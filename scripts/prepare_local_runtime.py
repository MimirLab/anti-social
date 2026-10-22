#!/usr/bin/env python3
"""Build the small deterministic installer, never a phone compiler/toolchain.

ARM reuses inert Vulkan bytes from a pinned upstream QVAC release. The exact
custom Android postimage is reconstructed by BSDIFF40; donor code is never run.
All tool/download/cache/generated products live on the external build volume.
"""
import argparse, gzip, hashlib, importlib, json, os, pathlib, shutil, sys, tarfile, urllib.request, zipfile

ROOT=pathlib.Path(__file__).resolve().parents[1]
BUILD=pathlib.Path(os.environ.get('ANTISOCIAL_BUILD_ROOT','/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))
WORK=BUILD/'runtime-delivery'
URL='https://github.com/tetherto/qvac-fabric-llm.cpp/releases/download/b7349/llama-b7349-bin-ubuntu-vulkan-x64.tar.gz'
ARCHIVE_BYTES=56234405
ARCHIVE_SHA='fb42fb4f4b3ae623ba606f7bbbaebe620be4966e04a95a30b0fb6f9b670ce12a'
MEMBER='./libqvac-ggml-vulkan.so'
DONOR_BYTES=70496480
DONOR_SHA='02e380579c34253c0807d4a427ba7011718162499ada2c0d4dbad149f51aab7a'

def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def fetch(url,path,size=None,digest=None):
    if path.is_file() and (size is None or path.stat().st_size==size) and (digest is None or sha(path)==digest):return
    temporary=path.with_suffix(path.suffix+'.part')
    with urllib.request.urlopen(url,timeout=120) as i,temporary.open('wb') as o:shutil.copyfileobj(i,o)
    if size is not None and temporary.stat().st_size!=size:raise RuntimeError('Upstream artifact size mismatch')
    if digest is not None and sha(temporary)!=digest:raise RuntimeError('Upstream artifact hash mismatch')
    temporary.replace(path)

def delta_module():
    # Development-only wheel; not put on Android, not compiled on the phone.
    library=WORK/'python';library.mkdir(exist_ok=True);sys.path.insert(0,str(library))
    try:return importlib.import_module('bsdiff4')
    except ImportError:pass
    metadata=json.load(urllib.request.urlopen('https://pypi.org/pypi/bsdiff4/1.3.1/json',timeout=30))
    tag=f'cp{sys.version_info.major}{sys.version_info.minor}'
    wheel=next((a for a in metadata['urls'] if tag+'-' in a['filename'] and 'manylinux' in a['filename'] and 'x86_64' in a['filename']),None)
    if wheel is None:raise RuntimeError('Install the BSD-licensed bsdiff4 1.3.1 build tool for this host Python, then rebuild.')
    path=WORK/wheel['filename'];fetch(wheel['url'],path,digest=wheel['digests']['sha256'])
    with zipfile.ZipFile(path) as z:z.extractall(library)
    return importlib.import_module('bsdiff4')

def prepare(output,abis):
    WORK.mkdir(parents=True,exist_ok=True)
    output.mkdir(parents=True,exist_ok=True)
    packs=[];expected={'catalog.json'}
    for abi in abis:
        source=BUILD/'native-android'/abi/'libantisocial_local_ai.so'
        if not source.exists():raise RuntimeError(f'Build the pinned {abi} local runtime first.')
        target_sha=sha(source)
        pack={'abi':abi,'bytes':source.stat().st_size,'sha256':target_sha}
        if abi=='arm64-v8a':
            archive=WORK/URL.rsplit('/',1)[-1];fetch(URL,archive,ARCHIVE_BYTES,ARCHIVE_SHA)
            donor=WORK/'donor-vulkan.so'
            if not donor.is_file() or sha(donor)!=DONOR_SHA:
                with tarfile.open(archive) as tar,donor.open('wb') as out:shutil.copyfileobj(tar.extractfile(MEMBER),out)
            assert donor.stat().st_size==DONOR_BYTES and sha(donor)==DONOR_SHA
            patch=WORK/'arm64.bsdiff';receipt=WORK/'arm64.patch-receipt.json'
            current=json.loads(receipt.read_text()) if receipt.exists() else {}
            if not patch.is_file() or current!={'donor':DONOR_SHA,'target':target_sha,'patch':sha(patch)}:
                engine=delta_module();before=donor.read_bytes();after=source.read_bytes()
                delta=engine.diff(before,after)
                if engine.patch(before,delta)!=after:raise RuntimeError('Runtime delta did not reproduce the exact custom binary.')
                patch.write_bytes(delta);receipt.write_text(json.dumps({'donor':DONOR_SHA,'target':target_sha,'patch':sha(patch)},indent=2)+'\n')
            payload=output/f'{abi}.bsdiff';shutil.copyfile(patch,payload)
            pack.update(donorUrl=URL,donorArchiveBytes=ARCHIVE_BYTES,donorArchiveSha256=ARCHIVE_SHA,
                donorMember=MEMBER,donorBytes=DONOR_BYTES,donorSha256=DONOR_SHA)
        else:
            # Emulator engine is small and has no Vulkan shader bank to borrow.
            # Shipping its compressed postimage costs less than a donor+patch.
            payload=output/f'{abi}.packed'
            with source.open('rb') as i,payload.open('wb') as f,gzip.GzipFile(fileobj=f,mode='wb',compresslevel=9,mtime=0,filename='') as o:shutil.copyfileobj(i,o)
        pack.update(payload=payload.name,payloadBytes=payload.stat().st_size,payloadSha256=sha(payload))
        packs.append(pack);expected.add(payload.name)
    (output/'catalog.json').write_text(json.dumps({'schema':'antisocial-native-runtime-v1','packs':packs},indent=2)+'\n')
    for stale in output.iterdir():
        if stale.is_file() and stale.name not in expected:stale.unlink()
    # Notices are checked in beside the app's other runtime notices, so a
    # preinstalled host tool cannot accidentally omit them from the release.
    print(json.dumps({'installerBytes':sum(p['payloadBytes'] for p in packs),'runtimeBytes':sum(p['bytes'] for p in packs),'abis':abis}))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=pathlib.Path,required=True)
    parser.add_argument('--abi',action='append',choices=['arm64-v8a','x86_64'])
    args=parser.parse_args();prepare(args.output,args.abi or ['arm64-v8a','x86_64'])
