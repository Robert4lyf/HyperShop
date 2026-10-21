# Simplify (2026-10): plan and risk assessment

Audit of `main` at `942912a` before this pass. What exists, what changes, and
what could go wrong. One commit per phase; `tools/verify.sh` before each.

## What the audit found

Already in place (not redone):

- Three list layouts (store order, by type, by recipe) in the list menu; quiet
  ticks (tap again is the undo); a 56 dp tick target separate from the row's
  detail tap; "Finish shopping" with Undo; Clear all asks first.
- Recipes tab with "On the list / Library" toggle, saved-recipe edit, rename,
  search with chips, cookbook book/page, photo import (Claude).
- Review screen: staged loading, "paste the lines" after 25 s, edits kept with
  the job, Cancel with Undo, "List changed — review updated".
- Imports survive process death (`ImportJob` rows); shares queue one review at
  a time; Claude only while the review is in the foreground; spend guard.
- Watch sync with go-back-N, epochs, aliases; every vector replayed end to end.
- Backup: Android Auto Backup only. No export/import.
- Eval (docs/reports/eval-claude.md): the rule parser scores 96–100 % on name
  and 100 % on quantity on the golden lines; Haiku 94–100 % / 92–96 %. Claude
  is still called on every import when a key is set.

Problems found:

1. **Shopping with the phone is the planning screen.** The hero header, the
   add field, suggestions, the ready card, the "In stock" section and the
   chevrons all stay on screen in the shop. Ticked rows stay in the aisle.
   There is no "what's next" and no way to hide what is bought.
