#!/usr/bin/env python3
"""Publish a verified fixed-name Android download on a versioned GitHub release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tag', help='Existing versioned release, e.g. v0.35.45')
    parser.add_argument('--repo', default='MimirLab/anti-social')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'v\d+\.\d+\.\d+', args.tag):
        parser.error('A versioned vMAJOR.MINOR.PATCH release is required.')
    if not re.fullmatch(r'[\w.-]+/[\w.-]+', args.repo):
        parser.error('Repository must be OWNER/NAME.')
    gh = os.environ.get('GH_CLI', 'gh')
    build = Path(os.environ.get('ANTISOCIAL_BUILD_ROOT', '/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))

    def call(*arguments):
        return subprocess.check_output([gh, *arguments], text=True)

    def digest(path):
        with path.open('rb') as stream:
            return hashlib.file_digest(stream, 'sha256').hexdigest()

    # Listing also finds drafts, which the public by-tag endpoint excludes.
    pages = json.loads(call('api', f'repos/{args.repo}/releases?per_page=100', '--paginate', '--slurp'))
    releases = [release for page in pages for release in page]
    matches = [release for release in releases if release['tag_name'] == args.tag]
    if len(matches) != 1:
        raise SystemExit('The versioned GitHub release must exist before publishing.')
    release = matches[0]

    def read_release():
        return json.loads(call('api', f"repos/{args.repo}/releases/{release['id']}"))

    version = args.tag[1:]
    assets = {a['name']: a for a in release['assets']}
    candidates = [a for a in assets.values() if a['name'].startswith(f'anti-social-{version}-') and a['name'].endswith('.apk') and a['state'] == 'uploaded' and 'debug' not in a['name']]
    if len(candidates) != 1:
        raise SystemExit('Exactly one uploaded versioned, non-debug APK is required.')
    original = candidates[0]
    apk = args.apk or build / 'artifacts' / original['name']
    apk_hash = digest(apk)
    if original.get('digest') != 'sha256:' + apk_hash or original['size'] != apk.stat().st_size:
        raise SystemExit('Local APK does not match the versioned GitHub asset.')
    source = assets.get(f'anti-social-{version}-source.zip')
    if not source or source['state'] != 'uploaded' or not re.fullmatch(r'sha256:[a-f0-9]{64}', source.get('digest') or ''):
        raise SystemExit('Uploaded, hash-identified corresponding source ZIP is required.')
    fixed = assets.get('anti-social.apk')
    if fixed and (fixed['state'] != 'uploaded' or fixed.get('digest') != 'sha256:' + apk_hash or fixed['size'] != apk.stat().st_size):
        raise SystemExit('Existing fixed-name APK differs; refusing to replace published bytes.')
    # Do not silently move a reader's update bookmark back to an older version.
    official = [r['tag_name'] for r in releases if not r['draft'] and not r['prerelease'] and re.fullmatch(r'v\d+\.\d+\.\d+', r['tag_name'])]
    numbers = lambda tag: tuple(map(int, tag[1:].split('.')))
    if any(numbers(tag) > numbers(args.tag) for tag in official):
        raise SystemExit('A newer official release exists; refusing to downgrade Latest.')
    url = f'https://github.com/{args.repo}/releases/latest/download/anti-social.apk'
    if args.dry_run:
        print(json.dumps({'tag': args.tag, 'sha256': apk_hash, 'uploadAlias': fixed is None, 'permanentUrl': url}, indent=2))
        return
    scratch = build / 'github-publish'
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='release-alias-', dir=scratch) as directory:
        alias = Path(directory) / 'anti-social.apk'
        try:
            os.link(apk, alias)
        except OSError:
            shutil.copyfile(apk, alias)
        if fixed is None:
            call('release', 'upload', args.tag, str(alias), '--repo', args.repo)
        checked = {a['name']: a for a in read_release()['assets']}
        fixed = checked['anti-social.apk']
        if fixed['state'] != 'uploaded' or fixed.get('digest') != 'sha256:' + apk_hash or fixed['size'] != apk.stat().st_size:
            raise SystemExit('Published alias integrity check failed; release was not promoted.')
        sums = Path(directory) / 'SHA256SUMS'
        names = [original['name'], source['name'], 'anti-social.apk']
        sums.write_text(''.join(f"{checked[name]['digest'].removeprefix('sha256:')}  {name}\n" for name in names))
        call('release', 'upload', args.tag, str(sums), '--repo', args.repo, '--clobber')
        published = {a['name']: a for a in read_release()['assets']}
        if published['SHA256SUMS'].get('digest') != 'sha256:' + digest(sums):
            raise SystemExit('Published checksum integrity check failed; release was not promoted.')
        call('release', 'edit', args.tag, '--repo', args.repo, '--draft=false', '--prerelease=false', '--latest', '--title', f'anti social {version}')
    print(url)


if __name__ == '__main__':
    main()
