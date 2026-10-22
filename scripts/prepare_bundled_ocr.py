#!/usr/bin/env python3
"""Prepare the small signed English flyer reader. No text model or private state."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import urllib.request

REPO = Path(__file__).resolve().parents[1]
ROOT = Path(os.environ.get('ANTISOCIAL_BUILD_ROOT', '/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))

def prepare(output):
    catalog = json.loads((REPO / 'app/src/main/context-media-catalog/catalog.json').read_text())
    pack = next(p for p in catalog['packs'] if p['id'] == 'ocr-eng')
    for asset in pack['assets']:
        cached = ROOT / 'downloads' / ('discovery-' + asset['sha256'] + '.traineddata')
        def valid(path):
            return path.is_file() and path.stat().st_size == asset['bytes'] and hashlib.sha256(path.read_bytes()).hexdigest() == asset['sha256']
        if not valid(cached):
            cached.parent.mkdir(parents=True, exist_ok=True)
            partial = cached.with_suffix('.part')
            try:
                with urllib.request.urlopen(asset['url'], timeout=120) as source, partial.open('wb') as dest:
                    count = 0
                    while chunk := source.read(65536):
                        count += len(chunk)
                        if count > asset['bytes']:
                            raise RuntimeError('Bundled OCR download exceeds its signed size')
                        dest.write(chunk)
                if not valid(partial):
                    raise RuntimeError('Bundled OCR hash or length mismatch')
                partial.replace(cached)
            finally:
                partial.unlink(missing_ok=True)
        target = output / 'discovery-ocr' / asset['filename']
        target.parent.mkdir(parents=True, exist_ok=True)
        if not valid(target):
            shutil.copyfile(cached, target)
    print('Verified bundled English image reader:', pack['assets'][0]['bytes'], 'bytes')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'outputs/app/generated/discovery-assets')
    prepare(parser.parse_args().output)
