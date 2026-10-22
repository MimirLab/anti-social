#!/usr/bin/env python3
"""Package the built debug APK and reproducible source tree on RedtopStorage."""
import argparse,hashlib,json,os,pathlib,shutil,zipfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--preview',action='store_true',help='Package the non-debuggable, locally signed release build for the laptop preview')
args=parser.parse_args()

repo=pathlib.Path(__file__).resolve().parents[1]
build=pathlib.Path(os.environ.get('ANTISOCIAL_BUILD_ROOT','/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))
apk_dir=build/('outputs/app/outputs/apk/release' if args.preview else 'outputs/app/outputs/apk/debug')
version=json.loads((apk_dir/'output-metadata.json').read_text())['elements'][0]['versionName']
destination=build/'artifacts';destination.mkdir(parents=True,exist_ok=True)
apk=destination/f'anti-social-{version}-{"preview" if args.preview else "debug"}.apk'
temporary=apk.with_suffix('.apk.part');shutil.copyfile(apk_dir/('app-release.apk' if args.preview else 'app-debug.apk'),temporary);temporary.replace(apk)
excluded={'.git','.codex','.gradle','.kotlin','.build','build','target','jniLibs','__pycache__','artifacts','.idea'}
files=[p for p in repo.rglob('*') if p.is_file() and not p.is_symlink() and not any(s in excluded for s in p.relative_to(repo).parts) and p.name!='local.properties' and not p.name.startswith('.env') and p.suffix not in {'.jks','.keystore','.iml','.pyc'}]
runtime_source=build/'ai-foundation/qvac-bc474701.tar.gz'
with zipfile.ZipFile(apk) as package:
    names=package.namelist()
    has_local_runtime=any(name.endswith('/libantisocial_local_ai.so') or name=='assets/native-runtime/catalog.json' for name in names)
    has_context_media=any(name.endswith('/libantisocial_context_media.so') for name in names)
    has_translation=any(name.endswith('/libantisocial_translation.so') for name in names)
    if 'assets/native-runtime/catalog.json' in names:
        # AAPT transparently rewrites .gz assets; do not ship a manifest whose
        # installed payload is missing or changed by packaging.
        runtime=json.loads(package.read('assets/native-runtime/catalog.json'))
        if runtime.get('schema')!='antisocial-native-runtime-v1':raise SystemExit('Invalid packaged native installer manifest.')
        if any(name.endswith('/libantisocial_local_ai.so') for name in names):raise SystemExit('The optional text engine was accidentally bundled as a native library.')
        for pack in runtime['packs']:
            name='assets/native-runtime/'+pack['payload']
            if name not in names:raise SystemExit(f'Missing packaged runtime payload: {name}')
            member=package.getinfo(name)
            if member.file_size!=pack['payloadBytes']:raise SystemExit(f'Runtime payload size changed during Android packaging: {name}')
            with package.open(member) as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
            if digest!=pack['payloadSha256']:raise SystemExit(f'Runtime payload hash changed during Android packaging: {name}')
source_archives=[]
if has_local_runtime:
    if not runtime_source.is_file() or hashlib.sha256(runtime_source.read_bytes()).hexdigest()!='c538dc1a88395e5a3bd623ec4acdc68485e78b12bc15fd3b2f955809093764ec':
        raise SystemExit('Build the pinned local AI runtime before packaging its corresponding source.')
    source_archives.append((runtime_source,'third_party/qvac-bc474701.tar.gz'))
if has_context_media:
    # Match scripts/build_context_media.py. Original upstream archives retain
    # their full notices; the checked-in native patches and build script ship
    # beside them. These names also support rebuilding from this source ZIP.
    context_sources={
        'whisper':'20f6c4c2dd25225326d284b145998ffe48efabfee4e6c94c0437cb4f78dbab76',
        'tesseract':'9218e62793116d42a9f6d14cd9348518b27f382096eea3d0f2d1a24616bb5884',
        'leptonica':'fa2b40c5caea96d1bb93a97486262aed8731b69ce25a84a6bf5d25323e33f631'}
    for name,expected in context_sources.items():
        source=build/'context-media'/f'{name}-source.tar.gz'
        if not source.is_file():source=repo/'third_party'/f'context-{name}-source.tar.gz'
        if not source.is_file():raise SystemExit(f'Build the pinned Context media runtime before packaging its corresponding {name} source.')
        with source.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
        if actual!=expected:raise SystemExit(f'Context media {name} source integrity check failed; corresponding source was not packaged.')
        source_archives.append((source,f'third_party/context-{name}-source.tar.gz'))
if has_translation:
    translation_sources=json.loads((repo/'native/translation/sources.json').read_text())
    for name,pin in translation_sources.items():
        source=build/'languages'/f'{name}-source.tar.gz'
        if not source.is_file():source=repo/'third_party'/f'translation-{name}-source.tar.gz'
        if not source.is_file():raise SystemExit(f'Build the pinned translation runtime before packaging its corresponding {name} source.')
        with source.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
        if actual!=pin['sha256']:raise SystemExit(f'Translation {name} source integrity check failed; corresponding source was not packaged.')
        source_archives.append((source,f'third_party/translation-{name}-source.tar.gz'))
archive_names={name for _,name in source_archives}
files=[p for p in files if p.relative_to(repo).as_posix() not in archive_names]
archive=destination/f'anti-social-{version}-source.zip';temporary=archive.with_suffix('.zip.part')
with zipfile.ZipFile(temporary,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for path in sorted(files):z.write(path,f'AntiSocial-{version}/'+path.relative_to(repo).as_posix())
    for path,name in source_archives:z.write(path,f'AntiSocial-{version}/'+name)
temporary.replace(archive)
packages=[p for p in (destination/f'anti-social-{version}-debug.apk',destination/f'anti-social-{version}-preview.apk',archive) if p.exists()]
manifest={p.name:{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in packages}
(destination/f'anti-social-{version}-checksums.json').write_text(json.dumps(manifest,indent=2)+'\n')
alias=destination/('anti-social-preview.apk' if args.preview else 'anti-social-current-debug.apk');temporary=alias.with_suffix('.apk.part')
temporary.unlink(missing_ok=True);temporary.symlink_to(apk.name);temporary.replace(alias)
print(json.dumps({'version':version,'sourceFiles':len(files)+len(source_archives),'artifacts':manifest},indent=2))
