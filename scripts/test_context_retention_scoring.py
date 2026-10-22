#!/usr/bin/env python3
"""Offline checks for omission denominators and the release comparison gate."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from score_context_retention import score

ROOT = Path(os.environ.get('ANTISOCIAL_BUILD_ROOT', '/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))

class RetentionScoringTest(unittest.TestCase):
    def setUp(self):
        parent = ROOT / 'quality-release/scoring-tests'; parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=parent)
        self.root = Path(self.temp.name)
    def tearDown(self):
        self.temp.cleanup()
    def run_record(self, name, complete=True, brief='The fee is 12 dollars.', useful=True, noise_calls=0):
        folder = self.root / name; folder.mkdir()
        fixture = [dict(name='case', split='test', expectedUseful=useful, expectedFacts=[dict(id='fee', allOf=['12'])] if useful else [])]
        (folder / 'pipeline-jobs-v1.json').write_text(json.dumps(fixture))
        stages = [dict(stage='initial', brief=brief, findings=[], newModelCalls=1, elapsedMs=10),
                  dict(stage='noise', brief=brief, findings=[], newModelCalls=noise_calls, elapsedMs=1)] if complete else []
        (folder / 'jobs.jsonl').write_text(json.dumps(dict(name='case', completed=complete, stages=stages))+'\n')
        return folder
    def compare(self, candidate, baseline):
        result = subprocess.run([sys.executable, str(Path(__file__).with_name('score_context_retention.py')), str(candidate), '--baseline', str(baseline)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)
    def test_failed_composition_does_not_remove_a_fact_from_the_denominator(self):
        baseline = self.run_record('baseline', complete=False)
        self.assertEqual(1, score(baseline)['totals']['expectedFacts'])
        self.assertEqual(0, score(baseline)['totals']['retainedFacts'])
        candidate = self.run_record('candidate')
        self.assertTrue(self.compare(candidate, baseline)['markerGatePassed'])
    def test_extra_model_work_on_noise_blocks_an_otherwise_improved_result(self):
        baseline = self.run_record('baseline', complete=False)
        candidate = self.run_record('candidate', noise_calls=1)
        self.assertFalse(self.compare(candidate, baseline)['markerGatePassed'])
    def test_promoted_noise_fails_the_gate_even_with_complete_jobs(self):
        baseline = self.run_record('baseline', brief='', useful=False)
        candidate = self.run_record('candidate', useful=False)
        result = self.compare(candidate, baseline)
        self.assertFalse(result['markerGatePassed']); self.assertEqual(1, result['totals']['noisePromotions'])
    def test_a_missing_fact_is_an_omission_even_when_other_prose_is_nonempty(self):
        candidate = self.run_record('candidate', brief='A fee is required.')
        result = score(candidate)
        self.assertEqual(1, result['totals']['expectedFacts']); self.assertEqual(0, result['totals']['retainedFacts'])

if __name__ == '__main__':
    unittest.main()
