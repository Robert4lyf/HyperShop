# Final quality and reliability pass — report

Branch `claude/serene-lovelace-464dnp`, from main at d6802571 (Release C, PR #59).
Steps and checks: PROGRESS.md › Quality pass.

## 1. Baseline

Reviewed revision: **d6802571d34dc7fe6cb5aa1e6fa38d0997923d19**. Confirmed before any change:

- **Duplicate-line corrections (P0), reproduced.** `DuplicateLinesReviewTest` ran against d6802571's `ReviewViewModel`, and 9 of 10 cases failed:
  - An edit to the first "100 g sugar" landed on both lines after Ask Claude.
  - Removing one occurrence removed both.
  - A reorder from Claude produced three sugar lines.
  - Reopening the review forgot every correction, because the corrections lived only in memory.
- **The evaluation gated nothing (P0).** `ReleaseCEvalTest` ended with `assertTrue(all.isNotEmpty())`. Three representative parser mutations all passed it.
- **Release C's reported metrics verified exactly** on the 285-line set:
  - 251 right (88 %); name 88 %, quantity 284, unit 281, catalogue match 249 (87 %).
  - 19 of 258 accepted lines wrong.
- **main had no protection.** No ruleset or branch protection; `connectiq.yml` was path-filtered, so it could not be a required check; the simulator job ran with `continue-on-error`.

## 2. Ingredient correction reliability

Each review line has a **`LineOrigin`**: the source text it was read from (normalised for case, spacing, bullets and full stops) and which occurrence of that text it is. In the example, the cake's sugar is (`100 g sugar`, 0) and the icing's is (`100 g sugar`, 1). The identity never changes while the line is edited.

Corrections and removals are **`LineCorrection`s** filed under that origin. Both are saved with the job (`ImportResult.origins`, `.corrections`), so they survive Back, process death, and Cancel then Undo. No migration was needed: the job's JSON gained fields with defaults.

`rawLines`, which is what Claude reads, now stays as the recipe wrote it: an edit or a removal no longer rewrites it. A line Claude had split ("salt and pepper") is sent to Claude once, not once per part (`LineIdentity.sourceLines`).

**How `LineIdentity.reconcile` lines up Claude's answer:**

- Lines are matched by text and occurrence. Where both sides name the same sections, identical texts are paired within each section.
- A text Claude returns as many times as the recipe has it is paired one to one.
- A single line Claude split: a correction replaces the whole split, a removal drops it, and with neither, Claude's split stands.
- A repeated text Claude returned a different number of times, with a decision on any of them: those lines stay exactly as the review had them, and the untouched ones are marked "check".
- Lines matching nothing (a translation) are paired by position only when the counts agree and no removed line is among them. Otherwise, if any of them carries a decision, the review's lines stay (marked) and Claude's are not added beside them.
- A corrected line Claude left out is kept where it was. An untouched one Claude left out goes, as Claude's reading of the rest does.
- A line the user typed counts as their correction.

**When reconciliation happens:**

- After the answer arrives, against the review as it is then. A second Ask Claude after Stop joins the first call (`AppContainer.claudeParse`).
- An answer saved while the screen was gone carries the job's current edits and the lines it replaced (`ReviewLines`), and is reconciled when the review opens.

**Evidence:**

- `DuplicateLinesReviewTest` (12 cases, through the real view model, job store and repository):
  - Editing either occurrence, or both differently.
  - Removing either one, with Undo back in place.
  - A reorder, and reopening.
  - Cancel and its Undo.
  - A saved answer opened later.
  - Ask Claude again after Stop and an edit.
  - An edit after a purchase: 200 g bought stays bought, 50 g more needed.
  - The merged total on the list.
- `LineIdentityTest` (17 cases): extra, omitted, split, combined and translated lines, a removed line typed again, and sections.

## 3. Extraction regression protection

| | Release C set (285 lines) | Held-out set (137 lines, new) |
|---|---|---|
| Right (name, amount, unit): baseline → final | 251 → **259** (88 % → 91 %) | 107 → **114** (78 % → 83 %) |
| Name | 251 → 259 | 111 → 117 |
| Quantity | 284 → 284 | 133 → 134 |
| Unit | 281 → 281 | 134 → 135 |
| Catalogue match | 249 → 258 | 111 → 117 |
| Shown amount | 279 → 282 | 133 → 133 |
| Incorrectly accepted | 19 of 258 → **11 of 258** | 19 of 114 → **11 of 113** |
| Flag rate | 9 % → 9 % | 17 % → 18 % |

**The gates:**

- **`EvalGate`** holds every line to its recorded baseline. It records nine scored fields per line plus whether the line is flagged.
  - Fails on: a field lost, a flag raised or dropped, or a wrong line newly accepted.
  - Also fails on an improvement until the baseline is updated in the same change, so the ratchet never goes slack.
  - It records the labels' checksum. A label changed after the gate was set must be named in its SOURCES file under "Label corrections".
  - The rewrite switch (`HYPERSHOP_EVAL_UPDATE`) fails its own run, and `verify.sh` refuses to run with it set.
- **`EvalFloors`**: the totals in lines, held exactly.
- **`CriticalLinesTest`**: 61 lines pinned exactly through parse, catalogue item, aisle and shown amount in metric and imperial. They cover look-alike products (bicarbonate vs baking powder, self-raising vs plain, ground vs fresh ginger), tins, mince, tsp vs tbsp, ranges, US cups by weight, and every Q.3 fix. None of the plain lines may be flagged.
- **`ShelfAndSlashTest`**: what the Q.3 rules may and may not touch.

**Shown to fail on a representative regression.** Each mutation was made locally, run, and restored; none was committed. The old test passed all three.

| Mutation | Tests that failed |
|---|---|
| Every "tablespoon" word read as tsp | `CriticalLinesTest`, both gates, both floors |
| "baking soda" filed as baking powder | `CriticalLinesTest`, the Release C gate (rc-132) and floors |
| "400g tin" no longer a container | `CriticalLinesTest`, both gates (fq-078, rc-063), both floors |

**Independence:**

- The held-out set's 137 real lines come from 10 pages that appear in no other set. They were labelled by reading and committed (`[Q.2a]`) before any parser ran on them. No Claude call was made.
- Its errors then guided some Q.3 fixes, so only its first reading (78 %) is an independent figure (KNOWN_ISSUES #27).
- Two Release C labels were corrected and recorded in that set's SOURCES file, because they named a tin by the fresh vegetable's name. Both changes also raised the score, and the record says so.
- The frozen golden sets are untouched and still pass.

## 4. Ingredient accuracy improvements

| Line | Before | After |
|---|---|---|
| 2 (14.5-oz.) cans whole tomatoes | tomato, Fruit & veg | tinned tomatoes, Tins & jars |
| 800g / 28 oz canned crushed tomato | "canned", unmatched | tinned tomatoes, 800g |
| 400g can whole plum tomatoes | plum tomato, Fruit & veg | tinned tomatoes, 1 tin |
| Beef (minced, coarsely ground …) 10.6 oz | beef (joint) | beef mince, 310g / 10¾oz |
| 500g pack fresh egg lasagne sheets | dried lasagne sheets | fresh pasta, Chilled |
| 1.6kg braising steak | beef | braising steak |
| Parmigiano Reggiano PDO cheese 2.3 cups | unmatched, **580 ml** | parmesan, **230 g** |
| 2 dl flax seed/linseeds | unmatched, **200 ml** | linseed, **130 g** |
| 1 cup long- or short-grain brown rice* | unmatched, **250 ml** | brown rice, **190 g** |
| Remilled durum wheat semolina 2.1 cups | unmatched, **530 ml** | semolina, **360 g** |
| 1 cup roasted chickpeas | 240 ml | 1 cup (no density known: the recipe's unit) |
| Mascarpone cheese 2.2 lbs | unmatched, "1000g" | mascarpone, "1kg" |
| 1 recipe Italian Dressing | an item called "recipe" | italian dressing, flagged "made from another recipe" |
| small bunch thyme | no amount | 1 bunch |
| 3 beef bouillon cubes / 300g bacon lardons | unmatched | beef stock cubes / pancetta (as lardons already were) |

Kept apart on purpose:

- `4 whole tomatoes` stays fresh.
- `cream cheese` stays cream cheese.
- `2 garlic cloves, minced` stays garlic.
- Tinned salmon or peaches stay tins rather than becoming the fresh product.
- A slash between two products is flagged as a choice.

`ShoppingOutcomeTest` takes a UK recipe and a US recipe from the original line to the list row and the watch row, in metric and imperial, through a servings change, a purchase and the At home check:

- "3 tins chopped tomatoes"
- "960g beef mince" / "2¼lb"
- "210g parmesan"
- "460g more beef mince"

## 5. UX and accessibility

- **Automated:** none of the existing UI, semantics, contrast or device scenarios changed or failed.
  - One display improvement: an amount that rounds up to a kilogram or a litre is said in kilograms or litres.
  - One deliberate display change: a cup of something bought by weight or count with no known density keeps its cups instead of being shown in millilitres.
- **Physical devices:** nothing. No phone was reachable, so I made no UX change without a demonstrated problem.
  - The brief's questions are in Hand-off H as checks 4, 5, 12, 13 and 14: one-handed shop, Undo-only actions, largest font, daylight, Plan screen density.

## 6. Garmin

| Category | What |
|---|---|
| Kotlin model | the sync model and `WatchAcceptanceTest` ran in the unit suites, unchanged and passing; watch row text is the list's `Display.row`, covered by `ShoppingOutcomeTest` |
| Compiled in Monkey C | `tools/build_watch.sh` and `--test` in `verify.sh` locally; CI's "Compile the watch app" on this PR (the workflow change touches the watch's workflow) |
| Executed in a simulator | nothing; the headless simulator problem (KNOWN_ISSUES #12) was not retried without new evidence |
| Verified on hardware | nothing; Hand-off H checks 18–24 |

## 7. GitHub protection

**Not configured.** The ruleset (deletions and force pushes blocked, PRs required, `Build and test`, `Device scenarios (emulator)` and `Connect IQ result` required, admin bypass for PRs only) was written and its check names confirmed against PR #60's runs, but creating it was refused: the session's GitHub access is not permitted to write the rulesets API (HTTP 403). main is therefore still unprotected. docs/github-protection.md has the two-minute manual steps and `docs/github-ruleset.json` for `gh api --input`. What *was* changed: `connectiq.yml` now reports one `Connect IQ result` on every PR (so it can be required without blocking PRs that do not touch the watch; seen on this PR: changed-files, compile, resource check and result all green), and the simulator job no longer has `continue-on-error`.

## 8. Adversarial review

I reviewed my own diff, and two independent reviewers read it from the code, without my conclusions. Every confirmed finding is fixed, with a regression test.

**Line identity**

| # | Severity | Finding | Fix |
|---|---|---|---|
| 1 | High | A translated Ask Claude that could not be lined up put Claude's line beside the user's corrected one: 400 g of flour | unpairable lines with a decision keep the review's lines, marked |
| 2 | Medium | A removed line came back through a translation, and Undo then doubled it | same |
| 3 | Medium | The Claude call joined by a second Ask Claude after Stop was reconciled against the review from before the edit; an answer saved after the screen had gone overwrote edits made after Stop | reconciled after arrival, against the current review; the saved answer merges the job's current edits |
| 4 | Medium | An answer saved to the job was shown on reopening without the corrections over it, and could attach origins to the wrong lines | `ReviewLines` saved with it, reconciled on opening; fresh answers clear origins |
| 5 | Medium | A line Claude had split was sent to Claude once per part, then doubled | `sourceLines` |
| 6 | Low | A late answer after Cancel brought the import back | `finishJob` skips DISCARDING |

**Parser**

| # | Severity | Finding | Fix |
|---|---|---|---|
| 7 | High | A slash silently kept one of two products ("milk/dark chocolate" → milk; "salt/pepper" lost the pepper) | only two names for one catalogue entry collapse; anything else is a flagged choice |
| 8 | High | "canned"/"tinned" as qualifiers turned tinned salmon, peaches and crab into fresh ones | a tin the catalogue only knows fresh keeps the word |
| 9 | High | The tin rule read the whole line: "4 ripe tomatoes (or 400g tinned)" became 4 tins | only the name's own words and brackets count |
| 10 | Medium | The mince rule read the whole line: "lamb, tossed with ground cumin" became lamb mince | same |
| 11 | Low | The footnote filter dropped "%" and "&" | only `* † ‡` |

**Re-key**

| # | Severity | Finding | Fix |
|---|---|---|---|
| 12 | Medium | An existing `cat:beef` item named "stewing beef" would split from new braising steak | re-key moves a `cat:` item whose name the catalogue now files elsewhere; overrides stay on the old key |

**Gate**

| # | Severity | Finding | Fix |
|---|---|---|---|
| 13 | Medium | "Right" covered only name, amount and unit; a line could lose its aisle or shown amount unnoticed | per-line field signatures |
| 14 | Medium | Flag changes were not gated | the flag is part of the signature |
| 15 | Medium | Floors could go slack | exact floors |
| 16 | Low | The update switch silently passed every regression | it fails its own run, and `verify.sh` refuses it |
| 17 | Low | A hand-edited checksum skipped the SOURCES check | checked against the checksum at the gate, on every run |

**My own review**

| # | Severity | Finding | Fix |
|---|---|---|---|
| 18 | Low | 998 g read "1000g" | kg/l threshold after rounding |
| 19 | Low | "ripe tomatoes" kept a qualifier through a plural-aware lookup I had added | lookup reverted |

**Accepted costs and recorded leftovers:**

- "cooking salt / kosher salt" is now flagged as a choice. The held-out set has one line fewer right: 115 → 114.
- Claude's answers do not get the tin and mince readings; recorded as KNOWN_ISSUES #29.
- Two Release C labels were corrected in the same commit as the rules that made them pass; both are recorded in that set's SOURCES file.

## 9. Verification evidence

| Check | Where | Result |
|---|---|---|
| `tools/verify.sh` (ktlint, every JVM and Robolectric test in every module including the gates, migrations, backup, invariants, APK, androidTest compile, manifest, secrets, golden checksum, protocol parity, Monkey C compile and test compile) | container, on each commit Q.1, Q.2b, Q.3, Q.6 | green (Q.6: see the PR) |
| Core unit tests | container | 198 passed |
| Data unit tests | container | 127 passed |
| Android build, unit tests, device scenarios, Connect IQ | CI on PR #60 | see the PR's checks |
| Watch tests in a simulator | — | not run (KNOWN_ISSUES #12) |
| Real phone, real Forerunner 245 | — | not run (Hand-off H) |

## 10. Remaining limitations

- Nothing was done on hardware: Hand-off H (KNOWN_ISSUES #28).
- The held-out set's independence is spent (#27); the next parser change needs fresh lines.
- 11 accepted-but-wrong lines in each set (#25). Most are naming conventions; on the held-out set, two are presence items with no amount.
- "plus" amounts are not added (spec R3.6, #26).
- Claude's answers lack the Q.3 tin and mince readings (#29).

## 11. Independent assessment

| Area | Score | Why |
|---|---|---|
| User experience | 7.5 | the measured weekly path is 6 taps (Release B); corrections now behave as a user expects. Untested with real hands, TalkBack or daylight |
| Visual design | 7.5 | consistent identity, contrast tested on every palette; never judged on a real screen in this pass |
| Functionality | 8 | planning, merging, purchase integrity and watch sync are complete for a personal app; Claude's fallback path lags the rule parser on tins |
| Reliability | 8 | invariant suites, exact extraction gates, purchase-preserving edits and a reconciled review; minus the absence of any hardware run |
| Architecture | 8 | clear core/data/app split, pure domain logic, one transaction per write; `ReviewViewModel` (1,100 lines) carries a lot of state |
| Everyday usefulness | 7.5 | 83–91 % of real lines read right with none of the dangerous confusions tested, but about 1 in 10 accepted lines on unseen pages is still named differently from what a shopper would write |

## 12. Delivery

- Branch `claude/serene-lovelace-464dnp`, PR #60.
- Commits Q.1, Q.2a, Q.2b, Q.3, Q.4 (two commits), Q.5a and Q.6, plus this report.
- Docs: this report; PROGRESS.md; CLAUDE.md (one rule); HANDOFF.md (Hand-off H); KNOWN_ISSUES #25–29; github-protection.md and github-ruleset.json; the evaluation sets' SOURCES files.
- To do by you: apply the ruleset (docs/github-protection.md).
- Outstanding: Hand-off H's 24 checks.
