#!/usr/bin/env python3
"""Development acquisition only; the Android app uses its signed catalog/gateway.

Fetch the exact unchanged SSE runtime files plus optional numerical reference
source into the external build root. This does not train or convert a model.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request

repository = Path(__file__).resolve().parents[1]
build_root = Path(os.environ.get("ANTISOCIAL_BUILD_ROOT", "/run/media/red/RedtopStorage/DarwinBuild/LiberBuild"))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--runtime-only", action="store_true", help="omit host-only Python/model-card reference")
args = parser.parse_args()
destination = build_root / "ai-foundation/sse-q4"
destination.mkdir(parents=True, exist_ok=True)
catalog = json.loads((repository / "app/src/main/ai-catalog/catalog.json").read_text())
pack = next(p for p in catalog["packs"] if p["id"] == "sse-q4-en")
files = [(item["filename"], item["url"], item["bytes"], item["sha256"]) for item in pack["assets"]]
if not args.runtime_only:
    base = "https://huggingface.co/RikkaBotan/quantized-stable-static-embedding-fast-retrieval-mrl-en/resolve/48de1fc899574177a7c2d0d4ae90bb902190935c/"
    files += [
        ("SSE_quantize.py", base + "SSE_quantize.py", 6041, "fe749e0659aee48d874b308d89aef37239b8baf03e42e02dba0299f1dbc95752"),
        ("README.md", base + "README.md", 30603, "a902c16dda0287d387889a2a16afa1deb60b9ba8506044750d2499f211dd93ae"),
    ]

def matches(path, size, expected):
    if not path.is_file() or path.stat().st_size != size:
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == expected

for name, url, size, expected in files:
    target = destination / name
    if not matches(target, size, expected):
        temporary = destination / (name + ".fetching")
        try:
            with urllib.request.urlopen(url, timeout=180) as response, temporary.open("wb") as output:
                total = 0
                while chunk := response.read(64 * 1024):
                    total += len(chunk)
                    if total > size:
                        raise RuntimeError(f"Reference source exceeds its declared size: {name}")
                    output.write(chunk)
            if not matches(temporary, size, expected):
                raise RuntimeError(f"Reference source integrity mismatch: {name}")
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    print(f"Verified {name}: {size} bytes, SHA256 {expected}")
