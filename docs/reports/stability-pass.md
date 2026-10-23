# Final stability and data integrity pass (Step S) — report

Branch `claude/serene-lovelace-464dnp`, PR #62, from main at e523054a (PR #61). The
branch also carries R.7–R.9 (brighter heroes, recipe photos on every screen),
asked for separately just before this pass. Steps and checks: PROGRESS.md › Step S.

## 1. Baseline

Reviewed revision **e523054a9a389e1ad9c6610e6dfecfbd5d191cec**; main had not moved.
Each reported issue, and how it was confirmed:

| Issue | Confirmed? | How |
|---|---|---|
| Emulator scenario `b_returningWeeklyShopperItemByItem` fails | **Yes** | Failed again on this branch (run 39948603867, R.7) with the same 15 s timeout in the pick loop; passed on the next commit (R.8). Intermittent. |
| A chosen recipe photo is lost on restore to another phone | **Yes** | `Backup.kt` said so in a comment ("not the photos"); `PhotoBackupTest.anOlderJsonBackupStillRestores…` restores a plain JSON backup onto an empty photo folder: the chosen photo cannot come back. |
| Removing one recipe's photo deletes another recipe's | **Yes, by reading** | `RecipePhotos.replace` deleted the old file after the database write without asking whether another note named it; the files are content-named, so two recipes with one picture share a file. The new tests were not run against the old code (they use the new `sweep`). |
| Adjacent identical lines reach Claude once | **Yes, by test** | With the old adjacency rule put back, 8 of the new tests fail, among them `twoAdjacentIdenticalLinesBothReachClaudeAndBothAreBought` (Claude was sent "100 g sugar" once). |
| main unprotected | **Yes** | `GET /repos/Robert4lyf/HyperShop/branches/main` → `"protected": false`; no rulesets. |

## 2. Android CI correction (S.1, S.1b)

**Root cause.** Tapping "Plan" on a saved recipe inserts a meal card into the Plan screen's
`LazyColumn` *above* the picks. The card comes from Room through the screen's
`StateFlow` on `Dispatchers.Default`, which Compose's idling does not track, so it can land
after the tap's `waitForIdle()` has returned. The test then scrolled to the next pick; the
late card pushed that pick down and out of the composition, and the next tap waited 15 s
for a node that was no longer there. No application defect: the screen ends in the right
state every time; the test raced it. (R.9 had answered this with a re-scroll retry; S.1
replaced that with the cause.)

**Fix.** `planPick` waits, after each tap, for the plan in the database *and* the screen's
own count ("19–25 Oct · N meals · …") before the next scroll; the "Have it" rows wait for
the row to leave the screen. No timeouts were raised, no sleeps added. A timeout now says
what was awaited, the planner's meals, the summary and the tags on screen.

S.1's first CI run failed — and its diagnostics showed why in one line: the plan was in
place, but the wait looked for the summary to *start* with "1 meal". S.1b reads that part
of it. The emulator job then passed on 437be70 and on every run since (§8).

## 3. Photo backup (S.3, ADR 003)

A backup is now `hypershop-backup-<date>.zip`: `manifest.json` (first; each photo's name,
SHA-256 and size), `backup.json` (the plain backup byte for byte, so it takes the same
checks and version rules) and `photos/<name>` once per file.

- **Reading is hostile-input reading.** Only those three entry shapes, each once, manifest
  first; entry names are never used as paths. Bytes are counted as inflated, per entry
  (8 MB a photo) and in total (2 GB). A photo must match its manifest's size and hash, its
  name must be a prefix of that hash, and it must be a JPEG no larger than the app writes.
  Anything else refuses the whole file before anything changes.
