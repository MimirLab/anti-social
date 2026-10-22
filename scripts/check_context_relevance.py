#!/usr/bin/env python3
"""Controlled local relevance-screen experiments, not whole-engine qualification.

Models receive no tools. Freeze prompts, cases and the native binary before any
generation. A timed-out unnumbered worker is killed, never reused for later work.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import selectors
import shutil
import subprocess
import sys
import time
sys.dont_write_bytecode = True
from check_local_ai_kv import BUILD, ROOT, SHA, digest


def decode(response, recipe):
    def unique_members(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ValueError('Repeated decision field')
            value[key] = item
        return value
    value = json.loads(response, object_pairs_hook=unique_members)
    if not isinstance(value, dict) or set(value) != {recipe['key']}:
        raise ValueError('Unsupported decision fields')
    answer = value[recipe['key']]
    if type(answer) is not (bool if recipe['type'] == 'boolean' else str):
        raise ValueError('Wrong decision type')
    if answer not in recipe['keep'] + recipe['drop']:
        raise ValueError('Unknown decision')
    return answer in recipe['keep']


def combine_all(records, cases, recipes):
    """Score the declared conjunction, never each sub-question as usefulness."""
    names = {recipe['name'] for recipe in recipes}
    scored = []
    for case in cases:
        votes = [row for row in records if row['case'] == case['name']]
        if len(votes) != len(names) or {row['recipe'] for row in votes} != names:
            raise ValueError('Incomplete or repeated composite review')
        row = {'case':case['name'], 'split':case['split'], 'recipe':'all(' + ','.join(sorted(names)) + ')',
               'expectedKeep':case['expectedKeep']}
        if any(vote.get('invalid') for vote in votes):
            row['invalid'] = True
        else:
            if any(type(vote.get('keep')) is not bool for vote in votes):
                raise ValueError('Invalid composite decision type')
            row['keep'] = all(vote['keep'] for vote in votes)
            row['matched'] = row['keep'] == case['expectedKeep']
        scored.append(row)
    return scored


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', choices=['2B', '4B'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cases', type=Path, required=True)
    parser.add_argument('--recipes', type=Path, required=True)
    parser.add_argument('--recipe', nargs='+', help='Run only these declared recipes')
    parser.add_argument('--case', nargs='+', help='Run only these declared cases')
    parser.add_argument('--combine-all', action='store_true', help='Research only: score a conjunction of separate boolean duties; individual votes are not usefulness labels')
    args = parser.parse_args()
    out = args.output.resolve()
    if not out.is_relative_to(BUILD.resolve()): parser.error('Keep evidence under LiberBuild')
    if out.exists(): parser.error('Refusing to overwrite evidence')
    binary = BUILD / 'ai-foundation/native-build/host-vulkan-antisocial-kv-v3/antisocial-kv-quality-reference'
    weights = BUILD / f'ai-foundation/models/Ternary-Bonsai-2-{args.model}-PQ2_0.gguf'
    if digest(weights) != SHA[args.model]: raise RuntimeError('Model integrity failed')
    cases = json.loads(args.cases.read_text())
    recipes = json.loads(args.recipes.read_text())
    for entries in [cases, recipes]:
        if len({e['name'] for e in entries}) != len(entries): raise ValueError('Duplicate identities')
    selected = [e for e in cases if args.case is None or e['name'] in args.case]
    chosen = [e for e in recipes if args.recipe is None or e['name'] in args.recipe]
    if not selected or not chosen: parser.error('Empty selection')
    if args.case and set(args.case) != {e['name'] for e in selected}: parser.error('Unknown case')
    if args.recipe and set(args.recipe) != {e['name'] for e in chosen}: parser.error('Unknown recipe')
    if args.combine_all and (len(chosen) < 2 or any(e['type'] != 'boolean' for e in chosen)):
        parser.error('Composite review requires at least two boolean duties')
    for row in selected:
        assert type(row['expectedKeep']) is bool and row['split'] and row['subject'] and row['comment']
    out.mkdir(parents=True)
    shutil.copyfile(binary, out / 'native-runner'); (out / 'native-runner').chmod(0o755)
    shutil.copyfile(args.cases, out / 'cases.json'); shutil.copyfile(args.recipes, out / 'recipes.json')
    shutil.copyfile(__file__, out / 'runner.py')
    identity = {'model': args.model, 'model_sha256': SHA[args.model], 'binary_sha256': digest(out / 'native-runner'),
        'cases_sha256': digest(out / 'cases.json'), 'recipes_sha256': digest(out / 'recipes.json'),
        'runner_sha256': digest(out / 'runner.py'), 'case_names': [x['name'] for x in selected],
        'recipe_names': [x['name'] for x in chosen], 'capacity': 2048, 'response_tokens': 32,
        'combination': 'all' if args.combine_all else 'independent',
        'sampling': 'prism_non_thinking_reference_v1', 'backend': 'vulkan', 'cache': 'f16_reference',
        'instruction_cache_bytes': 64 * 1024 * 1024, 'deadline_seconds': 300,
        'scope': 'Synthetic isolated relevance decisions; no source extraction, translation, full pipeline or physical-phone admission'}
    (out / 'identity.json').write_text(json.dumps(identity, indent=2) + '\n')
    env = os.environ.copy()
    for key, directory in {'XDG_CACHE_HOME':'cache','MESA_SHADER_CACHE_DIR':'mesa-cache','__GL_SHADER_DISK_CACHE_PATH':'nvidia-cache'}.items():
        path = BUILD / 'inference-kv' / directory; path.mkdir(exist_ok=True); env[key] = str(path)
    stderr = (out / 'native.log').open('wb')
    process = subprocess.Popen([str(out / 'native-runner'), str(weights), 'vulkan', 'f16_reference', '2048', 'native', '2'],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr, env=env)
    selector = selectors.DefaultSelector(); selector.register(process.stdout, selectors.EVENT_READ)
    pending = bytearray(); usable = True
    def exchange(op, system='', user='', max_tokens=0):
        nonlocal usable
        if not usable: raise RuntimeError('Worker unavailable after transport failure')
        request = {'op':op, 'system':system, 'user':user, 'maxTokens':max_tokens,
            'instructionCacheBytes':identity['instruction_cache_bytes'] if system else 0, 'samplingProfile':identity['sampling']}
        try:
            process.stdin.write((json.dumps(request) + '\n').encode()); process.stdin.flush()
            deadline = time.monotonic() + identity['deadline_seconds']
            while b'\n' not in pending:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not selector.select(remaining): raise TimeoutError('Native exchange expired')
                block = os.read(process.stdout.fileno(), 65536)
                if not block: raise RuntimeError('Native worker ended')
                pending.extend(block)
                if len(pending) > 2 * 1024 * 1024: raise RuntimeError('Oversized native response')
            line, _, tail = pending.partition(b'\n'); pending.clear(); pending.extend(tail)
            response = json.loads(line)
            if 'error' in response: raise RuntimeError(response['error'])
            return response
        except BaseException:
            usable = False; process.kill(); process.wait(timeout=10); raise
    results = []
    try:
        metadata = exchange('metadata')
        (out / 'runtime.json').write_text(json.dumps(metadata, indent=2) + '\n')
        assert metadata['context'] == 2048 and metadata['threads'] == 2
        with (out / 'results.jsonl').open('x') as stream, (out / 'attempts.jsonl').open('x') as attempts:
            for row in selected:
                for recipe in chosen:
                    user = json.dumps({'subject':row['subject'], 'comment':row['comment']}, separators=(',', ':'), ensure_ascii=False)
                    counted = exchange('count', recipe['system'], user)['count']
                    assert counted + 32 + 32 <= 2048
                    attempt = {'case':row['name'], 'split':row['split'], 'recipe':recipe['name'], 'inputTokens':counted}
                    attempts.write(json.dumps(attempt) + '\n'); attempts.flush()
                    begin = time.monotonic()
                    response = exchange('generate', recipe['system'], user, 32)
                    diagnostics = response['diagnostics']
                    assert diagnostics['sampling_profile'] == identity['sampling']
                    record = {**attempt, 'expectedCompositeKeep' if args.combine_all else 'expectedKeep':row['expectedKeep'], 'response':response, 'elapsedSeconds':time.monotonic()-begin}
                    try:
                        if diagnostics['finish'] != 'end': raise ValueError('Decision did not finish')
                        record['keep'] = decode(response['text'], recipe)
                        if not args.combine_all: record['matched'] = record['keep'] == row['expectedKeep']
                    except (ValueError, TypeError): record['invalid'] = True
                    stream.write(json.dumps(record) + '\n'); stream.flush(); results.append(record)
                    verdict = 'invalid' if record.get('invalid') else str(record['keep'] if args.combine_all else record['matched'])
                    print(f"{args.model} {row['split']} {row['name']} {recipe['name']}: {verdict}", flush=True)
        scored = combine_all(results, selected, chosen) if args.combine_all else results
        if args.combine_all:
            (out / 'combined-results.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in scored))
        groups = {}
        for row in scored:
            key = row['recipe'] + '/' + row['split']
            group = groups.setdefault(key, {'cases':0,'matched':0,'falseNegative':0,'falsePositive':0,'invalid':0})
            group['cases'] += 1
            if row.get('invalid'): group['invalid'] += 1
            elif row['matched']: group['matched'] += 1
            elif row['expectedKeep']: group['falseNegative'] += 1
            else: group['falsePositive'] += 1
        (out / 'summary.json').write_text(json.dumps({'completed':True, 'groups':groups,
            'limit':'Predeclared synthetic outcomes require semantic review; counts do not qualify the whole engine or phone performance.'}, indent=2) + '\n')
    finally:
        process.stdin.close()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
        selector.close(); stderr.close()


if __name__ == '__main__': main()
