#!/usr/bin/env python3
"""Bounded native speculation controls, separate from complete Context qualification.

Use a frozen executable, explicit no-fit allocation and the same greedy chat
fixture for plain decoding and each depth/rollback combination. Every run has
external process memory and complete process wall-time observations. These host
observations cannot admit a phone route or choose a complete-Context winner.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
from check_local_ai_kv import BUILD, ROOT, SHA, digest

DRAFT_SHA = "4402be311018d01e31ecd0a894326e2f7bd28bc27a288c5b949799fe000808d8"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--depths", type=int, nargs="+", default=[1, 2, 4, 8, 12, 15])
    parser.add_argument("--rollback", nargs="+", choices=["recurrent", "checkpoint"], default=["recurrent", "checkpoint"])
    parser.add_argument("--memory-limit-mib", type=int, required=True, help="Declared process observation ceiling; not a measured phone budget")
    parser.add_argument("--batch", type=int, choices=[64, 1024], default=64)
    args = parser.parse_args()
    if not args.output.resolve().is_relative_to(BUILD.resolve()): parser.error("Keep evidence under LiberBuild")
    if not args.depths or any(d not in range(1, 16) for d in args.depths): parser.error("Depths must respect anchor + at most 15 proposals")
    if args.memory_limit_mib <= 0: parser.error("A positive process memory ceiling is required")
    args.output.mkdir(parents=True, exist_ok=False)
    target = BUILD/"ai-foundation/models/Ternary-Bonsai-2-4B-PQ2_0.gguf"
    draft = BUILD/"ai-foundation/models/drafters/Ternary-Bonsai-2-4B-DFlash2-BF16.gguf"
    if digest(target) != SHA["4B"] or digest(draft) != DRAFT_SHA: raise RuntimeError("Target/draft integrity failed")
    executable = args.output/"antisocial-kv-speculative-reference"
    shutil.copyfile(BUILD/"ai-foundation/native-build/host-cpu-antisocial-kv-v3/antisocial-kv-speculative-reference", executable)
    executable.chmod(0o755)
    fixture = args.output/"chat.json"
    fixture.write_text(json.dumps({
        "system": "Read the supplied evidence. State the correction and preserve its uncertainty. Return one short sentence.",
        "user": "Initial announcement: The repair kit costs $47 and ships on Monday. Later correction from the same maintainer: The kit costs $23, shipping is expected on Tuesday, and the date is not yet confirmed."
    })+"\n")
    identity = {"target_sha256": SHA["4B"], "draft_sha256": DRAFT_SHA, "binary_sha256": digest(executable), "fixture_sha256": digest(fixture),
        "context": 2048, "cache": "f16", "positioning": "native", "backend": "cpu", "depths": args.depths, "rollback": args.rollback,
        "memory_limit_mib": args.memory_limit_mib, "batch": args.batch, "ubatch": 32, "fit": False,
        "scope": "Host execution/memory/replay controls only. Not the real ContextEngine, a performance ranking or physical-phone qualification."}
    (args.output/"identity.json").write_text(json.dumps(identity, indent=2)+"\n")
    rows = []
    baseline = None
    for rollback, depth in [("recurrent", 0)] + [(r, d) for r in args.rollback for d in args.depths]:
        name = "plain" if depth == 0 else f"{rollback}-depth-{depth}"
        directory = args.output/name; directory.mkdir()
        env = os.environ.copy(); env.update(ANTISOCIAL_SPEC_CHAT_PATH=str(fixture), ANTISOCIAL_SPEC_METRICS_PATH=str(directory/"metrics.json"), ANTISOCIAL_SPEC_ROLLBACK=rollback)
        command = [str(executable), "-m", str(target), "-ngl", "0", "--fit", "off", "--spec-type", "none" if depth == 0 else "draft-dflash",
            "-c", "2048", "-b", str(args.batch), "-ub", "32", "-n", "32", "-t", "4", "-tb", "4", "--temp", "0", "--seed", "42", "--perf"]
        if depth: command += ["-md", str(draft), "-ngld", "0", "--spec-draft-n-max", str(depth), "--spec-draft-n-min", "0", "--spec-draft-p-min", "0"]
        print("Starting", name, flush=True)
        with (directory/"controller.log").open("x") as log:
            process = subprocess.Popen(["python3", str(ROOT/"scripts/measure_local_ai.py"), str(directory/"process"), *command], env=env, stdout=log, stderr=log, start_new_session=True)
            try: code = process.wait(timeout=600)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=10)
                code = -1
        row = {"case": name, "returncode": code, "phone_qualified": False}
        if code == 0:
            metrics = json.loads((directory/"metrics.json").read_text())
            observation = json.loads((directory/"process.measurement.json").read_text())
            if depth == 0: baseline = metrics["text"]
            row.update(text=metrics["text"], exact_greedy_replay=baseline is not None and metrics["text"] == baseline,
                elapsed_seconds=observation["elapsed_seconds"], max_rss_kib=observation["max_rss_kib"],
                observation_within_ceiling=max(observation["max_rss_kib"], observation["sampled_memory"]["max_pss_plus_drm_resident_kib"]) <= args.memory_limit_mib*1024,
                target_recurrent_rollback_slots=metrics["target_recurrent_rollback_slots"], verification_rounds=metrics["verification_rounds"], rollback_rounds=metrics["rollback_rounds"])
        rows.append(row)
        (args.output/"summary.json").write_text(json.dumps({"identity": identity, "complete": len(rows) == 1+len(args.rollback)*len(args.depths), "rows": rows}, indent=2)+"\n")
        print(json.dumps(row), flush=True)
        if depth == 0 and code != 0: raise RuntimeError("Plain control failed; no valid reference for speculation")

if __name__ == "__main__": main()