- **Restore** stages photos in the cache at inspection, moves them into the photo folder when
  confirmed and *before* the restore journal is written, and takes a photo's name off a
  recipe whose file will not be there (keeping a page's address, to fetch again). The
  database never names a missing file. Backup, restore and recovery hold the photo lock.
- **Compatibility.** Plain JSON backups still restore; their photos count as present only if
  the same file is on the phone, and the prompt says how many will be missing. An older
  HyperShop cannot read the new file (accepted: one user, updated in step).
- **Truthfulness.** "Backed up …" counts the photos, and says how many were not on the phone
  to include; the restore prompt says how many will be missing.

Fresh-install proof: `aChosenPhotoMovesToAnotherPhoneWithItsRatingAndNotes` (byte-identical
photo, rating and notes on an empty install) and, through a real provider on the
emulator, `DeviceBackupProviderTest.aChosenPhotoTravelsThroughAProvider`.

## 4. Shared media (S.2, S.5b)

Photos stay content-addressed and immutable. The database owns them, and **only the
start-up sweep deletes a file**: after any interrupted restore is resolved, before the app
is Ready, it removes what no recipe names and any `.part`. Deleting at the moment a photo
was replaced looked tidier and was unsafe three ways — the file could be another recipe's
(the reported defect), the other side of a restore left for the next start, or what a
restore's Undo would put back (both found in review). `store` flushes and renames whole and
reports a failed rename; every photo change and the sweep run under one lock, which backup
and restore hold too. Storage is bounded: anything unnamed goes at the next start.

Tests: `PhotoOwnershipTest` (shared remove and replace, last reference swept, 12 concurrent
changes, a write never recorded, no sweep during a paused restore) and the restore tests
below.

## 5. Ingredient-line identity (S.4)

Lines now carry where they came from, `ParsedIngredient.sourceLine`, kept in the stored
JSON (no migration). The rule parser numbers each line (it never splits one); Claude's
reading is numbered against the lines it was sent (`LineIdentity.attach`: by text, one
source occurrence per ingredient; within a run of one text, a name not yet seen is a further
part of the same line, a repeated name the next occurrence); a saved recipe is renumbered
with the text it is sent as (`reread`); a typed line gets the next number. `sourceLines`
groups by number, so two "100 g sugar" lines are two and a split "salt and pepper" is one.
Lines stored before numbering fall back to grouping only neighbours that share their text
*and* name different ingredients. Section headings now go to Claude before their lines.
`LineOrigin`, corrections and `reconcile` are unchanged: this decides only what Claude is
sent.

Tests through the Recipes and review view models, the job store and the repository
(`SourceLinesReviewTest`): adjacent both sent and both bought (200 g on the list); a split
sent once; two identical split lines once each; sections; an edit to one adjacent occurrence
(250 g on the list); a removal Claude's answer cannot undo (100 g); Undo restoring the edited
occurrence; a recipe stored before numbering. Plus `LineIdentityTest` for `attach` (split,
duplicate, reorder, combine, rephrased) and `reread`.

## 6. GitHub protection

**Not applied.** The session's GitHub access is refused write access to the rulesets API
(`POST /repos/…/rulesets` → HTTP 403, "Write access to this GitHub API path is not
permitted through this proxy"); nothing was worked around. The three required check
names in `docs/github-ruleset.json` — `Build and test`, `Device scenarios (emulator)`,
`Connect IQ result` — were checked against PR #62's check runs and all report on every PR.
The steps (browser or `gh api`) are in `docs/github-protection.md`.

## 7. Adversarial review

An independent reviewer read the S.2–S.4 diff without these conclusions; each finding was
then reproduced and fixed with a test (S.5b):

| # | Severity | Defect | Fix | Test |
|---|---|---|---|---|
| 1 | High | An entry name that is not valid UTF-8 threw `IllegalArgumentException` past every catch: inspecting the file crashed the app | Caught as a damaged archive | `anEntryNameThatIsNotTextIsADamagedFileNotACrash` |
| 2 | Medium | One damaged photo on the phone (written before S.2's flushed writes) went into the archive and made the whole backup unrestorable, after "Backed up" | Each photo checked before it goes in; a bad one counted missing and said | `aDamagedPhotoOnThePhoneIsLeftOut…` |
| 3 | Low | After a restore whose rollback failed too, replacing a photo deleted a file the journal's side still named | Deletion only at the start-up sweep | `aRestoreLeftForTheNextStartKeepsBothSidesPhotos…` |
| 4 | Low | Replacing a photo before Undo of a restore deleted the file Undo needed | Same | `undoAfterAPhotoChangedSinceTheRestore…` |
| 5 | Low | A damaged same-named file on the phone was kept instead of the archive's copy | Replaced when not intact | `aDamagedCopyAlreadyOnThePhoneIsReplaced…` |
| 6 | Low | An archive could bring in photos no recipe names; staging outlived process death | Only named photos adopted; staging cleared at start-up | `aPhotoNoRecipeNamesIsNotBroughtIn` |
| 7 | Low | A line typed in review follows the last section heading in the text Claude reads; a rules-only re-read would put it in that section | Left: KNOWN_ISSUES #30 | — |

My own passes: no deadlock (always `BackupManager.lock` then the photo lock); recovery runs
before the sweep and a stuck recovery skips it; old stored lines and old jobs decode
(`sourceLine` defaults to null); no Room migration needed (the new query uses existing
columns). Test infrastructure: the new device waits are conditions, not sleeps; the only
sleeps added are the screenshot tests' wait for a photo to decode and a 200 ms negative
check that a sweep is held by a paused restore. Artifacts stay at one-day retention, on
failure only.

## 8. Verification

| Check | Command | Result |
|---|---|---|
| The gate | `tools/verify.sh` (ktlint, every JVM/Robolectric test, eval gates, debug APK, manifest and secret checks, golden checksum, protocol parity, Monkey C limits, watch compile) | Passed on S.3, S.4 and S.5b; 769 tests, 0 failed, 2 skipped (the run-on-request simulator tests) |
| Changed areas | `:app:testDebugUnitTest` for the photo, backup, line-identity and review classes | Passed |
| Old rule put back | S.4's tests against the adjacency rule | 8 fail, as they should |
| CI, PR #62 | Build and test; Device scenarios (emulator); Connect IQ result | Green on 437be70 (run 39955448318); see below for 7e2f749 |
| Repeated device suite | `workflow_dispatch` with `device_repeats` = 8 on 7e2f749 | running when this was written; result recorded in PR #62 |

Not run: anything on a real phone or Forerunner 245 (none reachable). Room migration tests
and Garmin protocol parity run inside the gate; the watch's own unit tests compile in CI
but need the simulator (KNOWN_ISSUES #12).

## 9. Remaining issues

- Main is unprotected until the ruleset is applied by hand (§6).
- KNOWN_ISSUES #30 (a typed line and the last section on a rules-only re-read), low.
- On a phone (docs/HANDOFF.md checks 25–28): a chosen photo through Google Drive onto a
  second install; restore over photos then Undo; one picture on two recipes; two identical
  lines side by side through Ask Claude. Hand-off H is still open.

## 10. Delivery

Branch `claude/serene-lovelace-464dnp`, PR #62. Commits S.1, S.1b, S.2, S.3, S.4, S.5a,
S.5b (and this report). Documentation: this report, ADR 003, HANDOFF (25–28),
KNOWN_ISSUES (#30, #31), `docs/github-protection.md`, PROGRESS.md › Step S.
