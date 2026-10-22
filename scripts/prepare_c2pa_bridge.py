#!/usr/bin/env python3
"""Compile only the small offline-context JNI bridge; reuse pinned upstream C2PA binaries."""
import argparse
import os
from pathlib import Path
import subprocess

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--abi',action='append');a=p.parse_args()
    root=Path(os.environ.get('ANTISOCIAL_BUILD_ROOT','/run/media/red/RedtopStorage/DarwinBuild/LiberBuild')).resolve()
    output=a.output.resolve();output.relative_to(root)
    sdk=Path(os.environ.get('ANDROID_HOME',root/'android-sdk'))
    tools=sdk/'ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin'
    source=Path(__file__).resolve().parent.parent/'app/src/main/cpp/c2pa_detached.c'
    targets={'arm64-v8a':'aarch64-linux-android28','armeabi-v7a':'armv7a-linux-androideabi28','x86_64':'x86_64-linux-android28'}
    for abi in a.abi or targets:
        dest=output/abi/'libantisocial_c2pa_bridge.so';dest.parent.mkdir(parents=True,exist_ok=True)
        subprocess.run([str(tools/(targets[abi]+'-clang')),'-shared','-fPIC','-O2','-Werror','-Wall','-Wextra','-Wl,-z,max-page-size=16384',str(source),'-ldl','-o',str(dest)],check=True)
        subprocess.run([str(tools/'llvm-strip'),'--strip-unneeded',str(dest)],check=True)
if __name__=='__main__':main()
