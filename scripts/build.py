#!/usr/bin/env python3
"""Build anti social from a clean checkout, including all native prerequisites.

All downloads, build products and test evidence use ANTISOCIAL_BUILD_ROOT.
No text/embedding weights, API credentials or social-reader state are needed.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time

REPO = Path(__file__).resolve().parents[1]
DEFAULT_ROOT = '/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'

def commands(target, variant, bootstrap=True, instrumentation=False):
    abis = ['arm64-v8a', 'x86_64'] if target == 'all' else [target]
    result = [[sys.executable, 'scripts/bootstrap.py', '--no-emulator']] if bootstrap else []
    result += [['bash', 'scripts/native.sh', 'android'], ['bash', 'scripts/embedding_native.sh', 'android']]
    for abi in abis:
        result.append([sys.executable, 'scripts/build_local_ai.py', '--android', abi] + (['--vulkan'] if abi == 'arm64-v8a' else []))
        result.append([sys.executable, 'scripts/build_context_media.py', '--android', abi])
        result.append([sys.executable, 'scripts/build_translation.py', '--android', abi])
    tasks = [':core:test', ':app:assembleDebugAndroidTest']
    for name in (['Debug', 'Release'] if variant == 'both' else [variant.title()]):
        tasks += [':app:assemble' + name, ':app:lint' + name]
    if instrumentation:
        tasks += [':app:connectedDebugAndroidTest']
    result.append(['bash', 'scripts/gradle.sh'] + ([] if target == 'all' else ['-PantisocialTargetAbi=' + target]) + tasks)
    if variant in ('debug', 'both'):
        result.append([sys.executable, 'scripts/package.py'])
    if variant in ('release', 'both'):
        result.append([sys.executable, 'scripts/package.py', '--preview'])
    return abis, result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=['all', 'arm64-v8a', 'x86_64'], default='all')
    parser.add_argument('--variant', choices=['both', 'debug', 'release'], default='both')
    parser.add_argument('--skip-bootstrap', action='store_true', help='Use tools already prepared in this build root')
    parser.add_argument('--instrumentation', action='store_true', help='Also test the connected owned Android device')
    parser.add_argument('--check-only', action='store_true', help='Print the dependency-ordered commands without running them')
    args = parser.parse_args()
    root = Path(os.environ.get('ANTISOCIAL_BUILD_ROOT', DEFAULT_ROOT)).expanduser().resolve()
    if root == REPO or REPO in root.parents:
        parser.error('Choose an external build root, outside the source checkout')
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        parser.error('This documented bootstrap currently supports Linux x86_64 hosts')
    required = ['bash', 'curl', 'git', 'flock', 'tar', 'zstd', 'cc', 'c++', 'pkg-config', 'patch']
    missing = [name for name in required if shutil.which(name) is None]
    if missing:
        parser.error('Install these host tools first: ' + ', '.join(missing))
    if shutil.disk_usage('/').free < 15 * 1024**3:
        parser.error('Keep at least 15 GiB free on the system filesystem')
    link = REPO / 'app/src/main/jniLibs'
    if link.is_symlink() and link.resolve() != root / 'native-android':
        parser.error('This checkout already belongs to another native build root. Use a fresh checkout for a clean build.')
    abis, steps = commands(args.target, args.variant, not args.skip_bootstrap, args.instrumentation)
    if args.check_only:
        print(json.dumps({'root': str(root), 'abis': abis, 'commands': steps}, indent=2))
        return
    root.mkdir(parents=True, exist_ok=True)
    for name in ['java-tmp', 'android-user', 'xdg-cache', 'pip-cache']:
        (root / name).mkdir(exist_ok=True)
    env = os.environ.copy()
    env.update(ANTISOCIAL_BUILD_ROOT=str(root), ANTISOCIAL_TARGET_ABIS=','.join(abis + (['armeabi-v7a'] if args.target == 'all' else [])),
               TMPDIR=str(root / 'java-tmp'), XDG_CACHE_HOME=str(root / 'xdg-cache'),
               PIP_CACHE_DIR=str(root / 'pip-cache'), PYTHONPYCACHEPREFIX=str(root / 'python-cache'), CARGO_BUILD_JOBS='2')
    receipt = {'started': time.time(), 'target': args.target, 'variant': args.variant, 'steps': []}
    evidence = root / 'clean-build'; evidence.mkdir(exist_ok=True)
    try:
        for command in steps:
            start = time.time()
            # Catalog signing precedes APK assembly; a fresh build has no debug key yet.
            if command[:2] == ['bash', 'scripts/gradle.sh'] and 'ANTISOCIAL_SIGNING_STORE' not in env:
                key = root / 'android-user/debug.keystore'
                if not key.exists():
                    subprocess.run([str(root / 'jdk/bin/keytool'), '-genkeypair', '-keystore', str(key), '-storepass', 'android',
                                    '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048',
                                    '-validity', '10000', '-dname', 'CN=Android Debug,O=Android,C=US'], env=env, check=True)
            completed = subprocess.run(command, cwd=REPO, env=env)
            receipt['steps'].append({'command': command, 'seconds': time.time() - start, 'exit': completed.returncode})
            if completed.returncode:
                raise SystemExit(completed.returncode)
        receipt['passed'] = True
    finally:
        receipt['finished'] = time.time()
        (evidence / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')

if __name__ == '__main__':
    main()
