# Step Z — fewer concepts

Branch `claude/step-z-fewer-concepts-rvynea`, from `b5a49da` (main after #92). Words and actions:
`docs/design/simple-ux.md`. No Room schema change, no watch protocol change, no golden data, frozen-version or `:core`
merge/tick change. Not merged or released.

## Summary

The owner found the app confusing; a review put that down to the number of concepts. Step Z removes concepts rather
than rearranging them:

- **One way to add a saved recipe: "Add to list".** Its ingredients go on the list at once (planned and linked in the
  review's own `apply` transaction). "Add meal", "Make shopping list" and "Not on your list yet" are gone.
- **One list screen.** No shopping mode, no Shopping view, no "Next aisle", no in-shop search. The list folds each
  aisle's bought rows under "N bought", has "Can't find it" on the item's sheet and "Change store" in its menu when
  there are two stores.
- **Two tabs: Shopping list and Recipes.** The Meals tab, its week pager, Cooked toggle, meal notes and Add-meal sheet
  are gone; servings and Remove from list are on a recipe's card in the list.
- **"At home" in one place:** the review row's toggle (now on every row), with Settings' staples as its default. Check
  food at home, the row sheet's switch and "Always at home", and the list's At home section are gone.
- **A lighter review:** one button ("Add to list · N items"), "Save recipe only" in the menu, Claude's status only when
  it failed or was skipped, no help paragraphs, the aisle chips behind "Change aisle".
- **No instructions on screens**, **one word per idea** ("N to buy", "bought", "Remove from list", "Delete recipe"), and
  **Recipes without clutter** (sort and filter from 10 recipes; the detail starts with the ingredients).

## Removed

| Removed | Where it was | What replaces it |
|---|---|---|
| "Add meal" (plan only) and "Not on your list yet" | Recipes, Meals | **Add to list** puts the ingredients on at once |
| "Make shopping list" and its preview line; the Meals bottom-action states | Meals | nothing: adding is one step |
| Shopping view (`ListMode`, `ShopScreen`), "Back to list", "Next: aisle", in-shop search | List | the one list, which ticks and finishes |
| Meals tab: week pager, Repeat the week, Cooked toggle, meal notes, Add-meal sheet | Meals | Servings and Remove from list on a recipe's card in the list |
| "Plan this week" / "See this week" on the empty list | List | — |
| Check food at home (screen, route, chip, menu entry, Have some dialog) | List, Meals | the review row's At home toggle |
| The row sheet's At home switch and "Always at home" | Review | the row's toggle; staples in Settings |
| The list's "At home" section with "Need it" | List | Edit on the recipe's card reopens the review |
| "Read by Claude" / "Read offline" / "Waiting for Claude" badges, offline explanation dialog | Review | Claude's note and Retry, only when it failed or was skipped |
| "Save recipe" button beside Add | Review | "Save recipe only" in ⋮ |
| Help paragraphs: the cupboard hint, "Tap an ingredient…", Arrange items' paragraph, Settings' staples, backup and Buy again paragraphs, the model note, the cooking view's scope, the item editor's "Edits the total…" | Review, List, Settings, Recipes | the controls' own labels |
| The sort control and "4★ & up" below 10 saved recipes | Recipes | — |
| Settings: `list_mode`, `review_sheet_opens` | DataStore | — (old values stay in the file, unread) |

Files deleted: `ShopScreen.kt`, `MealsRoute.kt`, `MealsViewModel.kt`, `HomeCheckScreen.kt`, `ShopPrep.kt` and their
tests (`ShopScreenTest`, `MealsScreenTest`, `MealsViewModelTest`), and the screenshots `shop-*`, `meals-*`,
`home-check-light`, `home-amount-dialog-light`. Behaviour that still exists was moved, not dropped: the bought fold, the
linger after a tick and Change store are tested in `ListScreenTest`; Can't find it in `ListViewModelTest`; Buy again in
`BuyAgainSectionTest` and `BuyAgainViewModelTest`.

Size, from `b5a49da` to the end of Step Z: the app's and `:data`'s production code lost 3,305 lines and gained 738
(net −2,567); with tests, `android/` lost 4,747 and gained 1,313. Fifteen files were deleted (eight sources and tests,
seven screenshots); four were added (`AddToList.kt`, `BuyAgain.kt` and Buy again's two tests).

## Journeys, before and after

Counted by `JourneyTest` through the real app shell (Robolectric): taps (clicks and keyboard actions), entries typed,
scrolls, and screen changes (a new destination, or a dialog or sheet opening). Counts, not timings; no person took part.

| Journey | Q.0 (93d99fa) | Before Z (b5a49da) | After Z |
|---|---|---|---|
| Core job: share a recipe link, accept the review, tick everything, finish | — | 6 · 0 · 0 · 2 | 6 · 0 · 0 · 2 |
| Quick shopping: first run, milk and bread, tick both, finish | 7 · 2 · 0 · 0 | 7 · 2 · 0 · 0 | 7 · 2 · 0 · 0 |
| Meal-based: three saved recipes, one served to 5, rice partly at home | 9 · 1 · 5 · 2 | 13 · 1 · 6 · 6 | 15 · 1 · 4 · 4 |

(taps · typed · scrolls · screen changes)

The core and quick journeys did not get longer. **The meal-based journey does not come in at Q.0's 9 taps and 2 screen
changes.** Where the taps go:

| Part | Taps | Screen changes |
|---|---|---|
| Recipes tab, Add to list on each of the three recipes, back to the list | 5 | 2 |
| The chilli for 5: ⋮ › Group by recipe, the chilli card's ⋮ › Servings, +, Done | 6 | 1 (the dialog) |
| 300 g of the rice at home: the rice row, Change amount, the amount field, Save | 4 | 1 (the item sheet) |

Which taps cannot go without putting back what this step removed:

- The two taps of **Group by recipe**: servings belong to a recipe, and the brief puts them on the recipe's card in the
  list; in store order there is no card. Putting a stepper back on a screen of its own is the Meals tab again.
- **Servings › Done**: a dialog, so that a stray tap does not rescale a recipe. An inline stepper in the menu would
  save one tap and the dialog's screen change, at the cost of each + writing at once.
- The **rice**'s four taps: "at home" is said once, on the review; a partial amount on the list is an edited amount.
  The food-at-home screen did this in fewer taps per item but was a screen and a concept of its own, which Z.4 removed.

Without the two optional corrections, putting three meals on the list is 5 taps and 2 screen changes, under Q.0's
figure; the whole journey is longer than Q.0's because Q.0 did everything on one long screen. Whether this is easier to
understand is for the owner to judge in use.

Found by the core journey and **not fixed**: the review's "Added …" message (with Undo, 5 s) sits over the bottom of
the list, including the Finish bar, after the review closes. The journey waits it out (not counted as a tap); a person
can swipe it away. Lifting it above the Finish bar was tried and covered the last rows instead, so it was reverted.

## Claims checked against the code

- **Recipes' "Add meal" only planned the meal**: true (`Planner.plan`); the review's add went through `apply`.
- **Shopping view and the list showed the same rows and both ticked and finished**: true; `ShopStart.request` only set
  the active store and the mode.
- **"I have some" in the review row sheet**: not so. The sheet had an "At home" switch and an "Always at home" button;
  since Y.20 the row's own pill appeared only on rows already at home, so the sheet's switch was the only way to mark a
  row. Applied the intent: the pill is on every row, and the sheet's switch and "Always at home" went.
- **An at-home control in the item sheet**: not so; it showed "At home 0" in its breakdown only. That figure now shows
  only for an amount recorded before.
- **The list's own At home section** ("Need it") was a fifth place the brief did not list; it went too.

## Data and the rules

- Every write still goes through the repository; nothing before start-up is Ready (the new `addRecipeToList` waits
  for it); Claude is called only while the review is in the foreground.
- **Nothing undoes a purchase.** Undo of Add to list is `Planner.takeBack` → `unplan`, which removes the use only when
  nothing of it is bought and otherwise withdraws the unbought shares; Remove from list is `removeFromList` (the same
  rule); Servings is `setUseServings` (bought stays bought). `RecipesViewModelTest` ticks an item before Undo and checks
  it stays.
- Planned meals are still written for the current week (an import plans itself, an edit relinks, Add to list plans),
  so backups and the plan invariants hold. They have no screen: meals planned earlier, past weeks and Cooked marks stay
  in the database and in backups, unseen (KNOWN_ISSUES #46). No data was deleted.

## Not done, and why

- **The meal-based journey target** (above): not met; the remaining taps are explained rather than hidden.
- **Unused `:data` functions** (`addWeekToList`, `previewWeek`, `repeatWeek`, `setCooked`, `HomeCheck`, …) were kept with
  their tests: they guard the plan's data rules and backup tests use them. Deleting them is a follow-up (KNOWN_ISSUES #46).
- **Unused columns** (`PlannedMeal.cookedAt`, `RecipeNote.timesCooked`, `ListItem.onHand`) stay: no schema change was
  allowed.
- **The snackbar over the Finish bar** (above).

## What could not be checked here

There was no device, emulator, launcher, TalkBack or Forerunner 245 in this container. Screenshots are Robolectric
renders. The emulator scenarios (`WeeklyShopScenariosTest`, `DeviceScenariosTest`, `PerformanceProbeTest`) were
rewritten for the new flow and only compiled; they run in CI. The watch app was not touched; its compiles were skipped
here (`HYPERSHOP_SKIP_WATCH=1`, as the connectiq workflow builds it) while its argument-limit check ran.

## Verification

`tools/verify.sh` passed before every commit (Z.0 to Z.9), with `HYPERSHOP_SKIP_WATCH=1`: ktlint; every JVM unit test
(core, extract, claude, data, app); debug, emulator-test and unsigned release APKs; the release-promotion guards;
catalogue; merged manifests; secrets scan; golden checksum; protocol parity; the sync model checker; the watch's
argument limits. Each step was committed from a separate checkout so that it was gated on its own. Every Roborazzi
screenshot was re-recorded by the last run (37, each made by a test; none left over from a removed screen) and
`tools/contact-sheet` rebuilt `docs/contact-sheet.png`.

This container's Maven Central answered bursts with HTTP 429. Builds ran with Gradle's own retry settings raised on
the command line (`-Dorg.gradle.internal.repository.max.retries=15`, `initial.backoff=3000`), nothing committed.
