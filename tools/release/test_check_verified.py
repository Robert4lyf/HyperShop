import copy
import importlib.util
import os
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('gate', Path(__file__).with_name('check_verified.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        os.environ['GITHUB_REPOSITORY'] = 'Robert4lyf/HyperShop'
        self.run = dict(head_sha='abc', head_branch='main', event='push', status='completed', conclusion='success',
                        head_repository={'full_name': 'Robert4lyf/HyperShop'})
        self.jobs = [{'name': n, 'conclusion': 'success'} for n in gate.REQUIRED['android.yml']]

    def check(self, run=None, jobs=None):
        gate.validate(run or self.run, 'abc', self.jobs if jobs is None else jobs, gate.REQUIRED['android.yml'])

    def test_exact_success(self):
        self.check()

    def test_fail_closed(self):
        for field, values in {
            'head_sha': ['old'], 'head_branch': ['feature'], 'event': ['pull_request', 'workflow_dispatch'],
            'status': ['queued', 'in_progress'], 'conclusion': ['failure', 'cancelled', 'skipped', None],
            'head_repository': [{'full_name': 'someone/fork'}],
        }.items():
            for value in values:
                with self.subTest(field=field, value=value), self.assertRaises(AssertionError):
                    self.check(dict(self.run, **{field: value}))

    def test_required_job_missing_or_not_success(self):
        with self.assertRaises(AssertionError):
            self.check(jobs=[])
        for conclusion in ['failure', 'cancelled', 'skipped', None]:
            jobs = copy.deepcopy(self.jobs)
            jobs[0]['conclusion'] = conclusion
            with self.subTest(conclusion=conclusion), self.assertRaises(AssertionError):
                self.check(jobs=jobs)


if __name__ == '__main__':
    unittest.main()
