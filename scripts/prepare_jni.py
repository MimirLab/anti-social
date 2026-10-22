#!/usr/bin/env python3
"""Keep Android native build products on the external AntiSocial build volume."""
import os,pathlib,shutil
repo=pathlib.Path(__file__).resolve().parents[1]
root=pathlib.Path(os.environ.get('ANTISOCIAL_BUILD_ROOT','/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))
source=repo/'app/src/main/jniLibs';destination=root/'native-android'
destination.mkdir(parents=True,exist_ok=True)
if source.is_symlink():
    if source.resolve()!=destination.resolve():raise SystemExit('jniLibs points to a different build root; move its products deliberately before changing ANTISOCIAL_BUILD_ROOT.')
elif source.exists():
    # Preserve legacy input files before replacing the directory with a link.
    # Existing external products may be newer; refuse conflicting migrations.
    for item in source.rglob('*'):
        if item.is_file():
            target=destination/item.relative_to(source)
            if target.exists() and target.read_bytes()!=item.read_bytes():raise SystemExit(f'Conflicting native products: {target}')
    shutil.copytree(source,destination,dirs_exist_ok=True)
    shutil.rmtree(source)
if not source.is_symlink():source.symlink_to(destination,target_is_directory=True)
