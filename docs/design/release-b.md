# Release B — make weekly shopping effortless

## The journeys today (main at 64d1afb)

| Who | Today | Where the effort goes |
|---|---|---|
| Quick list | List tab: type "milk", Enter, repeat. Shop. | Fine. Must not get harder. |
| Weekly shopper | Recipes tab › Meals this week › tap Add per recipe › back to List › type every household item again › scan "At home" › Shop. | The meals *are* the list's recipe uses, so "Finish shopping" ends the week's plan with the trip; last week cannot be repeated; every regular is retyped; nothing remembers what was bought. |
| Recipe-heavy | Share › review › Add N items; Recipes tab for servings. | Works; keep it. |

## Design

One screen, **Plan** (the old "Meals this week", reached from the List tab's
"This week" card and from the Recipes tab), in three sections that read top to
bottom as the conceptual flow — *Meals → Buy again → At home?* — with one primary
action at the bottom that changes with the state: **Add N meals to the list** while
any planned meal is not on it, otherwise **Start shopping**. Nothing is a wizard: the
quick-list user never needs to open it, and Shop mode is untouched.

- **Meals** — the week (Monday start) with ‹ › to move between weeks. A planned meal
  is a card: title, state ("Not on the list yet" / "5 to buy" / "Bought" / …),
  servings stepper, remove. An empty week offers **Repeat the week of 13 Oct** (the
  latest earlier week with meals). Saved recipes below, searchable, one tap to plan.
  The same recipe can be planned twice on purpose.
- **Buy again** (current week only) — chips for household items bought on earlier
  trips: pinned ones first, then those bought by hand on two or more of the last eight
  trips. A chip already on the list says so and does nothing. **Add all** adds every
  suggestion not on the list in one write, one Undo. **Repeat last shop** adds the
  last trip's hand-added items. Long-press pins or hides.
- **At home?** (current week only) — the cupboard ingredients (oils, spices, baking,
  grains, tins) the planned recipes still need, one row per ingredient however many
  recipes share it, with **Have** / **Need**; plus the lines already set aside as at
  home, which can be put back. **I have all of these** does the lot.

## Ownership (one source of truth each)

| Thing | Source of truth | Derived, never stored |
|---|---|---|
| Saved recipe | `Recipe` copies sharing a `libraryKey` (unchanged) | the library entry = latest copy |
| Planned meal | **`PlannedMeal`** (week, libraryKey, servings, position, `useId?`) | title, its shopping state |
| Shopping requirement | `RecipeUse` + `Contribution` (unchanged) | amounts, progress |
| Plan → list link | `PlannedMeal.useId` (FK, SET NULL) | "on the list" / "bought" / "taken off" |
| Purchase | `Tick` covering contributions (unchanged) | |
| "At home for this plan" | `LineDecision` IN_STOCK (unchanged meaning) | |
| "Usually at home" | `Staple` (unchanged) | |
| Shopping history | **`Trip`** + **`TripItem`** written by Finish shopping | regular suggestions |
| Regular preference | **`Regular`** (mergeKey, pinned or hidden) | |

A planned meal is not a use: it can exist with no use (not added yet, or added to a
later trip), and a finished trip leaves it planned. "Add to the list" creates the use
through the existing review path (`Merger.apply`), so merging, staples, purchases,
Garmin and Undo work as before; the meal is linked to the use in the same transaction,
which is what makes a second tap a no-op. Servings on a linked meal go through
`rescaleUse`, so a bought amount stays bought. Removing a meal that still needs things
takes its use off the list too (with Undo); removing one whose shopping is done leaves
the trip's history alone.

## Data changes (schema 5)

- `PlannedMeal(id, weekStart, libraryKey, servings?, position, useId? → RecipeUse SET NULL, addedAt)`;
  `weekStart` is the Monday's epoch day, so time zones and clock changes cannot move a meal.
- `Trip(id, finishedAt)`, `TripItem(tripId → Trip CASCADE, mergeKey, displayName, aisleCode, byHand, qty?, unitHint?)`:
  one row per item bought per trip, so onions in four recipes are one occasion; `byHand`
  is what makes it a household purchase. The last 26 trips are kept.
- `Regular(mergeKey, displayName, pinned)`: pinned, or hidden from suggestions.
- Migration 4→5 creates them and plans every recipe still on the list (not archived)
  in the week it was added, linked to its use: nothing on the list changes.
- Backup: the new tables are optional lists (older files restore; a file without plans
  gets the same seeding), validated like the rest. Nothing new is sent anywhere.

## Risks and how they are held

| Risk | Guard |
|---|---|
| A week added twice doubles the list | link written in the same transaction; pending = unlinked or taken off; mutex in the view model; test |
| Half a week added on a crash | the whole week is one Room transaction |
| Edits replace a use (new id) and orphan the meal | `apply(replacing)` relinks in its transaction |
| "Remove from list" elsewhere | meal stays planned, shown as not on the list; Undo relinks |
| Deleted saved recipe | its copies are kept while a plan names the key; the card says "No longer saved" |
| Repeat last shop doubles items already there | skips keys on the list; one Undo |
| History grows without bound | 26 trips; Settings › Clear shopping history |
| At home changing old meanings | uses the existing IN_STOCK decision and "Need it"; staples untouched |
