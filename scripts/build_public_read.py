#!/usr/bin/env python3
"""Build the experimental byte engine only; never modifies or packages the APK.

Pinned development inputs and all extracted sources/binaries stay on the external
build volume. This tool is a build-time downloader, not an app networking path.
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
import tarfile
import urllib.request
import zipfile

REPO = Path(__file__).resolve().parent.parent
SOURCE = REPO / 'native/public_read'
PINS = json.loads((SOURCE / 'sources.json').read_text())


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
            left = pin.get('maxBytes', pin.get('bytes', 1024 * 1024))
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


def prepare(root, abi):
    include = root / 'include'
    notices = root / abi / 'notices'
    notices.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(fetch(root, PINS['boringssl'])) as archive:
        prefix = PINS['boringssl']['root'] + '/'
        selected = [v for v in archive.infolist() if v.filename.startswith(prefix + 'include/') or v.filename == prefix + 'LICENSE']
        if sum(v.file_size for v in selected) > 4 * 1024 * 1024:
            raise ValueError('Unexpected primitive headers size')
        for info in selected:
            rel = Path(info.filename).relative_to(prefix)
            if rel.is_absolute() or '..' in rel.parts or info.file_size > 1024 * 1024:
                raise ValueError('Invalid primitive header path')
            if info.is_dir():
                continue
            dest = include / rel.relative_to('include') if rel.parts[0] == 'include' else notices / 'BORINGSSL_SOURCE_LICENSE'
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(archive.read(info))
    for pin in PINS['headers']:
        dest = include / pin['name']
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(fetch(root, pin).read_bytes())
        if dest.name == 'nghttp2ver.h.in':
            dest.with_suffix('').write_text(dest.read_text().replace('@PACKAGE_VERSION@', '1.63.0').replace('@PACKAGE_VERSION_NUM@', '0x013f00'))
    library = root / abi / 'libprimitives.a'
    with tarfile.open(fetch(root, PINS['android'][abi]), 'r:gz') as archive:
        for info in archive:
            name = Path(info.name).name
            if name != 'libcurl-impersonate.a' and not name.startswith('LICENSE'):
                continue
            if not info.isfile() or info.size > 64 * 1024 * 1024:
                raise ValueError('Unexpected primitive archive member')
            dest = library if name == 'libcurl-impersonate.a' else notices / name
            stream = archive.extractfile(info)
            if stream is None:
                raise ValueError('Missing primitive archive member')
            dest.write_bytes(stream.read())
    if not library.is_file():
        raise ValueError('Missing primitive library')
    return include, library


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abi', choices=tuple(PINS['android']), action='append')
    parser.add_argument('--fixture-driver', action='store_true')
    parser.add_argument('--sanitize', action='store_true', help='Instrument first-party C++ with ASan/UBSan; development only')
    args = parser.parse_args()
    build = Path(os.environ.get('ANTISOCIAL_BUILD_ROOT', '/run/media/red/RedtopStorage/DarwinBuild/LiberBuild')).resolve()
    inputs = build / 'public-read-native'
    inputs.mkdir(parents=True, exist_ok=True)
    with (inputs / 'build.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        build_abis(args, build, inputs)


def build_abis(args, build, inputs):
    root = build / ('public-read-native-asan' if args.sanitize else 'public-read-native')
    root.mkdir(parents=True, exist_ok=True)
    tool = build / 'android-sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin'
    records = []
    for abi in args.abi or PINS['android']:
        include, library = prepare(inputs, abi)
        out = root / abi
        out.mkdir(parents=True, exist_ok=True)
        compiler = tool / (PINS['android'][abi]['triple'] + '-clang++')
        dest = out / 'libantisocial_public_read.so'
        common = [str(compiler), '-std=c++17', '-static-libstdc++', '-Os', '-Wall', '-Wextra', '-Werror', '-ffunction-sections', '-fdata-sections', '-I' + str(include), '-I' + str(SOURCE), '-Wl,-z,max-page-size=16384', '-Wl,--gc-sections', '-Wl,--exclude-libs,ALL']
        if args.sanitize:
            common += ['-g', '-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        subprocess.run(common + ['-shared', '-fPIC', '-Wl,--version-script=' + str(SOURCE / 'exports.map'), str(SOURCE / 'engine.cc'), str(library), '-ldl', '-lm', '-o', str(dest)], check=True)
        symbols = subprocess.check_output([str(tool / 'llvm-nm'), str(dest)], text=True)
        # The upstream ld -r object coalesces same-named .text.map_resize
        # sections from three libraries. Retaining nghttp2's section also keeps
        # two dead map helpers and their four allocator wrappers. These are
        # local memory functions, not QUIC/HTTP3 protocol or transport code.
        allocator_names = {'ngtcp2_mem_calloc', 'ngtcp2_mem_free', 'nghttp3_mem_calloc', 'nghttp3_mem_free'}
        # llvm-nm also lists data named curl_version_info.ssl_buffer on ARM64.
        # It is not a retained function; audit symbol types, not substrings.
        functions = '\n'.join(line for line in symbols.splitlines() if len(line.split()) >= 2 and line.split()[-2] in {'t', 'T', 'U', 'w', 'W'})
        matches = set(re.findall(r'\b(?:curl_[A-Za-z_0-9]+|Curl_[A-Za-z_0-9]+|ngtcp2_[A-Za-z_0-9]+|nghttp3_[A-Za-z_0-9]+|connect|socket|getaddrinfo)\b', functions))
        retained_allocators = sorted(matches & allocator_names)
        forbidden = sorted(matches - allocator_names)
        for name in retained_allocators:
            if not re.search(r'^[0-9a-f]+ t ' + re.escape(name) + r'$', symbols, re.MULTILINE):
                raise ValueError('Allocator residue must remain a local function: ' + name)
        if forbidden:
            raise ValueError('Unexpected transport/client symbols retained: ' + ','.join(sorted(set(forbidden))))
        (out / 'symbols.txt').write_text(symbols)
        elf = subprocess.check_output([str(tool / 'llvm-readelf'), '-l', '-d', str(dest)], text=True)
        for line in elf.splitlines():
            if line.strip().startswith('LOAD') and int(line.split()[-1], 16) < 16384:
                raise ValueError('Library lacks 16 KiB ELF alignment')
        if 'libc++_shared.so' in elf:
            raise ValueError('Unexpected shared C++ runtime')
        (out / 'elf.txt').write_text(elf)
        subprocess.run([str(tool / 'llvm-strip'), '--strip-unneeded', str(dest)], check=True)
        if args.sanitize:
            arch = 'x86_64' if abi == 'x86_64' else 'aarch64'
            runtime = next((tool.parent / 'lib/clang').glob('*/lib/linux/libclang_rt.asan-' + arch + '-android.so'))
            shutil.copy2(runtime, out / runtime.name)
        if args.fixture_driver:
            driver = out / 'public-read-fixture'
            subprocess.run(common + [str(SOURCE / 'fixtures/driver.cc'), '-L' + str(out), '-lantisocial_public_read', str(library), '-ldl', '-lm', '-Wl,-rpath,$ORIGIN', '-o', str(driver)], check=True)
            subprocess.run([str(tool / 'llvm-strip'), '--strip-unneeded', str(driver)], check=True)
            admission = out / 'public-read-admission'
            subprocess.run(common + [str(SOURCE / 'fixtures/admission.cc'), '-L' + str(out), '-lantisocial_public_read', '-Wl,-rpath,$ORIGIN', '-o', str(admission)], check=True)
        records.append({'abi': abi, 'sanitizer': args.sanitize, 'bytes': dest.stat().st_size, 'sha256': sha(dest), 'forbiddenSymbols': forbidden, 'retainedAllocatorHelpers': retained_allocators, 'sourceSha256': sha(SOURCE / 'engine.cc'), 'pinsSha256': sha(SOURCE / 'sources.json')})
    (root / 'build.json').write_text(json.dumps(records, indent=2) + '\n')
    print(json.dumps(records, indent=2))


if __name__ == '__main__':
    main()
