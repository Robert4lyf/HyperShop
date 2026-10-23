# How main is to be protected

**Status: intended, not yet applied.** In the final quality pass (Q.5) the
session's GitHub access was refused write access to the rulesets API (HTTP 403
through its proxy), so main still has no protection. Apply it yourself in two
minutes, from the browser or from a terminal (below). The repository is public,
so rulesets are on the free plan. The workflow change it depends on
(`Connect IQ result` reporting on every PR) is merged with this pass, and all
three required checks were seen reporting on PR #60 before this was written.

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

## Applying it

**From a terminal**, with `gh` signed in as the repository's owner:

```bash
gh api -X POST repos/Robert4lyf/HyperShop/rulesets --input docs/github-ruleset.json
gh api repos/Robert4lyf/HyperShop/rulesets --jq '.[] | {id, name, enforcement}'   # check it is there
```

**In the browser:** Settings › Rules › Rulesets › New ruleset › New branch ruleset.

1. Name `main: PRs and green checks`; Enforcement status **Active**.
2. Bypass list: Add bypass › Repository admin, then set it to **For pull requests only**.
3. Target branches: Add target › Include default branch.
4. Tick **Restrict deletions** and **Block force pushes**.
5. Tick **Require a pull request before merging**; Required approvals **0**.
6. Tick **Require status checks to pass**; leave "Require branches to be up to date" unticked; Add checks:
   `Build and test`, `Device scenarios (emulator)`, `Connect IQ result` (source: GitHub Actions).
7. Create.

**Confirming it works:** open a PR whose CI is still running — the merge button
says the required checks have not passed; once all three are green it merges.
`git push origin main` from a clone is refused. Release APK still runs on the
merge commit (it is triggered by the push the merge makes, which the rules allow).

To switch it off in an emergency: the same page, Enforcement status › Disabled.

## Actions storage

Artifacts are kept for one day (three for the signed watch build), and only on
failure for the Android test reports; APKs are published as release assets,
which do not count against the Actions storage quota. Nothing here adds an
artifact.