2. **"In stock" is ambiguous** (the shop's stock or yours?). "Ticked" says what
   you did, not what it means.
3. **Claude runs when it is not needed.** Structured lines that the rule parser
   reads cleanly are sent anyway: slower, costs money, and the eval says it is
   no more accurate on those lines.
4. **No user-controlled backup.** A phone change without Google backup loses
   the library.
5. **Known issue 10:** a Claude call that hits the client timeout is billed
   but not metered, and reported as "Offline".
6. **Recipes tab cards are tall** (a colour band, three chips and a button
   each): two recipes fill the screen.
7. **Removing a recipe** from the list or the library doesn't say what happens
   to the other.

## Plan

| Phase | Change | Where |
|---|---|---|
| 1 | A **Shop** mode on the List tab (`Settings.listMode`, survives process death): a slim pinned bar with the count and progress, aisles as compact cards, bought rows hidden under a "N bought" row per aisle, "Next aisle" jumps to the next aisle with something left, no add field, ready card or chevrons. Same `ListUi`, same `ListActions`, same repository calls. A "Plan" button goes back. | `ui/list/ShopScreen.kt`, `ListRoute`, `Settings` |
| 2 | Words: "In stock" → **"At home"** (review pill, summary, list section, recipe facts, settings "Always at home"); counts say **bought** ("3 of 8 bought", "Finished: removed 3 bought items"). Remove dialogs say what stays. Empty states name the next action. | review, list, recipes, settings |
| 3 | **Hybrid extraction.** New held-out eval lines (`testdata/eval/`, outside the frozen set). Measure the rule parser on lines it flags (`check`) vs. the rest. If the unflagged lines are as accurate as Claude, the worker and the review call Claude only when a line is flagged, a line has no name, or there were no structured lines (page text, photo). "Retry with Claude" stays. A client timeout becomes its own reason ("Claude took too long"), not "Offline". | `core/parse`, `importing/Importer`, `claude` |
| 4 | **Backup and restore** (Settings › Data): a versioned JSON file through the Storage Access Framework. Validated before anything is written; restored in one transaction; a new list epoch and forgotten watch installs afterwards. Known issue 10: a timed-out call records an estimated usage so the meter never under-counts. | `data/Backup.kt`, `ListRepository`, settings |
| 5 | Tests: shop mode (Compose), mode switch keeps progress, rapid ticks, process recreation, watch ticks offline + a recipe added on the phone, backup round trip and rejection, hybrid gating. | `app/src/test` |
| 6 | Density: a shorter hero, compact recipe cards, less top padding on aisle cards. Screenshots re-recorded and inspected. | `ui/common`, recipes |
| 7 | **Meals this week** (from the Recipes tab): the recipes on the list are the week's meals (no new table); pick saved recipes, set servings, add straight to the list with Undo; remove or rescale a meal. | `ui/meals` |

## Risks

- **Wording changes break tests and habit.** Every renamed string is changed in
  its test too; TalkBack state strings are kept consistent ("Bought" on both
  the tick and the row).
- **A second list screen drifts from the first.** The shop screen takes the
  same `ListUi` and `ListActions`; it adds no state of its own beyond which
  aisles show their bought rows.
- **Gating Claude could lose accuracy on lines the rules read wrongly without
  flagging.** The eval reports that number; the gate stays off unless the
  unflagged lines score at least as well as Claude. The user can always retry.
- **Restore is destructive.** It never starts unless the whole file validates;
  the dialog says what will be replaced; the old database is not touched until
  the transaction commits.
- **Meals could duplicate recipes.** A recipe already on the list shows as
  such and asks before a second use (the existing "Add again?" rule).

## Outcome (Run 3, Step 8)

What landed, by phase, with its evidence:

| Phase | Done | Evidence |
|---|---|---|
| 1 Shop mode | `ShopScreen` on the same `ListUi`/`ListActions`; `Settings.listMode`; bought rows under one row per aisle; "Now" and "Next: <aisle>"; Plan/Shop pills | `ShopScreenTest` (10), `ListViewModelTest.shopModeIsASettingAndKeepsEveryTick`, `ScenariosTest`, `docs/screenshots/shop-*.png` |
| 2 Words | "At home", "bought", "On the list / Saved", remove dialogs say what stays, empty states name the next action | screen tests updated, screenshots re-recorded |
| 3 Extraction | `Uncertainty` + `HybridGateTest`; 45 held-out lines; Claude only when a line is flagged, for page text or a photo, or on "Ask Claude"; streamed replies with usage metered on a drop or a stall | `docs/reports/hybrid-gate.md`, `ClaudeClientTest` (5 new), `ImporterTest`, `ImportWorkerTest` |
| 4 Backup | `BackupData` + `BackupValidator` + `restoreBackup`; `BackupManager` over the Storage Access Framework; Settings › Data with a confirmation and Undo | `BackupTest` (5), `BackupManagerTest`, `SettingsViewModelTest` (2) |
| 4.3 Known issue 10 | fixed by streaming (KNOWN_ISSUES) | `aStreamThatDropsMidAnswerStillMetersWhatWasBilled`, `aStreamThatStallsIsATimeoutNotOffline` |
| 5 Tests | the three scenarios, rapid ticks, recreation, review after process death | `ScenariosTest` (5) |
| 6 Density | shorter hero, shorter recipe cards, "Open recipe" in the menu | `docs/contact-sheet.png` |
| 7 Meals | `MealsViewModel`/`MealsScreen`; `ListRepository.addSavedRecipe`; no new table | `MealsViewModelTest` (3), `MealsScreenTest` (5) |

### Adversarial review: what it found, and what was done

- **A finished trip left the list in Shop mode.** With the list empty the
  planning screen shows, but the next item typed would have flipped the
  screen into the shop (no field to type into). Fixed: Finish shopping and
  Clear all put the mode back to Plan when nothing is left; tested.
- **"Next aisle" read the scroll position in composition**, recomposing the
  bottom bar on every scrolled pixel. Now a `derivedStateOf`.
- **The meals search field could sit under the keyboard.** The screen pads
  for it.
- **Stale flags after "Ask Claude".** A Claude read now clears `askClaude`
  and `claudeSkipped`.
- **Two ways to add a saved recipe** (Saved › "Add to list" opens the review;
  This week › Add goes straight on). Kept on purpose: the review is where
  amounts and "at home" are changed, the picker is for speed; each says so.
- **Not fixed, written down** (KNOWN_ISSUES 13, 14): a line the rules misread
  without flagging stays misread unless asked about; a restore's settings and
  library edits are written after the database transaction.

### Scores (1–5, honest)

| | Before | After | Why |
|---|---|---|---|
| UX | 3 | 4 | One tap into a screen made for the shop; "at home" and "bought" say what they mean; meals in three taps. Still two tabs' worth of concepts (list, saved, meals). |
| Visual polish | 4 | 4 | The identity is unchanged; the shop screen is plainer on purpose; cards are shorter. No new decoration. |
| Reliability | 3 | 4 | Backup and restore with validation and Undo; metered Claude calls; the interrupted-trip scenario is a test. Restore's two-step write remains (KI 14). |
| Maintainability | 4 | 4 | No new table, no new library, one new screen per feature, every write still through the repository. The list view model now carries a mode. |
| Everyday usefulness | 3 | 4 | The shop screen and the meals picker are the two things a weekly shop needed; backup makes the library safe to rely on. |
