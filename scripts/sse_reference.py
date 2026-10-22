#!/usr/bin/env python3
"""Development host only: run the pinned, hash-verified published SSE Python loader.

No Python/reference source is bundled or executed on a user's phone. Environment and
model storage must be beneath ANTISOCIAL_BUILD_ROOT. Run with reference-venv/bin/python.
"""
import hashlib
import importlib.metadata
import importlib.util
import json
import os
from pathlib import Path
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

root = Path(os.environ.get("ANTISOCIAL_BUILD_ROOT", "/run/media/red/RedtopStorage/DarwinBuild/LiberBuild"))
pack = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "ai-foundation/sse-q4"
output = Path(sys.argv[2]) if len(sys.argv) > 2 else root / "ai-foundation/sse-reference.json"
reference = pack / "SSE_quantize.py"
assert hashlib.sha256(reference.read_bytes()).hexdigest() == "fe749e0659aee48d874b308d89aef37239b8baf03e42e02dba0299f1dbc95752"
spec = importlib.util.spec_from_file_location("published_sse_quantize", reference)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
torch.set_num_threads(1)
start = time.perf_counter()
model = module.SSEQ.load(str(pack)).eval()
load_ms = (time.perf_counter()-start)*1000
texts = [
    "", " \t\n\r", "\u0000\ufffd", "hello", "hello hello", "Hello, WORLD!",
    "battery battery solar", "battery solar", "Battery replacement for a laptop.",
    "A laptop battery can be replaced safely.", "The battery cannot be replaced safely.",
    "The cost is $12.50, not $125.00.", "There are 1,234 batteries; 12 were recalled.",
    "\t Café\n naïve façade résumé Ångström élève  ", "Cafe\u0301 nai\u0308ve re\u0301sume\u0301",
    "İ I ı i Σ σ ς Straße STRASSE", "東京で電池を交換する。", "中文测试，电池更换。",
    "한국어 배터리 교체", "Батарея ноутбука не заряжается", "مرحبا بطارية", "हिन्दी बैटरी",
    "🪫🔋😀👨\u200d👩\u200d👧\u200d👦", "before\u0000after\u200bword\ufffdnext",
    "https://example.org/recipes?q=battery&count=12 @entity #Battery #DIY",
    "well-known re-posted can't won't coöperate", "x"*101, "unseenXYZtoken123456789",
    "[CLS] battery [SEP] [MASK] [UNK] [PAD]", "10kg 10 kg -10°C 1e-3 9999999999999999",
    "Correction: the report did not say the battery was unsafe.",
    "The report said the battery was unsafe; the author later corrected that statement.",
    ("The laptop battery costs 12 dollars, not 120 dollars. "*512),
    ("repeat "*2048),
]
cases = []
durations = []
with torch.no_grad():
    for text in texts:
        start = time.perf_counter()
        ids = model.tokenizer.encode(text, add_special_tokens=False).ids
        if ids:
            features = model.tokenize([text])
            mean = model.embedding(features["input_ids"], features["offsets"])
            head = model.dyt(mean)
            final = F.normalize(model(features)["sentence_embedding"], p=2, dim=1)
            row = {"text": text, "ids": ids, "mean": mean[0].tolist(), "head": head[0].tolist(), "vector": final[0].tolist()}
        else:
            row = {"text": text, "ids": [], "empty": True}
        durations.append((time.perf_counter()-start)*1000)
        cases.append(row)
    rows = [{"id": i, "values": model.embedding.weight[i].tolist()} for i in [0,1,100,101,102,999,2003,30521]]
result = {"schema":"antisocial-sse-reference-v1", "reference_sha256":"fe749e0659aee48d874b308d89aef37239b8baf03e42e02dba0299f1dbc95752", "model_revision":"48de1fc899574177a7c2d0d4ae90bb902190935c", "versions":{name:importlib.metadata.version(name) for name in ["torch","numpy","tokenizers","safetensors","transformers","sentence-transformers"]}, "load_ms":load_ms, "case_ms":durations,"rows":rows,"cases":cases}
output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps(result,ensure_ascii=False,separators=(",",":"))+"\n")
print(json.dumps({"cases":len(cases),"empty_cases":sum(not c["ids"] for c in cases),"sample_rows":len(rows),"load_ms":load_ms,"output":str(output),"sha256":hashlib.sha256(output.read_bytes()).hexdigest(),"versions":result["versions"]},indent=2))
