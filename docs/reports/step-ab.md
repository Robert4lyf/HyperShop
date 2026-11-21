# Step AB — adding by hand

## Summary

The pinned **Add item** field is now the one place to add anything that isn't a recipe. This step adds no button, screen, tab, setting or help text.

- **Usual items under the field (AB.1).** While the field is focused and empty, it shows up to eight chips: the items you add by hand most often that aren't on the list. If last shop's items aren't all back on the list, the last chip is "Last shop's N items".
  - One tap adds an item at its usual amount; the field keeps focus and the next candidate moves up.
  - Typing switches to the existing typed suggestions.
  - A long press, or a TalkBack action, offers **Don't suggest**.
  - With no shopping history there are no chips and no text about them.
- **Buy again's separate surfaces are gone (AB.2).** That means the chip below the items, the ⋮ entry and the sheet, together with their view model and tests.
  - The behaviour that remains (what is suggested, Don't suggest, last shop's items in one go, never doubling, never undoing a purchase) is now tested on the field.
  - No data was dropped: trips and regular items are kept, and a pin you already set is still offered first.
- **Every hand add says where it went (AB.3).** The message is "Added to Dairy & Eggs", or "Added 3 items" for a paste or last shop's items, with Undo. Undo takes back only what is still unbought.
  - The new row is lit for a moment where it sits; the list does not scroll away from the field.
  - For an item already on the list, the double-Enter guard stays (it doesn't add it twice). After **Add 1 more**, the message says "Milk: now 2 on your list".
  - Each add gives one message, so TalkBack announces once.
  - Messages clear the bottom bar, the field and its chips, and now also the keyboard. While the keyboard is up, the chips become one row that scrolls sideways, so a message fits below them.
- **Non-food items go to Household (AB.4).** Batteries, toothpaste, shampoo, cat food, washing powder, dishwasher salt, sun cream and similar items now land in Household instead of Other.
  - These rules come after every food rule, plus a few phrases that can't be food, so no food item moved. A test checks the golden ingredient names.
  - Golden data is untouched and the golden coverage threshold still passes.
- **Aisle moves on hand-added items are remembered (AB.5).** Moving an item you added by hand to another aisle is remembered without asking: "Moved to Household. HyperShop will remember." Undo moves it back and forgets the aisle.
  - A recipe's item keeps the "Always put X in Y" checkbox.
  - Tests show the remembered aisle survives finishing a shop and a backup restore.

## Journeys, before and after

`JourneyTest` drives the real app shell (Robolectric) and counts taps, entries typed, scrolls and screen changes. These are counts, not timings; no person took part.

| Journey | AA (docs/reports/step-aa.md) | AB.0 | After AB |
|---|---|---|---|
| Adding by hand: bin bags typed, toothpaste and kitchen roll (regulars) added | — | 7 · 1 · 0 · 1 | **4 · 1 · 0 · 0** |
| Core job: share a recipe link, accept, tick everything, finish | 6 · 0 · 0 · 2 | 6 · 0 · 0 · 2 | 6 · 0 · 0 · 2 |
| Quick shopping: milk and bread, tick both, finish | 7 · 2 · 0 · 0 | 7 · 2 · 0 · 0 | 7 · 2 · 0 · 0 |

(taps · typed · scrolls · screen changes)

All targets are met:
- Adding by hand: 4 taps and 1 typed entry (target: at most 5 and 1), with no screen change.
  - Before: tap the field, type "bin bags", Enter, then ⋮ › Buy again › chip › chip › Done, which opened the sheet.
  - Now: tap the field, type "bin bags", Enter, then two chips.
- The core and quick journeys are unchanged.
- In the emulator scenario, the "quick" weekly shop drops from 9 taps to 8: the field, then last shop's chip, instead of Buy again's chip, Add all and Done.

## Where things typed into Add item land

| Item | AB.0 | After AB |
|---|---|---|
| bin bags | Household (catalogue) | Household (catalogue) |
| batteries | **Other** | Household |
| toothpaste | **Other** | Household |
| kitchen roll | Household (catalogue) | Household (catalogue) |
| washing-up liquid | Household (catalogue) | Household (catalogue) |
| cat food | **Other** | Household |
| shampoo | **Other** | Household |
| foil | Household (catalogue) | Household (catalogue) |

`HandAisleTest` writes this table to `build/reports/hand-aisles.txt` and asserts the "After" column.

## Claims in the brief that were wrong

- **"Pressing Enter adds a hand-added item."** True for a new item. For an item already on the list, a guard (review B.17) deliberately doesn't add it again; it says so and offers "Add 1 more". I kept the guard. "Milk: now 2 on your list" is shown after Add 1 more, which is when the list has actually changed.
- **Where the eight items land.** The brief implied all eight go to Other. Only four did; bin bags, kitchen roll, washing-up liquid and foil were already Household catalogue entries.
- **"Without asking Always / Just this time."** The list never asked that; the question is on the review. The list's item editor had an "Always put X in Y" checkbox, ticked by default. For hand-added items that checkbox is now gone and the move is always remembered.
- **"The same rule as AA.4" for message placement.** AA.4's rule measured only the bar's height. With the app drawn edge-to-edge, the Scaffold placed messages on the tab bar, which the keyboard covers, so messages shown while typing were hidden. The rule now measures where the bar's top is and also clears the keyboard.

## Not done, and why

- Without the sheet, a new **pin** ("Always offer") can't be set, and a single hidden item can't be unhidden. Existing pins still come first. Settings › Clear shopping history still clears every hidden item; its dialog no longer mentions Buy again.
- The empty list's "Last shop's N items" button stays: on an empty list the field isn't focused, so its chips aren't showing.
- Items already on a list keep the aisle they were given. AB.4 affects only new adds.
- I kept AB.4's single-word Household rules away from words that can name food: bulb (fennel bulb), sponge (sponge cake), polish (Polish sausage), dog (hot dog), spray (cooking spray), paper (rice paper), liquid (liquid smoke) and bag (tea bags). "Light bulbs", "dog food" and similar are caught as whole phrases instead.

## What could not be checked here

- **Keyboard.** Robolectric raises no keyboard. These were not seen on a device: the one-row chips while the keyboard is up, the message sitting above the keyboard, Enter keeping the keyboard up after a chip tap, and the keyboard's own microphone (voice typing into the field). The code follows the keyboard's inset; the tests cover the layout with no keyboard and the one-row layout directly.
- **Emulator and device.** The emulator scenarios were updated and compiled here, and ran in CI on the PR. The first run caught two mistakes in this step's tests, both now fixed:
  - the quick weekly shop takes 8 taps, not 7 (I forgot the Recipes tab);
  - `a_quickShop` ticked a row that the last add's message covered, because with the keyboard up the message sits just above it. The test now waits the message out, uncounted.
- **TalkBack.** The chips' "Don't suggest" action and the single message per add are tested through semantics only.
- **Watch.** Not run. The watch protocol and its data are unchanged; hand adds reach the watch exactly as typed items did before.

One commit per sub-step (AB.0–AB.6), each gated by `tools/verify.sh` (watch compile skipped; argument limits ran). Lines changed since 31ba91e: main sources +438 / −386, tests +589 / −283.
