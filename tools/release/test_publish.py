"""Exercise staging/promoting with a fake gh executable: never calls GitHub."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FAKE = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['CALLS'], 'a') as f: f.write(json.dumps(args) + '\\n')
if args[0] == 'api':
    path = args[1]
    sha = os.environ['CHECK_SHA']
    if path.endswith('git/ref/heads/main'):
        counter = Path(os.environ['COUNTER'])
        n = int(counter.read_text()) + 1 if counter.exists() else 1
        counter.write_text(str(n))
        print(json.dumps({'object': {'sha': 'stale' if n > 1 and os.getenv('STALE_AFTER_UPLOAD') else sha}}))
    elif '/attempts/' in path:
        names = ['Build and test', 'Device scenarios (emulator)'] if '/runs/1/' in path else ['Watch files changed', 'Manifest and resource check', 'Compile the watch app', 'Connect IQ result']
        print(json.dumps({'jobs': [{'name': n, 'conclusion': 'success'} for n in names]}))
    else:
        run = dict(id=2 if 'connectiq' in path or path.endswith('/2') else 1, run_attempt=1,
                   head_sha=sha, head_branch='main', event='push', status='completed', conclusion='success',
                   head_repository={'full_name': os.environ['GITHUB_REPOSITORY']})
        print(json.dumps({'workflow_runs': [run]} if '/workflows/' in path else run))
elif args[:2] == ['release', 'view']:
    if os.getenv('PUBLISHED'):
        print('false')
    else:
        sys.exit(1)
elif args[:2] == ['release', 'upload'] and os.getenv('FAIL_UPLOAD'):
    sys.exit(1)
'''


class PublishTest(unittest.TestCase):
    def run_publish(self, **switches):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            guard = directory / 'tools/release'
            guard.mkdir(parents=True)
            shutil.copy(ROOT / 'tools/release/check_verified.py', guard)
            fake = directory / 'gh'
            fake.write_text(FAKE)
            fake.chmod(0o755)
            (directory / 'hypershop-debug.apk').write_bytes(b'the verified bytes')
            for args in [['init', '-q'], ['config', 'user.name', 'Test'], ['config', 'user.email', 'test@example.invalid'],
                         ['add', 'hypershop-debug.apk'], ['commit', '-qm', 'Test build']]:
                subprocess.run(['git', '-C', tmp, *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            sha = subprocess.check_output(['git', '-C', tmp, 'rev-parse', 'HEAD'], text=True).strip()
            calls = directory / 'calls.jsonl'
            env = dict(os.environ, PATH=tmp + os.pathsep + os.environ['PATH'], RUN_ID='1', EXPECTED_SHA=sha,
                       CHECK_SHA=sha, GITHUB_REPOSITORY='Robert4lyf/HyperShop', TAG='build-test', WATCH_READY='false',
                       CALLS=str(calls), COUNTER=str(directory / 'count'), **switches)
            result = subprocess.run(['bash', str(ROOT / 'tools/release/publish.sh')], cwd=tmp, env=env, capture_output=True)
            commands = [json.loads(line) for line in calls.read_text().splitlines()]
            self.assertEqual(b'the verified bytes', (directory / 'hypershop-debug.apk').read_bytes())
            return result.returncode, commands

    def test_complete_assets_are_uploaded_before_latest_changes(self):
        code, calls = self.run_publish()
        self.assertEqual(0, code)
        operations = [c[1] for c in calls if c[0] == 'release']
        self.assertLess(operations.index('create'), operations.index('upload'))
        self.assertLess(operations.index('upload'), operations.index('edit'))
        edit = next(c for c in calls if c[:2] == ['release', 'edit'])
        self.assertIn('--latest', edit)
        self.assertIn('--draft=false', edit)
        self.assertNotIn('delete', operations)

    def test_failed_upload_keeps_only_a_draft(self):
        code, calls = self.run_publish(FAIL_UPLOAD='1')
        self.assertNotEqual(0, code)
        self.assertFalse(any(c[:2] == ['release', 'edit'] for c in calls))
        self.assertIn('--draft', next(c for c in calls if c[:2] == ['release', 'create']))

    def test_main_moving_during_upload_cannot_promote_stale_bytes(self):
        code, calls = self.run_publish(STALE_AFTER_UPLOAD='1')
        self.assertNotEqual(0, code)
        self.assertTrue(any(c[:2] == ['release', 'upload'] for c in calls))
        self.assertFalse(any(c[:2] == ['release', 'edit'] for c in calls))

    def test_published_build_is_immutable_on_rerun(self):
        code, calls = self.run_publish(PUBLISHED='1')
        self.assertEqual(0, code)
        self.assertFalse(any(c[0] == 'release' and c[1] in ['upload', 'edit', 'delete', 'create'] for c in calls))


if __name__ == '__main__':
    unittest.main()
