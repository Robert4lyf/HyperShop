# ADR 002 — Covered and DONE

**Status:** accepted (Step 1.3)

## Context
One list item can be fed by several recipes and manual adds, added at
different times, and ticked from the phone or the watch while more recipes
arrive. A single "checked" boolean cannot say "you bought the onions for the
curry, but the soup added since needs one more".

## Decision
- An item's amount is the **sum of its contributions** (`ContributionState`),
  never a stored total.
- A **tick** covers every contribution the item has *at that moment*
  (`coveredBy = tickId`). Contributions created later start uncovered.
- **DONE** = at least one contribution and all of them covered
  (`Coverage.isDone`). Otherwise the list shows the uncovered amount ("1 more")
  and, when some are covered, "2 already ticked" (`Coverage.isPartial`).
- **Untick** uncovers exactly the contributions of that item's most recent
  `Tick`.
- Removing or rescaling a use deletes or rescales its contributions, covered or
  not.
- **Ticks never create stock** and rounding is display-only, so a tick always
  fully covers.
- A watch tick carries `seenListRev`; it covers only contributions with
  `createdListRev ≤ seenListRev` (anything newer stays uncovered →
  `partial`, PLAN §3.6).

## Consequences
DONE is derived, never stored. Every change to contributions or ticks bumps
`Item.rev` in the same transaction.
