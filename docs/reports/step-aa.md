# Step AA — everyday polish

## Summary

Step Z removed concepts. Step AA tidies the ones that stayed, without adding tabs, modes, settings or help text.

- **Servings on the recipe card (AA.1).** A recipe already on the list shows "− Serves N +" in place of Add to list, on its card and in its sheet. Each press calls `Planner.setUseServings`. Its ⋮ menu gains **Remove from list**. On the list's recipe card the Servings dialog became the same inline stepper.
- **Review rows use a checkbox (AA.2).** Ticked means "add this to the list". Unticked rows are greyed and say **At home**; staples start unticked. TalkBack reads each row as one node: the name, the amount, and "Will be added" or "At home, not added". It toggles the row with a row action.
- **Review wording (AA.3).**
  - An "A or B" line shows its two options as buttons, on the row and in its sheet. Picking the other one is the existing swap. The "Check: …" line for choices is gone.
  - Amounts lose the "+". An item already on the list reads "200ml · 700ml with what's on your list".
  - "Edit the line" became **Fix the recipe's text**; "This is wrong" became **Report a wrong reading**.
  - The sheet's amount field is labelled **Amount in the recipe**, because that is what it sets.
- **Messages never cover the bottom bar (AA.4).** `BottomActionBar` reports its measured height, and the app's one snackbar sits that far up. This covers Finish shopping, Done arranging and the review's Add to list, at any font size. `SnackbarPlacementTest` fails if they overlap.
- **Compact list header (AA.5).**
  - Once the list has items, its top is one row: "5 to buy · 2 bought", a progress bar, the gear and ⋮. The banner shows only on the empty list.
  - **Add item** is pinned under that row.
  - At 360×640 dp the first aisle and three items are on screen (`CompactHeaderTest`).
  - Recipes does the same once there are recipes.
- **Recipes ordered by use (AA.6).** **Most used** is the default: added to the list most often, then most recently, then newest saved. It is read from planned meals, with no new column. A card reads "Serves 4 · last added 3 days ago".
- **One message for several adds (AA.7).** Adds made while the last add's message is still up combine into "3 recipes added · 12 to buy". Its one Undo takes them all back through `Planner.takeBack`, which removes only unbought shares.
- **Dead code removed (AA.8).** The week-add, repeat, preview, cooked and food-at-home code went (`HomeCheck` and seven `Planner` functions, among others), along with the tests that only tested them. Columns, tables and backups are unchanged. KNOWN_ISSUES #46 is updated.
- **Change amount opens on the amount (AA.9).** The field is focused with its text selected, so the new amount can be typed straight away.

## Journeys, before and after

`JourneyTest` counts each journey through the real app shell (Robolectric): taps (clicks and keyboard actions), entries typed, scrolls, and screen changes. These are counts, not timings, and no person took part.

| Journey | Q.0 | AA.0 (e293db6, after Z) | After AA |
|---|---|---|---|
| Core job: share a recipe link, accept the review, tick everything, finish | — | 6 · 0 · 0 · 2 | 6 · 0 · 0 · 2 |
| Quick shopping: first run, milk and bread, tick both, finish | 7 · 2 · 0 · 0 | 7 · 2 · 0 · 0 | 7 · 2 · 0 · 0 |
| Meal-based: three saved recipes, one served to 5, rice partly at home | 9 · 1 · 5 · 2 | 15 · 1 · 4 · 4 | **9 · 1 · 3 · 3** |

(taps · typed · scrolls · screen changes)

All targets are met:
- The core and quick journeys are no longer than at AA.0.
- The meal journey takes 9 taps and 3 screen changes.

Its 9 taps:
- the Recipes tab;
- Add to list on each of the three recipes;
- **+** on the chilli's stepper (AA.1; this was ⋮ › Group by recipe › ⋮ › Servings › + › Done);
- the Shopping list tab;
- the rice row;
- Change amount (the field is now focused, which saves a tap);
- Save.

The journeys wait, without counting it, in one case: a message is up over the row they are about to tap. In the core journey that is the last row; in the meal journey it is the rice. A message now sits above the bottom bar but can still cover list rows for its few seconds; a person waits or swipes it away. The core journey no longer waits before Finish.

## Lines

Measured from e293db6 (the start of AA) to the last commit:

| | Added | Removed |
|---|---|---|
| Main sources | 814 | 1069 |
| Tests | 678 | 440 |

AA.8 alone removed 965 lines and added 132, including `HomeCheck.kt` (259) and `HomeCheckTest.kt` (165).

## Claims in the brief that were wrong

- **AA.3 "Check: has 'or'".** This text was never on screen; it existed only in a test's sample row. The app said "Check: the recipe offers a choice; the swap button takes the other". Both are gone.
- **AA.3 "Amount to buy".** The row sheet's field sets the recipe line's own amount, which servings then scale. It does not set an amount to buy, so it is labelled **Amount in the recipe**.
- **AA.4 "The list screen owns its snackbar placement".** Done once instead: every pinned bar reports its measured height. The review's previous fixed 80 dp lift was itself 1 dp short of an 81 dp bar, and more at a large font.
- **AA.6 "most recently added to the list".** No stored field says this. `Recipe.lastAddedAt` is also set by "Save recipe only", so it stays the "saved" date. Uses are archived after a trip. The order comes from `PlannedMeal` rows instead, since every Add to list and every import added to the list plans one meal. Meals planned before Step Z without being added count as uses.

## Not done, and why

- Code that was dead **before** Step Z was left alone, since the brief limits AA.8 to what Step Z left unused:
  - `RecipesViewModel.addFromLibrary`, `ReviewViewModel.previewRow`/`currentPreview`, the `Fact` composable, `heroBrush`, `ListScreen.STORES`.
  - DAO methods with no caller: `observeMeta`, `deleteAliases`, `deleteShelfRanks`, `setParsed`, `putJob`.
  - In `:core`, `ShoppingList.setOnHand` has no production caller now that `HomeCheck` is gone.
- `Planner.plan`, `week` and `restore` have no production caller. They were kept because many tests use them to set up and read the plan that is still written.
- "Your usual" (cooked most, then planned most) was removed from the sort menu. "Cooked it" has had no screen since Step Z, and **Most used** covers the rest. "Recently added" is renamed **Recently saved**.
- The performance probe's week-preview and at-home-check budgets went with their code. "6 meals onto the list" now times six Add to list calls against the same 1300 ms budget. `docs/reports/performance.md` was not re-measured.

## What could not be checked

- There is no device, emulator, TalkBack or Forerunner here. The emulator scenarios and the performance probe were updated and compiled, but not run. The checkbox rows' TalkBack reading is tested through semantics only.
- One full-suite run had `StartupTest.aRecoveryThatCannotFinishStopsAndAsksThenCarriesOnWhenTold` fail with "uncaught exceptions before the test started", which means an earlier test in the same JVM leaked an exception. It did not happen again in two later full runs, and the culprit was not found (compare KNOWN_ISSUES #35).
- Whether the new order and wording are easier to use is for the owner to judge in use.

One commit per sub-step (AA.0–AA.9), each gated by `tools/verify.sh`. The watch compile was skipped; the argument limits were checked.
