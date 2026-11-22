# Step AC — clear screens, walking order

## Summary

This step fixes local clarity problems found in a review of Step AB, removes the large geometric header wherever it still showed, and lets the owner order an aisle's sub-groups from Walking order. It adds no tabs, modes, settings or help text.

- **One plain top bar everywhere (AC.1).** Every screen's top is now a compact bar: the back arrow (where there is one), the title, an optional one-line subtitle, and actions on the right. That covers the empty list, empty Recipes, Settings, Aisles, the ingredient library and the review.
  - The review's top shows the title with its pencil, "4 to add · 1 at home" and the servings. When Claude failed or was skipped, one line gives the reason with **Retry** and **✕**. On a 360×640 dp phone the first ingredient shows without scrolling.
  - "N recipes ready to review" is a plain card in the theme's colours.
  - A recipe without a photo shows its art as a small square beside its title; a real photo keeps its band.
  - The hero gradients, the glow, their colour and their tests are gone.
- **Sub-groups in Walking order (AC.2).** In Settings › Aisles, an aisle opens (⌄) to show every group it can have, plus **Everything else**. They move with the aisles' own controls: ↑ ↓, and ⋮ › Move to top / bottom.
  - This uses the same stored order as the list's **Arrange items**, per store, and bumps the list's revision, so the list and the watch follow it as they do an aisle reorder.
  - The shared text and the widget did not follow the group order before; now they do.
  - No schema change and no protocol change.
- **Change amount is one small form (AC.3).** It holds the name, the amount and **Aisle: …** (one menu), with Save.
  - An item with two kinds of amount ("2 + 200 g") opens on the one it is bought by and says "Also 200g on the list, kept as it is"; saving leaves that part alone. Before, the field opened empty.
  - Category and usual pack are a second small form under More details.
- **"On your list" (AC.4).** A recipe on the list shows a ticked "On your list" beside its servings, on its card and in its sheet. TalkBack reads it as one item.
- **Review warnings in plain words (AC.5).** "Check: …" and red text are gone; the warning icon alone marks a row.
  - Each of the 19 reasons the parser, Claude's post-processing and the line matching can raise has one sentence, listed in simple-ux.md.
  - A backwards range reads "3 cloves · recipe says 3–2", with a button for each reading.
  - A missing amount reads "No amount found", with **Set amount**.
- **Staples are plain chips (AC.6).** Tapping one opens **Add to list once** or **Remove**.
- **Denser list (AC.8).**
  - Aisle headings are one slim row: icon, name, "N to buy", arrow.
  - Group labels show only in aisles with six or more items, and always while arranging.
  - Rows are 56 dp, so a 360×640 dp phone shows five items under the top bar and the Add field.
- **Small words (AC.9).** The review's back button reads **Review later**. The Walking order row now mentions the groups.

## Journeys, before and after

`JourneyTest` drives the real app shell (Robolectric) and counts taps, entries typed, scrolls and screen changes. These are counts, not timings; no person took part.

| Journey | Step AB (docs/reports/step-ab.md) | After AC |
|---|---|---|
| Adding by hand | 4 · 1 · 0 · 0 | 4 · 1 · 0 · 0 |
| Core job: share, accept, tick, finish | 6 · 0 · 0 · 2 | 6 · 0 · 0 · 2 |
| Quick shopping: milk and bread | 7 · 2 · 0 · 0 | 7 · 2 · 0 · 0 |
| Meal-based: three recipes, one for 5, rice partly at home | 9 · 1 · 3 · 3 | 9 · 1 · 3 · 3 |
| **New:** Salad before Vegetables, from Walking order to the list | — | 6 · 0 · 1 · 4 |

(taps · typed · scrolls · screen changes)

No journey is longer than in Step AB.

The new journey's taps are: the gear, Walking order (after one scroll down Settings), ⌄ on Produce, ↑ on Salad, then back twice. Two of its screen changes are into Settings and Aisles; two are back out to the list.

