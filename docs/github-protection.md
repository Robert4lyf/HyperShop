# How main is protected

Set up in the final quality pass (Q.5). The repository is public, so GitHub's
rulesets are available on the free plan; this is one ruleset, **"main: PRs and
green checks"**, on the default branch.

## The rules

| Rule | Setting | Why |
|---|---|---|
| Restrict deletions | on | main cannot be deleted by accident |
| Block force pushes | on | history on main is never rewritten |
| Require a pull request before merging | on, 0 approvals | every change goes through CI; a solo maintainer cannot approve their own PR, so a required approval would lock the repository |
| Require status checks to pass | on, not strict | see below; "up to date with main" is not required, so a merge does not cost a second full CI run |
| Required checks | `Build and test`, `Device scenarios (emulator)`, `Connect IQ result` | below |
| Bypass | repository admins, **for pull requests only** | the owner can merge a PR over a broken check knowingly (the merge box says so); a direct push to main is still refused |

## The required checks

All three run on every pull request, so none can leave a PR waiting for a check
that never reports.

- **Build and test** (`android.yml`): `tools/verify.sh` — ktlint, every unit and
  Robolectric test in every module (including the extraction gates), the debug
  APK, the merged-manifest and secrets checks, the golden checksum, the
  phone/watch protocol parity and the Monkey C argument limits.
- **Device scenarios (emulator)** (`android.yml`): the instrumentation tests on
  an API 34 emulator, process death mid-restore, the performance probe.
- **Connect IQ result** (`connectiq.yml`): passes when the PR touches no watch
  file, and otherwise only when the resource check and the watch compile
  (sources and unit tests) passed.

Not required, on purpose:

- **Build for the Forerunner 245** needs Garmin secrets, which a fork's PR does not
  have.
- **Run the watch tests in the simulator** is on request only and known not to
  work headless (KNOWN_ISSUES #12). It no longer has `continue-on-error`, so a run
  that fails shows red instead of green.
- **Release APK** runs after a merge, on main; it is not a PR check.

## Applying or changing it

With admin rights on the repository: Settings › Rules › Rulesets › "main: PRs and
green checks". Or from a terminal with `gh` authenticated as an admin:

```bash
gh api repos/Robert4lyf/HyperShop/rulesets --jq '.[] | {id, name, enforcement}'
gh api repos/Robert4lyf/HyperShop/rulesets/<id>      # the full rule set
```

To switch it off in an emergency, set its enforcement to "Disabled" on the same
page; nothing else changes.

## Actions storage

Artifacts are kept for one day (three for the signed watch build), and only on
failure for the Android test reports; APKs are published as release assets,
which do not count against the Actions storage quota. Nothing here adds an
artifact.
