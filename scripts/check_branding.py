#!/usr/bin/env python3
"""Reject stale product identifiers without rewriting provenance or build history."""
from pathlib import Path
import re
import sys

REPO = Path(__file__).resolve().parents[1]
# These are real development-volume/AVD locations, not Android identities.
HISTORICAL_PATHS = ("LiberBuild", "/home/red/Desktop/Liber", "LiberPreview", "LiberLinks35")
OLD_ID = re.compile(r"app\.liber\b|Java_app_liber_|libliber_|(?<![A-Za-z])(?:Liber(?:\b|[A-Z_])|LIBER(?:\b|_)|liber(?=[_.-]|\b))")
# A cryptographically signed test asset keeps its historical signer. Rebranding
# that evidence would invalidate the signature; permit only this exact assertion.
SIGNED_FIXTURE_ASSERTION = ("app/src/androidTest/kotlin/app/antisocial/android/C2paInspectorTest.kt", 'assertEquals("Liber Fixture Studio",result.signer)')
findings = []
for base in ("app", "core", "native", "scripts"):
    for path in (REPO / base).rglob("*"):
        if path.is_symlink() or not path.is_file() or any(part in ("jniLibs", "build", "__pycache__") for part in path.parts):
            continue
        if path == Path(__file__) or "-notices" in str(path) or path.name in ("LICENSE", "COPYING"):
            continue
        try:
            body = path.read_text()
        except UnicodeError:
            continue
        for number, line in enumerate(body.splitlines(), 1):
            if (path.relative_to(REPO).as_posix(),line.strip()) == SIGNED_FIXTURE_ASSERTION:
                continue
            for allowed in HISTORICAL_PATHS:
                line = line.replace(allowed, "")
            if OLD_ID.search(line):
                findings.append(f"{path.relative_to(REPO)}:{number}")
if findings:
    sys.exit("Stale product identifiers:\n" + "\n".join(findings))
print("Active application, native interfaces and build tools use anti social identifiers.")
