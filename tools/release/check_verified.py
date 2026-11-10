#!/usr/bin/env python3
"""Fail closed: a release consumes one successful main push's exact APK.

Uses gh's existing credential; never accepts a PR artifact or arbitrary ref.
"""
import json
import os
import subprocess
import sys

REQUIRED = {
    'android.yml': {'Build and test', 'Device scenarios (emulator)'},
    'connectiq.yml': {'Watch files changed', 'Manifest and resource check', 'Compile the watch app', 'Connect IQ result'},
}


def require(ok, message):
    # Security checks must remain active even if Python optimisation is enabled.
    if not ok:
        raise AssertionError(message)


def validate(run, sha, jobs, names):
    require(run['head_sha'] == sha, 'Check belongs to another commit')
    require(run['head_branch'] == 'main' and run['event'] == 'push', 'Only main pushes can release')
    require(run['status'] == 'completed' and run['conclusion'] == 'success', 'Verification has not succeeded')
    require(run['head_repository']['full_name'].lower() == os.environ['GITHUB_REPOSITORY'].lower(), 'Foreign repository')
    by_name = {j['name']: j for j in jobs}
    require(names <= by_name.keys(), 'Required jobs missing')
    require(all(by_name[n]['conclusion'] == 'success' for n in names), 'Required job failed or skipped')


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True))


def main():
    repo = os.environ['GITHUB_REPOSITORY']
    android_id = int(sys.argv[1])
    android = api(f'repos/{repo}/actions/runs/{android_id}')
    sha = android['head_sha']
    require(api(f'repos/{repo}/git/ref/heads/main')['object']['sha'] == sha, 'Stale build: main has moved')
    for workflow, names in REQUIRED.items():
        runs = api(f'repos/{repo}/actions/workflows/{workflow}/runs?head_sha={sha}&event=push&per_page=100')['workflow_runs']
        require(runs, f'Missing {workflow} verification')
        newest = max(runs, key=lambda r: r['id'])
        run = api(f'repos/{repo}/actions/runs/{newest["id"]}')
        if workflow == 'android.yml':
            require(run['id'] == android_id, 'A newer Android run supersedes this one')
        # /attempts isolates reruns: old green jobs must not hide a failed rerun.
        jobs = api(f'repos/{repo}/actions/runs/{run["id"]}/attempts/{run["run_attempt"]}/jobs?per_page=100')['jobs']
        validate(run, sha, jobs, names)
    print(sha)


if __name__ == '__main__':
    main()
