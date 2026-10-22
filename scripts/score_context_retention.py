#!/usr/bin/env python3
"""Score predeclared fact markers at each real-engine stage.

Markers detect omissions, not truth or general semantic quality. Read the source,
selected evidence and brief before accepting a run. No model judges another model.
"""
import argparse
import json
from pathlib import Path
import re

def score(directory):
    directory = Path(directory)
    fixture = json.loads((directory / 'pipeline-jobs-v1.json').read_text())
    jobs = {row['name']: row for row in map(json.loads, (directory / 'jobs.jsonl').read_text().splitlines())}
    cases = []
    for case in fixture:
        row = jobs.get(case['name'])
        if row is None:
            continue
        stages = []
        for stage in row['stages']:
            selected = stage.get('selectedFindings')
            evidence = '\n'.join(finding['text'] for finding in stage['findings'])
            admitted = None if selected is None else '\n'.join(f['text'] for f in stage['findings'] if f['id'] in selected)
            def facts(text):
                return None if text is None else [fact['id'] for fact in case['expectedFacts'] if all(re.search(pattern, text, re.I) for pattern in fact['allOf'])]
            stages.append(dict(stage=stage['stage'], expectedFacts=len(case['expectedFacts']),
                               harvested=facts(evidence), admitted=facts(admitted), retained=facts(stage['brief']),
                               noisePromoted=not case['expectedUseful'] and bool(stage['brief'].strip()),
                               modelCalls=stage['newModelCalls'], elapsedMs=stage['elapsedMs'], brief=stage['brief']))
        stable = all(s['modelCalls'] == 0 for s in stages if s['stage'] in ('resume', 'noise', 'updated_resume', 'duplicate'))
        cases.append(dict(name=case['name'], split=case['split'], expectedFacts=len(case['expectedFacts']), completed=row['completed'], stableReuse=stable, stages=stages))
    first = [row['stages'][0] for row in cases if row['stages']]
    return dict(cases=cases, totals=dict(cases=len(cases), completed=sum(row['completed'] for row in cases),
                    expectedFacts=sum(row['expectedFacts'] for row in cases), retainedFacts=sum(len(s['retained']) for s in first),
                    noisePromotions=sum(s['noisePromoted'] for s in first), stableReuse=all(row['stableReuse'] for row in cases)),
                scope='Exact predeclared marker coverage on synthetic English engine inputs. Manual grounding and qualifications review required; not acquisition, phone or population accuracy.')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = score(args.run)
    if args.baseline:
        baseline = score(args.baseline)
        if (args.run / 'pipeline-jobs-v1.json').read_bytes() != (args.baseline / 'pipeline-jobs-v1.json').read_bytes():
            parser.error('Refusing to compare different frozen fixtures')
        result['baseline'] = baseline['totals']
        old = {row['name']: row for row in baseline['cases']}
        regressions = []
        for row in result['cases']:
            previous = old.get(row['name'])
            if not previous or not row['stages']:
                regressions.append(row['name']); continue
            before = previous['stages'][0] if previous['stages'] else {'retained': []}; after = row['stages'][0]
            if not set(before['retained']).issubset(after['retained']) or after['noisePromoted'] or not row['stableReuse'] or not row['completed']:
                regressions.append(row['name'])
        result['markerRegressions'] = regressions
        result['markerGatePassed'] = not regressions and result['totals']['retainedFacts'] > baseline['totals']['retainedFacts'] and len(old) == len(result['cases'])
    text = json.dumps(result, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_text(text)
        print(json.dumps({key: result[key] for key in ['totals', 'baseline', 'markerRegressions', 'markerGatePassed'] if key in result}))
    else:
        print(text, end='')

if __name__ == '__main__':
    main()
