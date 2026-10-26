import json
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / 'audit_public_fixtures.py'
BUILD = Path(os.environ.get('ANTISOCIAL_BUILD_ROOT', '/run/media/red/RedtopStorage/DarwinBuild/LiberBuild'))


class PublicFixtureAuditTest(unittest.TestCase):
    def setUp(self):
        scratch = BUILD / 'python-test-tmp'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=scratch)
        self.base = Path(self.temp.name)
        self.root = self.base / 'checkout'
        self.root.mkdir()
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        self.rules = self.base / 'private-rules.json'
        self.rules.write_text(json.dumps(['Private Fixture Region']))
        self.report = self.base / 'private-report.json'

    def tearDown(self):
        self.temp.cleanup()

    def run_audit(self, *extra, rules=None, report=None):
        return subprocess.run([sys.executable, str(SCRIPT), '--root', str(self.root), '--denylist-file', str(rules or self.rules), '--report', str(report or self.report), *extra], capture_output=True, text=True)

    def test_literal_and_encoded_values_are_found_without_echoing_private_text(self):
        (self.root / 'fixture.kt').write_text('val area = "Private Fixture Region"\nval link = "PRIVATE%20FIXTURE%20REGION"\n')
        result = self.run_audit()
        self.assertEqual(1, result.returncode)
        self.assertEqual(2, json.loads(result.stdout)['findings'])
        details = json.loads(self.report.read_text())
        self.assertEqual([1, 2], [f['line'] for f in details['findings']])
        for text in [result.stdout, result.stderr, self.report.read_text()]:
            self.assertNotIn('Private Fixture Region', text)
            self.assertNotIn('PRIVATE%20FIXTURE%20REGION', text)

    def test_corresponding_source_zip_is_scanned_without_unpacking_binaries(self):
        archive = self.base / 'source.zip'
        with zipfile.ZipFile(archive, 'w') as out:
            out.writestr('App/tests/fixture.json', '{"area":"private fixture region"}')
            out.writestr('App/native.bin', b'\x00Private Fixture Region')
        result = self.run_audit('--source-zip', str(archive))
        self.assertEqual(1, result.returncode)
        self.assertEqual({'textFiles': 1, 'binaryFiles': 1, 'reviewedUnchangedFiles': 0, 'findings': 1}, json.loads(result.stdout))

    def test_private_rules_and_reports_cannot_be_written_in_the_checkout(self):
        inside = self.root / 'private.json'
        inside.write_bytes(self.rules.read_bytes())
        self.assertEqual(2, self.run_audit(rules=inside).returncode)
        self.assertEqual(2, self.run_audit(report=self.root / 'report.json').returncode)
        self.assertFalse((self.root / 'report.json').exists())

    def test_unrelated_geographic_example_does_not_fail_the_audit(self):
        self.rules.write_text(json.dumps(['Shared City, Private Region']))
        (self.root / 'fixture.kt').write_text('"Shared City, Unrelated Region"')
        result = self.run_audit()
        self.assertEqual(0, result.returncode)
        self.assertEqual(0, json.loads(result.stdout)['findings'])

    def test_empty_rules_are_rejected(self):
        self.rules.write_text(json.dumps(['  ']))
        self.assertEqual(2, self.run_audit().returncode)

    def test_reviewed_public_file_exemption_requires_exact_bytes_in_tree_and_zip(self):
        payload = b'Private Fixture Region in a reviewed upstream notice'
        (self.root / 'notice.txt').write_bytes(payload)
        manifest = self.base / 'reviewed.json'
        manifest.write_text(json.dumps({'notice.txt': hashlib.sha256(payload).hexdigest()}))
        result = self.run_audit('--reviewed-hashes', str(manifest))
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, json.loads(result.stdout)['reviewedUnchangedFiles'])
        archive = self.base / 'source.zip'
        with zipfile.ZipFile(archive, 'w') as out:
            out.writestr('App/notice.txt', payload)
        self.assertEqual(0, self.run_audit('--reviewed-hashes', str(manifest), '--source-zip', str(archive), '--zip-prefix', 'App/').returncode)
        (self.root / 'notice.txt').write_bytes(payload + b' changed')
        self.assertEqual(1, self.run_audit('--reviewed-hashes', str(manifest)).returncode)


if __name__ == '__main__':
    unittest.main()
