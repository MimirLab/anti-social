#!/usr/bin/env python3
"""Replay the frozen Context contribution suite through the production runtime.

This is source selection, not a new prompt-tuning run. Compare one changed cache
or positional recipe at a time. Evidence is never overwritten.
"""
import argparse
import json
import os
from pathlib import Path
import select
import subprocess
import time
from check_local_ai_kv import BUILD, ROOT, SHA, digest

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model",choices=SHA,required=True)
    parser.add_argument("--backend",choices=["cpu","vulkan"],default="cpu")
    parser.add_argument("--cache",default="f16_reference")
    parser.add_argument("--positioning",choices=["native","yarn2"],default="native")
    parser.add_argument("--sampling",choices=["prism_non_thinking_reference_v1","context_greedy_v1"],default="prism_non_thinking_reference_v1")
    parser.add_argument("--capacity",choices=[2048,8192],type=int,default=2048)
    parser.add_argument("--binary",type=Path)
    parser.add_argument("--output",type=Path,default=BUILD/"inference-kv/context-quality-v3")
    parser.add_argument("--compare",type=Path,help="Completed baseline .jsonl from this exact binary/model/source suite")
    args=parser.parse_args()
    if not args.output.resolve().is_relative_to(BUILD.resolve()):parser.error("Keep evidence under ANTISOCIAL_BUILD_ROOT")
    if args.binary is None:args.binary=BUILD/f"ai-foundation/native-build/host-{args.backend}-antisocial-kv-v3/antisocial-kv-quality-reference"
    model=BUILD/f"ai-foundation/models/Ternary-Bonsai-2-{args.model}-PQ2_0.gguf"
    if digest(model)!=SHA[args.model]:raise RuntimeError("Model integrity failed")
    fixtures=ROOT/"native/local_ai/fixtures"
    case_file=fixtures/"context-contribution-cases-v1.json";prompt_file=fixtures/"context-contribution-prompts-v1.json"
    # These hashes identify the previously frozen 24 cases and unchanged prompt.
    if digest(case_file)!="0adfff3d0c95ef6f58fdd0e6468adab0a7abec0e0b09037e594b323fcad33e15":raise RuntimeError("Frozen cases changed")
    if digest(prompt_file)!="807d78397a32d70e010e5ad9ae31cb212716a674f60319ca75e9544ebe67edfc":raise RuntimeError("Frozen prompts changed")
    cases=json.loads(case_file.read_text());system=json.loads(prompt_file.read_text())["select"]
    identity={"model_sha256":SHA[args.model],"binary_sha256":digest(args.binary),"cases_sha256":digest(case_file),"prompts_sha256":digest(prompt_file),"protocol":"select","model":args.model,"backend":args.backend,"cache":args.cache,"positioning":args.positioning,"sampling":args.sampling,"capacity":args.capacity}
    baseline=None
    if args.compare:
        baseline_identity=json.loads(args.compare.with_suffix(".identity.json").read_text())
        for key in ["model_sha256","binary_sha256","cases_sha256","prompts_sha256","protocol","backend","capacity"]:
            if baseline_identity[key]!=identity[key]:raise RuntimeError(f"Baseline identity mismatch: {key}")
        baseline_summary=json.loads(args.compare.with_suffix(".summary.json").read_text())
        if not baseline_summary["complete"]:raise RuntimeError("Baseline is incomplete")
        baseline={d["name"]:d for d in map(json.loads,args.compare.read_text().splitlines())}
        if set(baseline)!={c["name"] for c in cases}:raise RuntimeError("Baseline case set changed")
        identity["baseline_sha256"]=digest(args.compare)
    args.output.mkdir(parents=True,exist_ok=True)
    tag=f"{args.model}-{args.backend}-{args.capacity}-{args.cache}-{args.positioning}"
    if args.sampling!="prism_non_thinking_reference_v1":tag+="-"+args.sampling
    target=args.output/f"{tag}.jsonl"
    if any(target.with_suffix(s).exists() for s in [".jsonl",".identity.json",".summary.json",".stderr"]):raise RuntimeError("Refusing to overwrite evidence")
    target.with_suffix(".identity.json").write_text(json.dumps(identity,indent=2)+"\n")
    env=os.environ.copy();env.update(TMPDIR=str(BUILD/"java-tmp"),XDG_CACHE_HOME=str(BUILD/"inference-kv/cache"),MESA_SHADER_CACHE_DIR=str(BUILD/"inference-kv/mesa-cache"),__GL_SHADER_DISK_CACHE_PATH=str(BUILD/"inference-kv/nvidia-cache"))
    rows=[];failure=None
    with target.with_suffix(".stderr").open("w") as log:
        process=subprocess.Popen([str(args.binary),str(model),args.backend,args.cache,str(args.capacity),args.positioning],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True,bufsize=1,env=env)
        def exchange(op,**payload):
            process.stdin.write(json.dumps({"op":op,**payload},ensure_ascii=False)+"\n");process.stdin.flush()
            if not select.select([process.stdout],[],[],180)[0]:raise TimeoutError("Native fixture response exceeded 180 seconds")
            line=process.stdout.readline()
            if not line:raise RuntimeError("Native fixture worker ended")
            return json.loads(line)
        try:
            metadata=exchange("metadata")
            if "error" in metadata:raise RuntimeError(metadata["error"])
            target.with_suffix(".runtime.json").write_text(json.dumps(metadata,indent=2)+"\n")
            with target.open("x") as output:
                for case in cases:
                    user=json.dumps({"subject":case["subject"],"passages":case["passages"]},ensure_ascii=False)
                    counted=exchange("count",system=system,user=user)
                    if "error" in counted:raise RuntimeError(counted["error"])
                    if counted["count"]+160>args.capacity:raise RuntimeError("Frozen case does not fit without truncation")
                    started=time.monotonic();response=exchange("generate",system=system,user=user,maxTokens=128,samplingProfile=args.sampling)
                    row={"name":case["name"],"expectedKeep":case["expectedKeep"],"input_tokens":counted["count"],"response":response,"elapsed_ms":(time.monotonic()-started)*1000,"exact_selection":False}
                    try:
                        diagnostics=response["diagnostics"]
                        assert diagnostics["cache"]==args.cache and diagnostics["positioning"]==args.positioning and diagnostics["context"]==args.capacity
                        assert diagnostics["sampling_profile"]==args.sampling
                        value=json.loads(response["text"]);assert set(value)=={"keep"} and isinstance(value["keep"],list)
                        ids=value["keep"];assert all(type(n)==int and n in {p["id"] for p in case["passages"]} for n in ids) and len(ids)==len(set(ids))
                        row.update(actualKeep=ids,exact_selection=set(ids)==set(case["expectedKeep"]))
                    except (KeyError,ValueError,AssertionError,TypeError) as error:row["validation_error"]=str(error) or type(error).__name__
                    rows.append(row);output.write(json.dumps(row,ensure_ascii=False)+"\n");output.flush()
                    print(tag,case["name"],row["exact_selection"],flush=True)
        except Exception as error:failure=f"{type(error).__name__}: {error}"
        finally:
            process.stdin.close()
            try:process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
    summary={"complete":failure is None and len(rows)==len(cases) and process.returncode==0,"exact_selection_count":sum(r["exact_selection"] for r in rows),"case_count":len(cases),"completed_case_count":len(rows),"failure":failure,"process_returncode":process.returncode,"scope":"Frozen Context passage-selection checks; no general summary/physical-device qualification"}
    if baseline:
        improved=[r["name"] for r in rows if r["exact_selection"] and not baseline[r["name"]]["exact_selection"]]
        regressed=[r["name"] for r in rows if not r["exact_selection"] and baseline[r["name"]]["exact_selection"]]
        summary.update(improved=improved,regressed=regressed,quality_improvement_gate=summary["complete"] and bool(improved) and not regressed)
    target.with_suffix(".summary.json").write_text(json.dumps(summary,indent=2)+"\n");print(json.dumps(summary),flush=True)
    if not summary["complete"]:raise SystemExit(1)

if __name__=="__main__":main()
