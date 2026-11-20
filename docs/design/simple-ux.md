# Simpler everyday use: structure, words and what each action changes

Step Q wrote the first version of this page. Step Z ("fewer concepts") replaced it: the app has fewer screens,
fewer modes and one word per idea. The data model is unchanged; every action calls an existing operation.

## Structure

| Destination | Answers | Main action |
|---|---|---|
| **Shopping list** | What is there to buy, in store order? | Type to add. Tick. **Finish shopping** once something is bought. |
| **Recipes** | What is saved? | **Add to list** on a recipe. Add a recipe (link, typed, photo). |

- Settings is a gear at the top of each. Its own screens (Ingredient library, Walking order, Debug, Voice test) have
  a back arrow.
- A share opens the review; the widget and the launcher shortcut open the list.
- There is no Meals tab, no shopping mode and no food-at-home check. Planned meals are still written for the current
  week (an import plans itself, an edit relinks), so backups and the data rules hold; they have no screen of their own.

## One word per idea

| Idea | The word | Not |
|---|---|---|
| Put a saved recipe's ingredients on the list | **Add to list** | Add meal, Plan, Save and add meal, Make shopping list |
| How many unbought items | **N to buy** | N left, still needed, to add (on the list) |
| An item or a recipe's items all bought | **bought** ("2 bought", "Bought ✓", "All bought") | done, Done ✓ |
| Take a recipe's items off the list | **Remove from list** (only what is unbought goes) | Remove meal, ✕ |
| Delete a saved recipe | **Delete recipe** | Remove from your recipes |
| The order of saved recipes | **Most used** (the default: added to the list most often, then most lately, then newest saved); **Recently saved**, **Highest rated**, **A–Z**. The sort control shows from 10 recipes | Your usual, Recently added |
| A saved recipe's card line | "Serves 4 · last added 3 days ago" ("last added" left out when it never has been) | added today, 12 ingredients, planned 3 times, cooked once |
| Something is already at home | **At home** (an unticked review row; staples in Settings start unticked) | Have it, Have some, I have some |
| Can't get it on this trip | **Can't find it** → *Set aside for this trip*; **Found it** takes it back | Unavailable, Not available today |
| End the trip | **Finish shopping** | Done |
| Keep a reviewed recipe without adding it | **Save recipe only** (review ⋮ menu) | Save recipe, Save for later |
| Correct the words a recipe line was read from | **Fix the recipe's text** (review row sheet) | Edit the line |
| Say the app read something wrong | **Report a wrong reading** (review row sheet, item sheet) | This is wrong |
| A line offering "A or B" | its **two options** as buttons, the one it reads now selected | Check: has 'or', Use B instead, swap |
| How much a review row adds | the amount alone ("200 ml"); with the item on the list already, "200 ml · 700 ml with what's on your list" | +200 ml, → 700 ml on the shopping list |
| The row sheet's amount field | **Amount in the recipe** (it sets the line's own amount, which servings then scale) | Amount, Amount to buy |

On the review, lines still to go on the list are counted as **N to add** ("4 to add · 1 at home") and the button reads
**Add to list · N items**: they become "to buy" once they are on the list.

## Actions and what they change

| Words | Where | Changes |
|---|---|---|
| **Add to list** | Recipes card and sheet | `Planner.addToList` → `ListRepository.addSavedRecipe(plan = true)`: the review's own `apply`, planned for this week and linked in its transaction. Undo: `Planner.takeBack` (`unplan`: only unbought shares) |
| **Add to list · N items** | Review | review `apply` (plans itself; an edit relinks) |
| **Save recipe only** | Review ⋮ | review `saveForLater` |
| **Checkbox** (ticked = add; unticked rows are greyed and say **At home**) | Review row | the row's decision (in stock); staples from Settings › Always at home start unticked; TalkBack reads the row as one node (name, amount, "Will be added" or "At home, not added") and toggles it with "Mark at home" / "Add to list" |
| **− Serves N +** | A recipe on the list: its Recipes card and sheet (in place of Add to list), and its card on the list (Group by recipe, ⋮) | `Planner.setUseServings` on each press (the meal and the use rescaled together; what was bought stays bought). On the list twice, the latest is changed and the card says "on the list twice" |
| **Remove from list** | A recipe on the list: its Recipes card's ⋮, its card on the list (⋮), its ingredients sheet | `ListRepository.removeFromList` for each time it is on the list (the use goes only when nothing of it is bought; otherwise unbought shares are withdrawn); one Undo |
| **Can't find it** / **Found it** | The item's sheet | `markUnavailable` / `markAvailable` |
| **Change store** | List ⋮, only with two or more stores | `ShopStart.change` → active store |
| **Store order / Group by recipe**, **Arrange items** | List ⋮ | `Settings.listView`; `arrangeWith` |
| **Finish shopping** | List, once something is ticked | `ListRepository.finish` (Undo) |
| **Buy again** | List ⋮ and a chip below the items | `BuyAgain.add*` |

## On the list

- Bought rows fold under one **"N bought"** row per aisle; a row just ticked stays in place for 2.5 s, so a second tap
  undoes it. The screen stays awake while anything is ticked.
- Once the list has items, its top is one row: **"N to buy · M bought"** ("N to buy" before anything is ticked, "All
  bought" at the end), the progress bar under it, the gear and ⋮; "1 set aside" and the watch's state in small text when
  there are any. The mosaic banner only on the empty list. The **Add item** field is pinned under that row. Recipes
  does the same: one "Recipes" row once there are recipes, the banner while there are none.
- No instructions on screens: a control that needed a paragraph was relabelled or removed.
