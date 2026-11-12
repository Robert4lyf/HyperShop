# Simpler everyday use (step Q): states, actions and what they change

An implementation aid for step Q, written before the code changes. The data model is unchanged. Every action below
calls an existing operation, and each line says which one and what it changes.

## Structure

| Destination | Answers | Primary action by state |
|---|---|---|
| **Meals** (selected week) | Which meals, how many servings, are their ingredients on the list? | No meals: **Add meal**. Meals not yet on the list: **Make shopping list**. All on the list: **View shopping list**. Past week: none. |
| **Shopping list** | What is left to buy, in store order? | Type to add. Ticked items: **Finish shopping**. |
| **Recipes** | What is saved? | Add a recipe (link, typed, photo). |

- Settings is a gear button at the top of each of the three screens. Its routes (Library, Aisles, Debug, Voice test)
  are unchanged.
- Launch opens the last of the three destinations used. An empty list no longer switches the app to Meals.
- A share, the widget and the launcher shortcut take precedence over that.

## Vocabulary

| Words | Action | Changes |
|---|---|---|
| **Add meal** | Puts a saved recipe in the selected week | `Planner.plan` (a `PlannedMeal`; the list is untouched) |
| **Add another** | The same, when the week already has it | `Planner.plan` again (a second meal) |
| **Make shopping list** | Puts every meal of the week that is not on the list yet on it, combined | `Planner.addWeekToList` (review `apply`, linked uses; pending meals only) |
| **On your list** | A meal's ingredients are on the list | (state: `MealShopping.TO_BUY`) |
| **Check food at home** | For each ingredient the week's meals need, say whether it is at home | `HomeCheck.have / need / haveSome` (unbought shares only, never manual extras) |
| **Need it / Have some / Have enough** | One ingredient's answer | `need` / `haveSome(amount)` / `have` |
| **Shopping view** | The same list, large and focused, kept awake | `ListMode.SHOP` (presentation only; ticks work in either view) |
| **Can't find it** | Sets a row aside for this trip | `markUnavailable` (state shown: *Set aside for this trip*; back next trip) |
| **Finish shopping** | Records what was bought; anything unbought stays on the list | `ListRepository.finish` (history kept; Undo) |
| **Buy again** | Your usual items and last shop, as a sheet from the list | `BuyAgain.add*` (manual items) |
| **Save recipe** | Keeps an import in Recipes only | review `saveForLater` |
| **Save and add meal** | Keeps it, adds it as a meal this week and puts its items on the list | review `apply` (as before: a single-recipe shortcut) |
| **Cooked it** | Records that a meal was cooked (a toggle) | `Planner.setCooked` |

"Plan", "Again", "Plan again", "Start shopping", "Unavailable" and "Not available today" are retired from the UI.

## Meal states (Meals screen)

| State | Shown on the meal | What changes it |
|---|---|---|
| Not on the list | *Not on your list yet* | Make shopping list |
| On the list | *On your list · N to buy* | ticks, Finish |
| Bought | *Bought* | — |
| Nothing to buy | *All at home* | — |
| Servings changed after Make shopping list | the list follows at once (`Planner.setServings` rescales the use in the same transaction) | — |
| Future week | as above. Make shopping list says it adds to the current list. | — |
| Past week | history only; no actions | — |

## Not changed in this step

- The item sheet still edits the total needed, not the amount still to buy. "Buy N" needs a new repository operation
  that maps a buying amount through covered contributions, home allocations and mixed dimensions. That is not done
  here: a naive subtraction would be wrong, and the brief rules it out.
- The review's single-recipe add stays one step, now labelled *Save and add meal*. Splitting it into add meal, then
  Make shopping list, would add a step for anyone importing one recipe to shop.
