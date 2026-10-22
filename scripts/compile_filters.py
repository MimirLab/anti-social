#!/usr/bin/env python3
"""Compile the exact bundled seed on the host, using the app's pinned engine."""
import gzip,hashlib,json,os,pathlib,subprocess,sys,tempfile

repo=pathlib.Path(__file__).resolve().parents[1]
build=pathlib.Path(os.environ.get('ANTISOCIAL_BUILD_ROOT','/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))
assets=repo/'app/src/main/assets'
seed=assets/'network-rules.seed'
output=assets/'network-rules.compiled'
metadata_path=assets/'network-rules.compiled.json'
previous=json.loads(metadata_path.read_text()) if metadata_path.exists() else {}
if '--check' in sys.argv:
    metadata=json.loads((assets/'network-rules.compiled.json').read_text())
    if metadata['seedSha256']!=hashlib.sha256(seed.read_bytes()).hexdigest() or metadata['sha256']!=hashlib.sha256(output.read_bytes()).hexdigest():
        raise SystemExit('Compiled protection is stale. Run bash scripts/native.sh to rebuild it.')
    raise SystemExit(0)
with tempfile.TemporaryDirectory(prefix='filter-seed-',dir=build) as work:
    raw=pathlib.Path(work)/'rules.txt'
    raw.write_bytes(gzip.decompress(seed.read_bytes()))
    compiled=pathlib.Path(work)/'rules.bin'
    subprocess.run([str(build/'rust-target/release/compile-seed'),str(raw),str(compiled)],check=True)
    output.write_bytes(compiled.read_bytes())
metadata={'seedSha256':hashlib.sha256(seed.read_bytes()).hexdigest(),
          'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),
          'entries':json.loads((assets/'filter-seed-status.json').read_text())['rules']}
metadata.update(json.loads(subprocess.check_output([str(build/'rust-target/release/compile-seed'),'--inspect',str(output)],text=True)))
prior=previous.get('previousSeedSha256',[])
if previous.get('sha256') and previous['sha256']!=metadata['sha256']:prior=prior+[previous['sha256']]
metadata['previousSeedSha256']=list(dict.fromkeys(prior))[-8:]
metadata_path.write_text(json.dumps(metadata,indent=2)+'\n')
print(f'Bundled compiled protection: {metadata["entries"]} entries, {output.stat().st_size} bytes',flush=True)
