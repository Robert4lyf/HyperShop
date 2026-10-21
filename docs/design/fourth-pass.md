# Fourth pass: correctness, UX refinement and reliability (Run 4, step 9)

Two briefs drove this pass: "Fourth Development Pass" (phases 1–8) and the
later "Comprehensive UX, Quality and Functionality Improvement". Most of the
second was already built in Run 3 (Shop mode, backup and restore, the hybrid
parser, Meals this week, the usage meter), so it was folded into the first
rather than rebuilt. PROGRESS.md › Step 9 has the per-step record.

## What changed for the user

| Before | Now |
|---|---|
| A recipe with nothing on the list read "All bought" (even when everything was at home), and the Recipes tab, its sheet and Meals each counted differently | One account, `ShoppingList.progress`: "3 still needed", "All bought", "Nothing left to buy", "Nothing to buy — all at home", in the same words everywhere |
| Raising servings after buying stretched the tick: the extra was silently "bought" | What was bought stays bought; only the difference is still needed, back on the list if the trip was finished |
| A restore killed half-way left the database from the file and the settings or ingredient edits from before | Journalled: the next start finishes it (or puts things back) and says so. A failure part-way is rolled back. "Nothing was changed" is said only when true |
| A damaged setting or ingredient edit in a backup was skipped silently | Refused before anything is written, with the reason |
| Shop mode walked recipe cards when planning grouped by recipe; "Now" was the first aisle with anything left, wherever you were | Shop mode always walks the aisles; "Now" is the aisle you have scrolled to, and the top bar names it |
| Fast taps on a meal's servings collapsed into one | Every tap counts; one message and one Undo per burst; a tap is saved even if you leave the screen at once |
| TalkBack: "Ticked / Not ticked" | "Bought / Partly bought / Still needed", "Mark bought" |
| "- 3 eggs" pasted from a list lost its amount | Read as 3 eggs |

## Not built, on purpose

- **A separate `PlannedMeal` table.** The later brief asks for the simplest
  weekly plan, and Meals this week already is one: the recipes on the list are
  the meals. A second table mirroring the list would need its own sync,
  backup, restore validation and Undo, and every one of those is a place for
  the two to disagree. Not worth it until there is a need it cannot meet
  (planning for a week that is not this one).
- **A different extraction strategy.** 40 more unseen lines confirmed the
  rules-first gate (docs/reports/hybrid-gate.md). Nothing to change but one
  parser defect.

## Adversarial review

Read as a stranger would, against the three scenarios.

**A — a quick list.** Open, type four things (each Enter keeps the field),
Shop, tick, Finish shopping, Undo offered. Five taps plus typing; no dialog
and no confirmation per tick. *Could be faster:* nothing found that would not
cost a safety net (Finish has Undo; there is no confirm to remove).

**B — a weekly shop from saved recipes.** Recipes › This week › Add, per
recipe; servings ± on the card; Go to the list; mark what is at home from the
review or the item sheet; Shop. *Found and fixed:* a recipe added after its
shared onion was bought read "1 still needed (1 partly bought)", which says
the opposite of the truth for that recipe; the bracket is gone (the list row
still says "1 more onion"). Fast servings taps were lost, and a tap followed
at once by leaving the screen could be cancelled; both fixed and tested.
*Left as is:* the item sheet's "On the list: 2 + 200g" for an onion counted
by one recipe and weighed by another is honest but reads oddly; changing how
totals across dimensions are written is its own decision.

**C — an interrupted trip.** Ticks on the watch offline, a recipe added on the
phone, the watch reconnects. A watch tick covers only what existed at the
list revision the watch had seen, so the new recipe's share stays needed and
both screens say "1 more onion" (`ScenariosTest`, `UseProgressTest`).
*Not verified on hardware here:* the reconnect itself; it is on Hand-off D.

**Other findings.**

- Fixed: the shop screen's "Next: <aisle>" button squeezed its arrow to a dot
  on a long aisle name (seen on the contact sheet).
- Open, documented: the device scenarios have never run here (KNOWN_ISSUES
  #15); the Garmin simulator job still fails, now with a log that says why
  (#12); a real process kill during restore is a Hand-off D check.
- Fragility checked: the restore tests stop the "process" with an `Error` so
  that nothing on the way out can catch it as an ordinary failure; the
  servings tests advance Robolectric's clock rather than sleeping.

## Scores (my own, after the fixes)

| | /10 | Why not higher |
|---|---|---|
| UX | 8 | The item sheet's mixed totals; Meals is two taps from the List tab |
| Visual polish | 8 | Consistent in both themes on the contact sheet; not seen on a real screen in daylight |
| Reliability | 8 | Restore, servings and recipe state are now tested at their edges; device and watch runs are still owed |
| Maintainability | 8 | One progress model instead of three counts; the journal is small and isolated |
| Everyday usefulness | 8 | The core shop is fast; the weekly plan is deliberately thin |
