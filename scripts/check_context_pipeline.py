#!/usr/bin/env python3
"""Freeze and run real ContextEngine jobs with native or the authorized research provider.

The model output still requires semantic review. This measures a host JVM/native
process tree, not Android memory, energy, collection or translation performance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
sys.dont_write_bytecode=True
from check_local_ai_kv import BUILD, ROOT, SHA, digest

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model",choices=[*SHA,"server"],required=True)
    parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--backend",choices=["cpu","vulkan"],default="cpu")
    parser.add_argument("--cache",default="f16_reference")
    parser.add_argument("--capacity",type=int,choices=[2048,8192],default=2048)
    parser.add_argument("--position",choices=["native","yarn2"],default="native")
    parser.add_argument("--threads",type=int,choices=[1,2,4],default=2)
    parser.add_argument("--instruction-cache-mib",type=int,choices=[0,32,64,128],default=64)
    parser.add_argument("--sampling",choices=["prism_non_thinking_reference_v1","context_greedy_v1"],default="prism_non_thinking_reference_v1")
    parser.add_argument("--style",choices=["auto","focused","balanced","extended"],default="auto")
    parser.add_argument("--summary-group-limit",type=int,choices=[0,4,8,16],default=0,help="Development scope comparison; zero keeps the production recipe")
    parser.add_argument("--output-tokens",type=int,default=0,help="Independent response-allowance control; zero keeps production, actual runtime limits still apply")
    parser.add_argument("--reader-characters",type=int,default=0,help="Experimental source-selection display allowance; zero retains the model-coupled control")
    parser.add_argument("--composition",choices=["baseline","publication","source_selection","source_atomic"],default="baseline",help="Publication adds the app's exact-copy reuse policy; experimental selection/atomic composition requires exact-source findings")
    parser.add_argument("--observation-scope",choices=["enabled","disabled","data_only"],default="data_only",help="Audit ablation: enabled also adds experimental narrative guidance; production uses source data only")
    parser.add_argument("--exchange-timeout-seconds",type=int,default=300,help="Native transport deadline; a timeout terminates the worker, never reuses a pending response")
    parser.add_argument("--cases",nargs="+",choices=["lift_uncertainty","battery_events","workshop_thread","library_thread","publication_context","publication_many","workshop_scope","reply_context","reply_reports","reply_out_of_order","multipart_praise_then_correction","multipart_condition_in_next_paragraph","multipart_firsthand_limit_in_next_paragraph","multipart_injection_beside_observation","multipart_commercial_detail_beside_bluster","multipart_inspection_is_not_reopening","multipart_dependent_explanation","multipart_multipart_empty_reaction","long_multipart_praise_then_correction","long_multipart_condition_in_next_paragraph","long_multipart_firsthand_limit_in_next_paragraph","long_multipart_injection_beside_observation","long_multipart_commercial_detail_beside_bluster","long_multipart_inspection_is_not_reopening","long_multipart_dependent_explanation","long_multipart_multipart_empty_reaction","fresh_clinic_hours","fresh_release_condition","fresh_warranty_scope","fresh_rude_measurement","fresh_witness_limit","fresh_empty_reaction","fresh_security_quote","fresh_condition_first","linked_security_report","linked_oven_calibration","linked_train_log","linked_pharmacy_witness","linked_condition_after_noise","linked_refund_terms","linked_empty_deictic","linked_offtopic_numbers","linked_direct_command_and_fact","linked_rude_correction","linked_certificate_scope","linked_unconfirmed_report","linked_bare_question","linked_commercial_scope","linked_noise_between_facts","linked_limited_trial","role_battery_observation","role_firsthand_praise","role_firsthand_offtopic","role_locked_entrance","role_conditional_permission","role_bare_instruction","role_distinct_values","role_commercial_detail","qual_limited_stock","qual_offtopic_condition","qual_conditional_praise","qual_conditional_attack","qual_quoted_attack_report","qual_uncertain_witness","qual_attributed_rumor","qual_empty_insult","qual_scope_terms","qual_offtopic_caveat","qual_decorated_opinion","qual_human_directions","qual_unsupported_scope_claim","qual_unrelated_practical_advice","qual_promotion_condition","qual_related_safety"],default=["lift_uncertainty","battery_events"])
    parser.add_argument("--comment-screen",choices=["production","disabled"],default="production",help="Audit only: compare the existing passage selector without the whole-comment boolean/short-comment route")
    parser.add_argument("--post-background",choices=["legacy","typed"],default="legacy",help="Audit only: supply the declared subject post to every comment using the reader's typed frame; legacy preserves earlier experiments")
    parser.add_argument("--passage-review",choices=["selector","binary_parent","binary_neighbors","binary_full","binary_linked","role_linked","selector_linked","qualified_linked"],default="selector",help="Research adapters: narrow passage duties or one selection over app-linked groups; app owns original IDs")
    parser.add_argument("--passage-closure",action="store_true",help="Research only: conservatively retain linked neighboring passages")
    parser.add_argument("--code-snapshot",type=Path,help="Reuse a verified frozen audit classpath and fixture for a controlled model comparison")
    parser.add_argument("--prepare-only",action="store_true",help="Freeze verified audit inputs without starting a model; a later run can use them as a code snapshot")
    args=parser.parse_args()
    if not 1<=args.exchange_timeout_seconds<=900:parser.error("Native exchange deadline must be between 1 and 900 seconds")
    if not 0<=args.output_tokens<=8192:parser.error("Output control must be between zero and 8192")
    if args.reader_characters and (args.composition!="source_selection" or not 256<=args.reader_characters<=32768):parser.error("A separate 256..32768 character reading allowance requires source_selection")
    if args.model!="server" and args.style!="auto":parser.error("Phone profiles keep the production focused recipe")
    if args.passage_review!="selector" and args.comment_screen!="disabled":parser.error("Binary passage audit requires the disabled whole-comment screen control")
    if args.passage_closure and args.passage_review=="selector":parser.error("Passage closure requires binary passage review")
    if args.passage_closure and args.passage_review in ["binary_linked","role_linked","selector_linked","qualified_linked"]:parser.error("Linked groups already determine retention before classification; do not add post-vote closure")
    out=args.output.resolve()
    if not out.is_relative_to(BUILD.resolve()):parser.error("Keep evidence under LiberBuild")
    if out.exists():parser.error("Refusing to overwrite evidence")
    binary=BUILD/f"ai-foundation/native-build/host-{args.backend}-antisocial-kv-v3/antisocial-kv-quality-reference"
    weights=BUILD/f"ai-foundation/models/Ternary-Bonsai-2-{args.model}-PQ2_0.gguf"
    if args.model!="server":
        if digest(weights)!=SHA[args.model]:raise RuntimeError("Model integrity failed")
        if not binary.is_file():raise RuntimeError("Build the selected native runner first")
    snapshot=None
    if args.code_snapshot:
        source=args.code_snapshot.resolve()
        if not source.is_relative_to(BUILD.resolve()):parser.error("Keep code snapshots under LiberBuild")
        recorded=json.loads((source/"identity.json").read_text())
        if recorded.get("schema")!="antisocial-context-pipeline-v4":parser.error("Code snapshot lacks the v4 observation-scope protocol; use its archived runner for older snapshots")
        if args.observation_scope not in recorded.get("observation_scope_modes",["enabled","disabled"]):parser.error("Requested observation experiment is unsupported by the frozen code")
        if args.reader_characters and not recorded.get("reader_budget_control",False):parser.error("Frozen code does not implement the independent reading allowance")
        if args.comment_screen!="production" and not recorded.get("comment_screen_control",False):parser.error("Frozen code lacks comment-screen control")
        if args.post_background not in recorded.get("post_background_modes",["legacy"]):parser.error("Frozen code lacks the requested post-background control")
        if args.passage_review not in recorded.get("passage_review_modes",["selector"]):parser.error("Frozen code lacks the requested passage review adapter")
        if args.passage_closure and not recorded.get("passage_closure_control",False):parser.error("Frozen code lacks passage closure")
        inputs=recorded["classpath"]
        for entry in inputs:
            path=Path(entry["file"]).resolve()
            if not path.is_relative_to(source) or digest(path)!=entry["sha256"]:parser.error("Frozen classpath integrity failed")
        if digest(source/"pipeline-jobs-v1.json")!=recorded["fixture_sha256"]:parser.error("Frozen fixture integrity failed")
        if args.model!="server" and digest(binary)!=recorded.get("binary_sha256"):parser.error("Native code changed; use the intended matching snapshot")
        out.mkdir();(out/"classpath").mkdir()
        files=[]
        for index,entry in enumerate(inputs):
            destination=out/"classpath"/f"{index}-{Path(entry['file']).name}"
            shutil.copyfile(entry["file"],destination)
            if digest(destination)!=entry["sha256"]:raise RuntimeError("Copied classpath integrity failed")
            files.append(str(destination))
        (out/"classpath.txt").write_text(os.pathsep.join(files))
        shutil.copyfile(source/"java.txt",out/"java.txt")
        snapshot={"source":str(source),"identity_sha256":digest(source/"identity.json")}
    else:
        result=subprocess.run([str(ROOT/"scripts/gradle.sh"),":core:prepareContextPipelineAudit",f"-PantisocialPipelineOutput={out}"],cwd=ROOT)
        result.check_returncode()
    fixture=out/"pipeline-jobs-v1.json"
    shutil.copyfile(args.code_snapshot.resolve()/"pipeline-jobs-v1.json" if args.code_snapshot else ROOT/"core/src/test/resources/context-evaluation/pipeline-jobs-v1.json",fixture)
    identity={"schema":"antisocial-context-pipeline-v4","model":args.model,"backend":args.backend,"cache":args.cache,"capacity":args.capacity,"position":args.position,"observation_scope":args.observation_scope,"comment_screen":args.comment_screen,"post_background":args.post_background,"passage_review":args.passage_review,"passage_closure":args.passage_closure,"reader_characters":args.reader_characters,
        "threads":args.threads,"instruction_cache_bytes":args.instruction_cache_mib*1024*1024,"sampling":args.sampling,"style":args.style,"summary_group_limit":args.summary_group_limit,"output_tokens":args.output_tokens,"composition":args.composition,"exchange_timeout_seconds":args.exchange_timeout_seconds,"cases":args.cases,"fixture_sha256":digest(fixture),
        "classpath":[{"file":str(p),"sha256":digest(p)} for p in map(Path,(out/"classpath.txt").read_text().split(os.pathsep))],
        "scope":"Frozen complete ContextEngine fixture stages, including model initialization and process teardown; disk page cache may be warm after integrity verification. No Android or semantic admission."}
    frozen=out/"native-runner"
    identity["observation_scope_modes"]=recorded.get("observation_scope_modes",["enabled","disabled"]) if snapshot else ["enabled","disabled","data_only"]
    identity["comment_screen_control"]=recorded.get("comment_screen_control",False) if snapshot else True
    identity["post_background_modes"]=recorded.get("post_background_modes",["legacy"]) if snapshot else ["legacy","typed"]
    identity["passage_review_modes"]=recorded.get("passage_review_modes",["selector"]) if snapshot else ["selector","binary_parent","binary_neighbors","binary_full","binary_linked","role_linked","selector_linked","qualified_linked"]
    identity["passage_closure_control"]=recorded.get("passage_closure_control",False) if snapshot else True
    identity["reader_budget_control"]=recorded.get("reader_budget_control",False) if snapshot else True
    if snapshot is not None:identity["code_snapshot"]=snapshot
    if args.model!="server":
        shutil.copyfile(binary,frozen);frozen.chmod(0o755)
        identity.update(binary_sha256=digest(frozen),model_sha256=SHA[args.model])
    key=hashlib.sha256(json.dumps(identity,sort_keys=True).encode()).hexdigest()
    (out/"identity.json").write_text(json.dumps(identity,indent=2)+"\n")
    command=[(out/"java.txt").read_text(),"-Xmx512m",f"-DantisocialBuildRoot={BUILD}",f"-Dantisocial.pipeline.output={out}",f"-Dantisocial.pipeline.model={args.model}",
        f"-Dantisocial.pipeline.binary={frozen}",f"-Dantisocial.pipeline.weights={weights}",f"-Dantisocial.pipeline.backend={args.backend}",f"-Dantisocial.pipeline.cache={args.cache}",
        f"-Dantisocial.pipeline.capacity={args.capacity}",f"-Dantisocial.pipeline.position={args.position}",f"-Dantisocial.pipeline.threads={args.threads}",
        f"-Dantisocial.pipeline.sampling={args.sampling}",
        f"-Dantisocial.pipeline.style={args.style.upper()}",
        f"-Dantisocial.pipeline.summaryGroupLimit={args.summary_group_limit}",
        f"-Dantisocial.pipeline.outputTokens={args.output_tokens}",
        f"-Dantisocial.pipeline.readerCharacters={args.reader_characters}",
        f"-Dantisocial.pipeline.composition={args.composition}",
        f"-Dantisocial.pipeline.observationScope={args.observation_scope}",f"-Dantisocial.pipeline.commentScreen={args.comment_screen}",f"-Dantisocial.pipeline.passageReview={args.passage_review}",f"-Dantisocial.pipeline.passageClosure={str(args.passage_closure).lower()}",
        f"-Dantisocial.pipeline.postBackground={args.post_background}",
        f"-Dantisocial.pipeline.exchangeTimeoutSeconds={args.exchange_timeout_seconds}",
        f"-Dantisocial.pipeline.instructionBytes={identity['instruction_cache_bytes']}",f"-Dantisocial.pipeline.identity={key}",f"-Dantisocial.pipeline.cases={','.join(args.cases)}",
        "-cp",(out/"classpath.txt").read_text(),"app.antisocial.core.ContextPipelineAuditKt"]
    (out/"command.json").write_text(json.dumps(command,indent=2)+"\n")
    if args.prepare_only:
        (out/"summary.json").write_text(json.dumps({"prepared":True,"complete":False,"nativeStarted":False,"identity":key},indent=2)+"\n")
        print("Prepared frozen audit inputs; no model was started.")
        return
    env=os.environ.copy();env["ANTISOCIAL_MEASURE_PROCESS_TREE"]="true"
    process=subprocess.Popen(["python3",str(ROOT/"scripts/measure_local_ai.py"),str(out/"process"),*command],env=env,cwd=ROOT,start_new_session=True)
    try: code=process.wait(timeout=7200)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid,signal.SIGTERM)
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=10)
        raise
    rows=[json.loads(line) for line in (out/"jobs.jsonl").read_text().splitlines()] if (out/"jobs.jsonl").exists() else []
    complete=code==0 and len(rows)==len(args.cases) and all(r["completed"] for r in rows)
    (out/"summary.json").write_text(json.dumps({"complete":complete,"returncode":code,"jobs":len(rows),"criteria":"Manual semantic review required; completion alone does not qualify output","identity":key},indent=2)+"\n")
    raise SystemExit(0 if complete else 1)

if __name__=="__main__":main()
