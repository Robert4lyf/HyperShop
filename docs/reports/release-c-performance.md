# Release C: performance, measured

How: `QueryBudgetTest` builds the sample list in an in-memory Room database that
reports every statement, collects the list screen's two flows as `ListRoute`
does (the rows and the week's plan card), ticks one item and counts the `SELECT`s
that follow, in each list mode. The device probe (`PerformanceProbeTest`, on a
phone) measures time; this measures work, which is what a code change moves.

## Reads for one tick

| mode | before C.6 | after C.6 | what went |
|---|---|---|---|
| Shop | 28 | 23 | Staple, Trip, Regular and a third list load: the plan card's flows (planner, Buy again, home check, library) are not collected while the shop screen is up, since it draws no card |
| Plan | 37 | 32 | Buy again's own `load()`: it now reads the list the repository's flow already handed it |

The list is still read twice per tick (Item, RecipeUse, Contribution, Tick,
ItemAlias): once inside the write, once when the repository's flow re-reads
after Room's invalidation. That is the repository's design (a write never trusts
a stale copy) and is left alone.

The planner's `describe` read one `Recipe` query per meal; it now reads one for
the week (`recipesByKeys`). With the sample list (no meals) that shows as 0 → 0;
with a week of six meals it is 6 → 1 on every list change while planning.

## Left alone, with why

- `ListViewModel.ui` is `SharingStarted.Eagerly` so the list is ready the moment
  the tab shows; the cost is one load at start-up.
- `MealsViewModel` recomputes up to three times per list change while the plan
  screen is open; a screen the user is looking at, a few queries each, no
  measured jank. Not worth a restructure in a polish release.
- Archived `RecipeUse` rows are never purged. `load()` reads them all; growth is
  a handful of rows a week, so years of use stay in the hundreds. A purge would
  have to respect the planned meals that link to them (`useId`); recorded in
  `docs/KNOWN_ISSUES.md` for a later release rather than risked here.
- The device probe's tap-to-visible time includes the row's placement spring;
  that test runs on a phone, which this container cannot, so its clock handling
  is left as it is and noted in the hand-off.

## Also in C.6 (not performance, same pass)

- `voice-test.log` is capped at 2 000 lines on disk as well as in memory.
- The camera's copy of a recipe photo (`cache/camera/shot.jpg`) is deleted once
  the photo is stored.