## Where the mosaic was (AC.0) and what became of each

| Where | Screen | Now |
|---|---|---|
| `ScreenHeader` (mosaic + shade) | empty list, empty Recipes, Settings, Aisles | the plain bar (`ScreenHeader` rewritten); the ingredient library uses it too |
| The review's `Hero` (`RecipeBackdrop`: photo or mosaic under a shade) | review | a plain block: title, pencil, summary, servings, site, Claude's line |
| Recipes card band (`RecipeBackdrop`) | Recipes | a real photo keeps its band; otherwise a 56 dp square of the recipe's art beside the title |
| `RecipeArt` on the list's recipe card | list › Group by recipe | unchanged: already a 56 dp square |
| `Brand.WarmCall` + `heroGlow` | "N recipes ready to review" card | `secondaryContainer` card |
| `heroBrush`, `Brand.OnHeroSoft`, `HERO_INK`, `MOSAIC_SHADE` | none (or only the above) | deleted |

`lowPoly` stays: it now draws only the small art squares. Deleted with the headers: `HeroTextTest` and its `hero-large-font-light.png` (replaced by `ScreenHeaderTest`), and `HeroContrastTest`, since no text sits on a mosaic any more.

## Lines

Measured from b42be93 to this step's last commit:

| | Added | Removed |
|---|---|---|
| Main sources | 697 | 491 |
| Tests | 414 | 309 |

## Claims in the brief that were wrong

- **`EmptyHero` draws a mosaic.** It doesn't: it is plain text. The cooking view and the recipe sheet draw none either; the sheets show a real photo when there is one.
- **AC.2: "First find how sub-group order is stored."** It was already one stored order (`ShelfRank`, per store) that the list's Arrange items writes and the watch snapshot already followed.
  - Two places did not follow it: the "Share shopping list" text (sorted by add order within an aisle) and the widget (its first four items in storage order, not even by aisle). Both now follow it.
- **AC.3: "the amount field opens filled with the current amount."** For an item with two kinds of amount it opened empty. That is now fixed as described above.
- **AC.7: "Hiding a usual item shows a message with Undo."** This was already true since Step AB.1, with a test. Nothing changed; AC.7's commit only records that.

## Not done, and why

- **Confirmations the form no longer needs.** The "Discard edits and mark bought / remove / swap" confirmations in the item sheet can no longer be reached: the form doesn't show those actions. Seven `ItemSheetHostTest` cases that tested that combination were removed. The confirmation code is left in place and harmless, and Back on a form with unsaved edits still asks first.
- **Unit chips kept in the form.** Change amount keeps the amount's unit chips (kg, g, each, More units). They are how the amount changes unit (`UnitChangeTest`), so they count as part of the amount.

## Found on the way

- **`EntryNavigationTest` depended on test order.** Robolectric's window never enters touch mode. So when a focused control went away, Android re-focused the window, and which control won depended on the layout; the plain header changed which one. The test now puts the window in touch mode, as a phone's is (in touch mode, Android 9 and later do not re-focus).
- **An intermittent `CompactHeaderTest` crash.** It failed once in a mixed local run with "Only the original thread … can touch its views". It passes alone and passed in every full gate run. The crash comes from Compose's test clock running a recomposition on a worker thread after a snapshot change made off the main thread. The `snapshotFlow` reads fixed in Step AB were the known source; another source was not found.

## What could not be checked here

- **Emulator and device:** the emulator scenarios compile but were not run here; CI runs them on the PR.
- **TalkBack:** the Walking order controls, "On your list", the reading buttons and the staple menu are tested through semantics only.
- **Watch:** the order reaching the watch is tested on the phone's snapshot (`WalkingOrderGroupsTest`). It was not checked on a Forerunner.
- **The keyboard:** not raised in Robolectric.

One commit per sub-step (AC.0–AC.10), each gated by `tools/verify.sh` (watch compile skipped; argument limits ran).
