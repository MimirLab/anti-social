#!/usr/bin/env python3
"""Check public source text against a privately supplied reproduction denylist.

The denylist and detailed report must stay outside the checkout. Neither matched
values nor source lines are printed. This supplements review and source-archive
correspondence; compressed upstream sources retain their separate hash checks.
"""
import argparse
import html
import hashlib
import json
from pathlib import Path
import subprocess
import unicodedata
from urllib.parse import unquote
import zipfile


def normalized(value):
    return unicodedata.normalize('NFKC', html.unescape(unquote(value))).casefold()


def scan(entries, needles, reviewed=None):
    findings = []
    scanned = skipped = unchanged = 0
    for name, data in entries:
        if (reviewed or {}).get(name) == hashlib.sha256(data).hexdigest():
            unchanged += 1
            continue
        try:
            if b'\x00' in data[:4096]:
                raise UnicodeError()
            text = data.decode('utf-8')
        except UnicodeError:
            skipped += 1
            continue
        scanned += 1
        for number, line in enumerate(text.splitlines(), 1):
            value = normalized(line)
            findings.extend({'file': name, 'line': number, 'rule': index}
                            for index, needle in enumerate(needles) if needle in value)
    return {'textFiles': scanned, 'binaryFiles': skipped, 'reviewedUnchangedFiles': unchanged, 'findings': findings}


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--denylist-file', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--source-zip', type=Path)
    parser.add_argument('--zip-prefix', default='')
    parser.add_argument('--reviewed-hashes', type=Path, help='Outside-checkout JSON map of reviewed public files to SHA-256; changed bytes lose the exemption.')
    args = parser.parse_args()
    if args.zip_prefix and not args.source_zip:
        parser.error('--zip-prefix requires --source-zip; no checkout scan was performed.')
    root = args.root.resolve()
    for path in (args.denylist_file, args.report, *([args.reviewed_hashes] if args.reviewed_hashes else [])):
        if path.resolve().is_relative_to(root):
            parser.error('Private inputs and reports must stay outside the checkout.')
    values = json.loads(args.denylist_file.read_text())
    if not isinstance(values, list) or not 1 <= len(values) <= 128 or not all(isinstance(v, str) and v.strip() and 2 <= len(v) <= 256 for v in values):
        parser.error('Supply a private JSON list of bounded, nonempty strings.')
    needles = [normalized(v) for v in values]
    reviewed = json.loads(args.reviewed_hashes.read_text()) if args.reviewed_hashes else {}
    if not isinstance(reviewed, dict) or len(reviewed) > 128 or not all(isinstance(k, str) and isinstance(v, str) and len(v) == 64 and all(c in '0123456789abcdef' for c in v) for k, v in reviewed.items()):
        parser.error('Reviewed files require explicit SHA-256 identities.')
    if args.source_zip:
        with zipfile.ZipFile(args.source_zip) as archive:
            if args.zip_prefix and any(not entry.filename.startswith(args.zip_prefix) for entry in archive.infolist()):
                parser.error('The source archive does not have the declared prefix.')
            result = scan(((entry.filename.removeprefix(args.zip_prefix), archive.read(entry)) for entry in archive.infolist() if not entry.is_dir()), needles, reviewed)
    else:
        names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root).decode().split('\0')
        result = scan(((name, (root / name).read_bytes()) for name in dict.fromkeys(names) if name and (root / name).is_file() and not (root / name).is_symlink()), needles, reviewed)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open('w') as output:
        args.report.chmod(0o600)
        json.dump(result, output, indent=2)
        output.write('\n')
    print(json.dumps({key: len(value) if key == 'findings' else value for key, value in result.items()}))
    return int(bool(result['findings']))


if __name__ == '__main__':
    raise SystemExit(main())
