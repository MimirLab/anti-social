#!/usr/bin/env python3
"""Stage a hash-verified experimental native build; never compiles or fetches it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil

def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f,'sha256').hexdigest()

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--assets',type=Path,required=True);p.add_argument('--abi',action='append');a=p.parse_args()
    build=Path(os.environ.get('ANTISOCIAL_BUILD_ROOT','/run/media/red/RedtopStorage/DarwinBuild/LiberBuild')).resolve()
    source=Path(__file__).resolve().parent.parent/'native/public_read';root=build/'public-read-source-native'
    output=a.output.resolve();assets=a.assets.resolve();output.relative_to(build);assets.relative_to(build)
    rows=json.loads((root/'build.json').read_text());copied=[]
    for abi in a.abi or ['arm64-v8a','x86_64']:
        if abi not in ['arm64-v8a','x86_64']:continue
        row=next((r for r in rows if r['abi']==abi and not r['sanitizer']),None)
        if row is None:raise RuntimeError('Build the public-read candidate for '+abi+' first')
        for name,key in [('engine.cc','sourceSha256'),('engine.h','headerSha256'),('exports.map','exportsSha256'),('jni.cc','jniSha256'),('sources.json','pinsSha256')]:
            if row.get(key)!=sha(source/name):raise RuntimeError('Stale public-read candidate: '+name)
        if row['builderSha256']!=sha(Path(__file__).with_name('build_public_read.py')):raise RuntimeError('Rebuild public-read with the current builder')
        library=root/abi/'libantisocial_public_read.so'
        if sha(library)!=row['sha256'] or row['forbiddenSymbols']:raise RuntimeError('Public-read integrity audit failed')
        dest=output/abi/library.name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(library,dest)
        notices=assets/'public-read-notices'/abi;shutil.copytree(root/abi/'notices',notices,dirs_exist_ok=True)
        copied.append(row)
    assets.mkdir(parents=True,exist_ok=True);(assets/'public-read-build.json').write_text(json.dumps(copied,indent=2)+'\n')

if __name__=='__main__':main()
