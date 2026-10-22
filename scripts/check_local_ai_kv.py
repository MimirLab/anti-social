#!/usr/bin/env python3
"""Run cache-only comparisons against a matched F16 control, without downloads.

Example after build_local_ai.py --host --kv-probes:
  python3 scripts/check_local_ai_kv.py --model 2B --profiles f16,pq4,pq3,pq4_pq3
Use a separate output directory for a changed binary or fixture. Existing
evidence is never overwritten. Semantic and device admission remain separate.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
BUILD=Path(os.environ.get("ANTISOCIAL_BUILD_ROOT","/run/media/red/RedtopStorage/DarwinBuild/LiberBuild"))
SHA={"2B":"d5f01a4ecaf49239653308ee732ad1a0c5f802c0ddb997549fc7b694c8b45928","4B":"d406b1e912c0d2a4c124ad10bf38a76ae662fdc4d24b176451403dc7b7f30409","9B":"7a89ab48f5038c704480f60781f9b8d4035351f5f6beb7d11fad80001fde23f9"}

def digest(path):
    with path.open("rb") as stream:return hashlib.file_digest(stream,"sha256").hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model",choices=SHA,required=True)
    parser.add_argument("--backend",choices=["cpu","vulkan"],default="cpu")
    parser.add_argument("--capacity",type=int,choices=[2048,8192,32768],default=2048)
    parser.add_argument("--profiles",default="f16,pq4")
    parser.add_argument("--positioning",choices=["native","yarn2"],default="native")
    parser.add_argument("--reference-positioning",choices=["native","yarn2"],help="Explicit cross-position comparison; otherwise compare cache profiles under identical positions")
    parser.add_argument("--binary",type=Path)
    parser.add_argument("--output",type=Path,default=BUILD/"inference-kv/reproducible-comparisons-v3")
    parser.add_argument("--calibration",type=Path)
    args=parser.parse_args()
    profiles=args.profiles.split(",")
    if args.binary is None:
        args.binary=BUILD/f"ai-foundation/native-build/host-{args.backend}-antisocial-kv-v3/antisocial-kv-logits-reference"
    reference_positioning=args.reference_positioning or args.positioning
    comparing_positioning=reference_positioning!=args.positioning
    allowed={"f16","q8","q4","pq4","pq3","pq4_pq3","signed4","planar4","iso4","norm4","norm4v3"}
    if not profiles or any(p not in allowed for p in profiles):parser.error("Unknown cache profile")
    if not args.output.resolve().is_relative_to(BUILD.resolve()):parser.error("Keep evidence under ANTISOCIAL_BUILD_ROOT")
    model=BUILD/f"ai-foundation/models/Ternary-Bonsai-2-{args.model}-PQ2_0.gguf"
    if digest(model)!=SHA[args.model]:raise RuntimeError("Model integrity failed")
    fixtures=ROOT/"native/local_ai/fixtures"
    corpus=fixtures/"cache-fidelity-corpus-v1.txt"
    gates=fixtures/"predeclared-gates-v1.json"
    declared=json.loads(gates.read_text())
    if digest(corpus)!=declared["corpus_sha256"]:raise RuntimeError("Frozen source corpus changed")
    args.output.mkdir(parents=True,exist_ok=True)
    reference_prefix=f"{args.model}-{args.backend}-{args.capacity}-{reference_positioning}-f16"
    reference=args.output/f"{reference_prefix}.logits"
    group=f"{args.model}-{args.backend}-{args.capacity}-{args.positioning}"
    if comparing_positioning:group+=f"-vs-{reference_positioning}"
    results=[]
    for name in profiles:
        prefix=args.output/f"{group}-{name}"
        if prefix.with_suffix(".identity.json").exists():raise RuntimeError(f"Refusing to overwrite {prefix}")
        profile={"norm4":"pq4","norm4v3":"pq4_pq3"}.get(name,name)
        command=[str(args.binary),str(model),args.backend,profile,str(args.capacity),str(corpus),str(reference),f"--positioning={args.positioning}"]
        if comparing_positioning:command.append("--compare-positioning")
        identity={"model_sha256":SHA[args.model],"binary_sha256":digest(args.binary),"corpus_sha256":digest(corpus),"gates_sha256":digest(gates),"name":name,"profile":profile,"positioning":args.positioning,"reference_positioning":reference_positioning,"scope":"joint positional/cache numerical gate" if comparing_positioning else "cache-only numerical gate; semantic/device admission separate"}
        if name.startswith("norm"):
            if args.calibration is None:parser.error("Normalized profiles require --calibration")
            data=json.loads(args.calibration.read_text())
            if data["verified_model_sha256"]!=SHA[args.model]:raise RuntimeError("Calibration belongs to another model revision")
            identity["calibration_sha256"]=digest(args.calibration)
            command.append(str(args.calibration))
        if name!="f16" or comparing_positioning:
            baseline=json.loads((args.output/f"{reference_prefix}.identity.json").read_text())
            for key in ["model_sha256","binary_sha256","corpus_sha256","gates_sha256"]:
                if baseline[key]!=identity[key]:raise RuntimeError(f"F16 baseline identity mismatch: {key}")
            if baseline["positioning"]!=reference_positioning or baseline["reference_positioning"]!=reference_positioning:
                raise RuntimeError("Baseline was not generated under the requested positional recipe")
            identity["reference_logits_sha256"]=digest(reference)
        prefix.with_suffix(".identity.json").write_text(json.dumps(identity,indent=2)+"\n")
        print(f"Starting {args.model} {args.backend} {args.capacity} {name}",flush=True)
        result=subprocess.run(["python3",str(ROOT/"scripts/measure_local_ai.py"),str(prefix),*command])
        results.append({"profile":name,"returncode":result.returncode})
        if result.returncode not in (0,2) or (name=="f16" and not comparing_positioning and result.returncode):raise SystemExit(result.returncode)
    print(json.dumps({"completed":results}),flush=True)
    raise SystemExit(int(any(p["returncode"] for p in results)))

if __name__=="__main__":main()
