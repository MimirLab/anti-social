#!/usr/bin/env python3
"""Fetch the pinned, upstream Content Authenticity Android reader artifact.

The archive and temporary download stay in AntiSocial's external build directory. No
runtime download, SDK account, or third-party inspection service is involved.
"""
import hashlib
import pathlib
import sys
import urllib.request

VERSION = "0.0.14"
URL = f"https://github.com/contentauth/c2pa-android/releases/download/{VERSION}/c2pa-release.aar"
SHA256 = "34a48ca041dabd641c40c9540f3674ea46f3c2ddecfd48dc2ccfbbb965c1ec95"
SIZE = 36_813_049


def verified(path):
    return path.exists() and path.stat().st_size == SIZE and hashlib.sha256(path.read_bytes()).hexdigest() == SHA256


def main():
    target = pathlib.Path(sys.argv[1]).resolve()
    target.parent.mkdir(parents=True, exist_ok=True)
    if verified(target):
        return
    temporary = target.with_suffix(".download")
    try:
        with urllib.request.urlopen(URL, timeout=120) as source, temporary.open("wb") as dest:
            count = 0
            while chunk := source.read(1024 * 1024):
                count += len(chunk)
                if count > SIZE:
                    raise RuntimeError("C2PA archive exceeds pinned size")
                dest.write(chunk)
        if not verified(temporary):
            raise RuntimeError("C2PA archive does not match pinned SHA-256")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
