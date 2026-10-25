#!/usr/bin/env python3
"""Build the experimental byte engine from pinned upstream sources, outside the APK.

All downloads, tools, caches, logs and outputs stay on the external build volume.
This is a developer build-time downloader, never an app networking bypass.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import venv

REPO = Path(__file__).resolve().parent.parent
SOURCE = REPO / 'native/public_read'
PINS = json.loads((SOURCE / 'sources.json').read_text())
CONFIG = {
    'boringssl': (['-DBUILD_TESTING=OFF'], ['ssl', 'crypto']),
    'nghttp2': (['-DENABLE_LIB_ONLY=ON', '-DBUILD_STATIC_LIBS=ON', '-DBUILD_SHARED_LIBS=OFF', '-DBUILD_TESTING=OFF', '-DENABLE_FAILMALLOC=OFF', '-DENABLE_DOC=OFF'], ['nghttp2_static']),
    'brotli': (['-DBUILD_SHARED_LIBS=OFF', '-DBROTLI_BUILD_TOOLS=OFF', '-DBROTLI_DISABLE_TESTS=ON'], ['brotlidec', 'brotlicommon']),
    'zlib': (['-DZLIB_BUILD_TESTING=OFF', '-DZLIB_BUILD_SHARED=OFF', '-DZLIB_BUILD_STATIC=ON'], ['zlibstatic']),
    'zstd': (['-DZSTD_BUILD_SHARED=OFF', '-DZSTD_BUILD_STATIC=ON', '-DZSTD_BUILD_PROGRAMS=OFF', '-DZSTD_BUILD_TESTS=OFF', '-DZSTD_BUILD_DICTBUILDER=OFF', '-DZSTD_BUILD_COMPRESSION=OFF', '-DZSTD_BUILD_DECOMPRESSION=ON', '-DZSTD_LEGACY_SUPPORT=OFF', '-DZSTD_MULTITHREAD_SUPPORT=OFF'], ['libzstd_static']),
}


def sha(path):
    with path.open('rb') as handle:
        return hashlib.file_digest(handle, 'sha256').hexdigest()


def fetch(root, pin):
    dest = root / 'downloads' / pin['sha256']
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() and sha(dest) == pin['sha256']:
        return dest
    temporary = dest.with_suffix('.part')
    try:
        with urllib.request.urlopen(pin['url'], timeout=45) as response, temporary.open('wb') as out:
            left = pin['maxBytes']
            while block := response.read(min(left + 1, 65536)):
                left -= len(block)
                if left < 0:
                    raise ValueError('Pinned build input exceeds allowance')
                out.write(block)
        if sha(temporary) != pin['sha256']:
            raise ValueError('Pinned build input hash differs: ' + pin['url'])
        temporary.replace(dest)
    finally:
        temporary.unlink(missing_ok=True)
    return dest


def extract(root, pin):
    dest = root / 'sources' / pin['root']
    marker = dest / '.antisocial-source-pin'
    if marker.is_file() and marker.read_text().strip() == pin['sha256']:
        return dest
    with tarfile.open(fetch(root, pin), 'r:gz') as archive:
        members = archive.getmembers()
        if len(members) > 20000 or sum(v.size for v in members) > 600 * 1024 * 1024:
            raise ValueError('Unexpected source archive size')
        for info in members:
            rel = Path(info.name).relative_to(pin['root'])
            if rel.is_absolute() or '..' in rel.parts or info.size > 32 * 1024 * 1024:
                raise ValueError('Invalid source archive path or size')
            out = dest / rel
            out.resolve().relative_to(root.resolve())
            if info.isfile():
                data = archive.extractfile(info).read()
                if not out.is_file() or sha(out) != hashlib.sha256(data).hexdigest():
                    out.parent.mkdir(parents=True, exist_ok=True)
                    out.write_bytes(data)
            elif not info.isdir() and not (info.issym() and rel.parts[0] == 'tests'):
                raise ValueError('Unsupported source archive member')
    marker.write_text(pin['sha256'] + '\n')
    return dest


def tools(build):
    root = build / 'ai-foundation/tools-venv'
    if not (root / 'bin/cmake').is_file() or not (root / 'bin/ninja').is_file():
        if not (root / 'bin/python').is_file():
            venv.create(root, with_pip=True)
        subprocess.run([str(root / 'bin/python'), '-m', 'pip', 'install', '--cache-dir', str(build / 'pip-cache'),
                        'cmake==' + PINS['tools']['cmake'], 'ninja==' + PINS['tools']['ninja']], check=True)
    return root / 'bin'


def build_primitives(args, build, inputs, root, abi, sources, tool_dir):
    cmake, ninja = str(tool_dir / 'cmake'), str(tool_dir / 'ninja')
    ndk = build / ('android-sdk/ndk/' + PINS['tools']['ndk'])
    env = os.environ.copy()
    env.update(TMPDIR=str(build / 'java-tmp'), PYTHONPYCACHEPREFIX=str(build / 'python-cache'),
               PATH=str(tool_dir) + ':' + env.get('PATH', ''))
    flags = '-Os -ffunction-sections -fdata-sections'
    if args.sanitize:
        # NDK issue 2065 reproduces on even a valid Base/Derived control under
        # this pinned Android runtime. Keep the remaining checks active and
        # report the vptr coverage gap instead of declaring a false clean run.
        flags += ' -fsanitize=address,undefined -fno-sanitize=vptr -fno-omit-frame-pointer'
    for name, (options, targets) in CONFIG.items():
        # Upstream's OSS-Fuzz configuration requires RTTI for UBSan vptr.
        # Keep the normal build unchanged; instrument the complete sanitizer
        # build rather than suppressing a check around mixed RTTI objects.
        if args.sanitize and name == 'boringssl':
            options = options + ['-DBORINGSSL_ALLOW_CXX_RUNTIME=ON']
        folder = root / 'build' / abi / name
        folder.mkdir(parents=True, exist_ok=True)
        source = sources[name] / 'build/cmake' if name == 'zstd' else sources[name]
        common = ['-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM=' + ninja, '-DCMAKE_BUILD_TYPE=Release',
                  '-DCMAKE_POSITION_INDEPENDENT_CODE=ON', '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
                  '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=' + ('c++_shared' if args.sanitize else 'c++_static'),
                  '-DCMAKE_C_FLAGS=' + flags, '-DCMAKE_CXX_FLAGS=' + flags,
                  '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384']
        log = root / (abi + '-' + name + '.log')
        print(abi, name, 'configure/build', flush=True)
        with log.open('w') as handle:
            for command in [[cmake, '-S', str(source), '-B', str(folder), *common, *options],
                            [cmake, '--build', str(folder), '--parallel', str(args.jobs), '--target', *targets]]:
                result = subprocess.run(command, env=env, stdout=handle, stderr=subprocess.STDOUT)
                if result.returncode:
                    handle.flush()
                    print('\n'.join(log.read_text().splitlines()[-35:]), file=sys.stderr)
                    raise subprocess.CalledProcessError(result.returncode, command)
    b = root / 'build' / abi
    libraries = [b / 'boringssl/libssl.a', b / 'boringssl/libcrypto.a', b / 'nghttp2/lib/libnghttp2.a',
                 b / 'brotli/libbrotlidec.a', b / 'brotli/libbrotlicommon.a', b / 'zstd/lib/libzstd.a', b / 'zlib/libz.a']
    for library in libraries:
        if not library.is_file():
            raise ValueError('Missing primitive library: ' + str(library))
    includes = [sources['boringssl'] / 'include', b / 'nghttp2/lib/includes', sources['nghttp2'] / 'lib/includes',
                sources['brotli'] / 'c/include', sources['zstd'] / 'lib', b / 'zlib', sources['zlib'], SOURCE]
    return libraries, includes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abi', choices=tuple(PINS['android']), action='append')
    parser.add_argument('--fixture-driver', action='store_true')
    parser.add_argument('--sanitize', action='store_true', help='Instrument engine and primitive C/C++ with ASan/UBSan; development only')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 8:
        parser.error('--jobs must be 1..8')
    build = Path(os.environ.get('ANTISOCIAL_BUILD_ROOT', '/run/media/red/RedtopStorage/DarwinBuild/LiberBuild')).resolve()
    inputs = build / 'public-read-source-native'
    inputs.mkdir(parents=True, exist_ok=True)
    with (inputs / 'build.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        build_abis(args, build, inputs)


def build_abis(args, build, inputs):
    root = build / ('public-read-source-native-asan' if args.sanitize else 'public-read-source-native')
    root.mkdir(parents=True, exist_ok=True)
    tool_dir = tools(build)
    sources = {name: extract(inputs, pin) for name, pin in PINS['libraries'].items()}
    tool = build / ('android-sdk/ndk/' + PINS['tools']['ndk'] + '/toolchains/llvm/prebuilt/linux-x86_64/bin')
    records = []
    for abi in args.abi or PINS['android']:
        libraries, includes = build_primitives(args, build, inputs, root, abi, sources, tool_dir)
        out = root / abi
        out.mkdir(parents=True, exist_ok=True)
        compiler = tool / (PINS['android'][abi]['triple'] + '-clang++')
        dest = out / 'libantisocial_public_read.so'
        common = [str(compiler), '-std=c++17', *([] if args.sanitize else ['-static-libstdc++']), '-Os', '-Wall', '-Wextra', '-Werror',
                  '-ffunction-sections', '-fdata-sections', *['-I' + str(p) for p in includes],
                  '-Wl,-z,max-page-size=16384', '-Wl,--gc-sections', '-Wl,--exclude-libs,ALL',
                  '-DAS_READ_BORINGSSL_REVISION="' + PINS['libraries']['boringssl']['revision'] + '"']
        if args.sanitize:
            common += ['-g', '-fsanitize=address,undefined', '-fno-sanitize=vptr', '-fno-omit-frame-pointer']
        subprocess.run(common + ['-shared', '-fPIC', '-Wl,--version-script=' + str(SOURCE / 'exports.map'),
                                str(SOURCE / 'engine.cc'), str(SOURCE / 'jni.cc'), *map(str, libraries), '-ldl', '-lm', '-o', str(dest)], check=True)
        symbols = subprocess.check_output([str(tool / 'llvm-nm'), str(dest)], text=True)
        functions = '\n'.join(v for v in symbols.splitlines() if len(v.split()) >= 2 and v.split()[-2] in {'t', 'T', 'U', 'w', 'W'})
        forbidden = sorted(set(re.findall(r'\b(?:curl_[A-Za-z_0-9]+|Curl_[A-Za-z_0-9]+|ngtcp2_[A-Za-z_0-9]+|nghttp3_[A-Za-z_0-9]+|connect|socket|getaddrinfo)\b', functions)))
        if forbidden:
            raise ValueError('Unexpected client/transport symbols: ' + ', '.join(forbidden))
        (out / 'symbols.txt').write_text(symbols)
        elf = subprocess.check_output([str(tool / 'llvm-readelf'), '-l', '-d', str(dest)], text=True)
        for line in elf.splitlines():
            if line.strip().startswith('LOAD') and int(line.split()[-1], 16) < 16384:
                raise ValueError('Library lacks 16 KiB ELF alignment')
        if ('libc++_shared.so' in elf) != args.sanitize:
            raise ValueError('C++ runtime differs from the selected qualification mode')
        (out / 'elf.txt').write_text(elf)
        subprocess.run([str(tool / 'llvm-strip'), '--strip-unneeded', str(dest)], check=True)
        notices = out / 'notices'
        notices.mkdir(exist_ok=True)
        for name, source in sources.items():
            license_file = source / ('COPYING' if name == 'nghttp2' else 'LICENSE')
            shutil.copy2(license_file, notices / (name + '-LICENSE'))
        (notices / 'source-identities.json').write_text(json.dumps(PINS, indent=2) + '\n')
        if args.sanitize:
            arch = 'x86_64' if abi == 'x86_64' else 'aarch64'
            runtime = next((tool.parent / 'lib/clang').glob('*/lib/linux/libclang_rt.asan-' + arch + '-android.so'))
            shutil.copy2(runtime, out / runtime.name)
            # NDK ASan and its C++ type checks must share one runtime with the
            # fixture executable. Preload it after ASan per NDK issue 988.
            runtime = tool.parent / ('sysroot/usr/lib/' + PINS['android'][abi]['triple'].removesuffix('26') + '/libc++_shared.so')
            shutil.copy2(runtime, out / runtime.name)
        if args.fixture_driver:
            driver = out / 'public-read-fixture'
            subprocess.run(common + [str(SOURCE / 'fixtures/driver.cc'), '-L' + str(out), '-lantisocial_public_read',
                                    str(libraries[1]), '-ldl', '-lm', '-Wl,-rpath,$ORIGIN', '-o', str(driver)], check=True)
            admission = out / 'public-read-admission'
            subprocess.run(common + [str(SOURCE / 'fixtures/admission.cc'), '-L' + str(out), '-lantisocial_public_read',
                                    '-Wl,-rpath,$ORIGIN', '-o', str(admission)], check=True)
        records.append({'abi': abi, 'sanitizer': args.sanitize, 'primitiveCodeInstrumented': args.sanitize,
                        'sanitizerChecks': ['address', 'undefined-except-vptr'] if args.sanitize else [],
                        'bytes': dest.stat().st_size, 'sha256': sha(dest), 'forbiddenSymbols': forbidden,
                        'sourceSha256': sha(SOURCE / 'engine.cc'), 'headerSha256': sha(SOURCE / 'engine.h'), 'exportsSha256': sha(SOURCE / 'exports.map'), 'jniSha256': sha(SOURCE / 'jni.cc'), 'pinsSha256': sha(SOURCE / 'sources.json'),
                        'builderSha256': sha(Path(__file__)), 'primitiveArchives': {p.name + ':' + str(p.parent.name): sha(p) for p in libraries}})
    (root / 'build.json').write_text(json.dumps(records, indent=2) + '\n')
    print(json.dumps(records, indent=2))


if __name__ == '__main__':
    main()
