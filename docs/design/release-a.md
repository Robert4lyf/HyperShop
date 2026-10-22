# Release A — verified and dependable

The goal: shopping data stays correct, important operations survive interruption,
and the tests catch regressions instead of producing green badges. No features.

## Baseline (main at 5343128, before this release)

| Finding | How it was established | Outcome |
|---|---|---|
| Android workflow green while the device job failed (`continue-on-error`) | Run 39631467809: `a_quickShop` timed out, overall "success" | Confirmed; fixed (A.1) |
| `a_quickShop` failure | Reproduced on CI (run 39731667082, a different step failed); not under Robolectric | Root cause: the test tapped the next row before the last tap had landed; fixed (A.1) |
| Start-up race: watch sync, UI, shares and import worker run while an interrupted restore is being recovered | Read `AppContainer.startUp()` | Confirmed; fixed (A.2) |
| Meals headline sums per-meal counts | Read `MealsUi.itemsLeft`; test with two meals sharing onions | Confirmed; fixed (A.4) |
| Finished trip, servings up then down, leaves a bought row on the list | Traced `rescaleUse`; regression test | Confirmed; fixed (A.4) |
| Undo of "Remove from list" after the shared row had gone | Found by the new invariant test (seed 5, step 44) | Confirmed (foreign-key crash); fixed (A.4) |
| `restore()` trusted its input; journal renames not flushed | Read `BackupManager`, `RestoreJournal` | Confirmed; fixed (A.3) |
| Garmin simulator job hangs | Two CI runs with new diagnostics | Cause found: an "Error" dialog under Xvfb; not yet fixed (KNOWN_ISSUES #12). Fonts installed in run 39738067898 did not clear it.|
| Watch sync after offline use, restore, duplicates | New acceptance tests | Already correct; now covered (A.5) |
| Main-thread database work | StrictMode during a 150-item shop session on the emulator | None found |

## What each part now guarantees, and its limits

**Start-up.** Nothing reads or writes the list before `AppContainer.startup` is `Ready`:
a restore the process died in is finished (or rolled back) first, then the watch epoch
is checked and the catalogue re-keyed. The watch coordinator holds incoming messages and
its pushes; shared links and the import worker wait; the UI shows `StartupScreen`. A
recovery that cannot finish stops on that screen and asks ("Try again" / "Keep my data
as it is"). *Limit:* new background entry points must call `awaitReady()` themselves
(CLAUDE.md says so).

**Backup and restore.** Validated before anything is written (`inspect()` and again in
`restore()`); journalled with both copies flushed, directory included, before the first
write; each stage replaces what it writes, so rolling forward twice is safe; a failure
part-way is rolled back; a failed rollback is left for the next start, which rolls
forward or stops and asks. Real process death mid-restore is tested on the emulator
(`ProcessDeathTest`). *Limits:* settings and ingredient edits made by the user *during*
the half-second a restore runs are overwritten by it, as if made just before it (the
database part is one transaction, so a tick lands wholly before or after). Cloud
providers are checked by hand only (Hand-off E).

**Shopping-list integrity.** `ListInvariantsTest` runs seeded random weeks (imports
sharing ingredients, ticks, finishes and their Undo, servings changes, removals and
their Undo, typed items) sequentially and with four racing writers, checking after each
step: every reference resolves; no live row with nothing on it; no two live rows share a
key; each recipe's count matches the rows it still needs; the meals headline only
counts rows the list needs; a row is done exactly when all of it is covered. *Limit:*
random runs find what they reach; the fixed tests cover the named edge cases.

**Meal and recipe counts.** One source: `ShoppingList.progress` per recipe and
`ShoppingList.rowsStillNeeded` for the Meals headline (each list row once).

**Watch synchronisation.** The phone is the authority. A wrist tick covers only what the
wrist had seen; a batch lands once however often it is sent; the wrist cannot undo a
phone purchase; ticks from before a restore are refused and the watch is sent the
restored list; nothing from the watch is handled before start-up is ready. *Limit:* the
watch's own Monkey C code is not executed in CI (the simulator is blocked by an error
dialog); a real Forerunner 245 is Hand-off E.

**Offline use.** Unchanged and local-first: the only network calls remain recipe pages
and Claude.

## Verification

See PROGRESS.md › Release A for the commands and results table, and
docs/reports/performance.md for the performance baseline and budgets.
