# UI overhaul (after Hand-off A)

Goal: a modern, distinctive look that stays as clear as possible in a shop:
one hand, quick glances, bright light. Behaviour does not change. Every
action, test tag, TalkBack label and rule in CLAUDE.md stays as it is.

## Principles

1. **One glance, one answer.** Each screen leads with its main fact: how many
   items are left, which recipe this is, what will be added.
2. **Aisles are the map.** Every aisle has a colour and an icon, used the same
   way on every screen, so you spot "Dairy" before you read it.
3. **Big targets, few words.** Rows are at least 56 dp high and the tick is the
   first thing your thumb meets. Secondary actions move into sheets and menus.
4. **Calm surfaces, one accent.** Warm off-white (near-black at night),
   white cards, basil green for actions and progress, tomato red only for
   things that need checking.
5. **State is never colour alone.** Ticked rows are struck through and carry
   a tick. Items to check have an icon as well as a colour. In-stock rows are
   struck through.

## Design system (`ui/theme/`)

| Token | Choice |
|---|---|
| Typeface | **Plus Jakarta Sans** (SIL OFL), 400–800, bundled in `res/font`, Latin and Latin Extended. Missing glyphs (⅓, ✓) fall back to the system font. |
| Colour | Own light and dark schemes (basil primary, sage secondary, tomato tertiary, a warm neutral surface ladder). Dynamic colour is **off** by default, so the app always looks like itself. All text pairs ≥ 4.5:1. |
| Aisle colours | One hue per aisle, giving a soft container and a strong icon colour in each theme (`AisleStyle`). |
| Aisle icons | One Material icon per aisle: Produce 🌿, Bakery 🥐, Meat & Fish 🐟, Dairy & Eggs 🥚, Chilled (fridge), Frozen ❄, Tins & Jars, Pasta/Rice/Grains 🍚, World Foods 🌍, Baking 🎂, Nuts & Dried Fruit, Oils & Vinegars 💧, Spices & Condiments 🔥, Free-from, Drinks 🥤, Household 🧽, Other. |
| Shapes | 8 / 12 / 16 / 24 / 32 dp. Cards are 24 dp, sheets 32 dp, buttons and fields are pills. |
| Spacing | 4-point scale: 4, 8, 12, 16, 20, 24, 32. Screen gutter 16 dp. |
| Elevation | Flat. Cards use a lighter surface, not shadows. Only the review screen's bottom bar is raised. |

Shared components (`ui/common/`):
- `AisleBadge`: a rounded square with the aisle icon on its colour.
- `SectionCard` / `Modifier.sectionItem(position)`: groups lazy-list items
  into one rounded card (first, middle, last, only), so an aisle reads as one
  block while each row stays its own lazy item.
- `ScreenHeader`: the large title, an optional overline and subtitle, and actions.
- `TickMark`: a round 28 dp check for open, partly ticked and done.
- `InfoBanner`: an icon and text on a tinted container (notices, errors, privacy).
- `EmptyHero`: a large icon in a circle, a headline, then steps.

## Screens

### Shell
- Navigation bar on a raised neutral surface. Filled icon when selected,
  outlined when not; labels stay.
- Snackbars use the inverse surface with a basil "Undo".

### List
- **Header:** the "Shopping list" overline, then "**5 left**" large and bold,
  and "1 of 6 ticked" with a rounded progress bar. When anything is ticked,
  "Finish shopping" is a tonal pill with a check icon. Share and Clear all
  stay in the menu.
- **Add item:** a pill-shaped field with a + icon. Suggestions appear in a
  card under it. "Paste link" is a chip with a clipboard icon.
- **Ready to review:** a green card with a recipe icon, "2 recipes ready to
  review", "Check the ingredients before they go on the list" and a chevron.
- **Aisles:** each aisle is a card. Its header has the badge, the name and a
  "2 left" count, or "Done ✓" in green when everything is ticked.
- **Rows:** the round tick, then the item. A row in progress (partly
  ticked) shows a half-filled tick. A ticked row is struck through and
  greyed. A chevron opens the details. Swiping shows a red rounded panel
  with a bin icon and "Delete".
- **Empty:** a basket icon in a green circle, "Your list is empty", two step
  cards (share a recipe, then shop aisle by aisle) with icons, then
  "Try a sample list" and "Paste link".

### Item sheet
- The aisle badge and the name, then "On the list" as a large amount.
- "From": each recipe on a row with its amount on the right; recipes with a
  page are links with an open-in-new icon.
- "Usually sold as …" with a shopping-bag icon.
- "Change amount" is a pill field with a tonal "Set" button.
- "Aisle" chips carry the aisle icons.

### Review
- **Top bar:** Close (×, "Cancel") on the left, a menu ("Clear in stock") on
  the right.
- **Recipe card:** the title (up to 3 lines); the site name as a link
  ("Open recipe"); the parser badge ("Parsed by Claude" with a sparkle,
  "Parsed offline", or "Claude pending" with "Retry with Claude"); and a
  servings stepper pill (− Serves 4 +).
- **Notice:** "List changed — review updated" as a tinted banner.
- **Aisles:** cards as on the list. The header menu has "Mark this aisle in stock".
- **Rows:** name and amount, then an "In stock" toggle pill (outlined, or
  filled with a check). A line to check gets a tomato warning icon and
  "Check this line". In-stock rows are struck through and greyed.
- **Bottom bar:** a full-width "Add 4 items" button, always visible, so the
  main action never scrolls away.

### Review row sheet
- Badge, name, and the effect ("milk: +200 ml → 700 ml on list") in a tinted card.
- Source lines as fields with "Read as …" under them, then tonal buttons.
- "I have some" as a pill field with "Subtract". "Always in stock" is a tonal button.
- Aisle chips as on the item sheet.

### Couldn't read the recipe (manual lines)
- An error banner with an icon, then "Select lines manually".
- The page's lines in a card with checkboxes, then a paste field.
- A bottom bar with "Use 3 lines" and "Back to the list".

### Recipes
- A large "Recipes" title with "3 on your list".
- One card per recipe: the title, the site with a link icon, chips for
  "Serves 4", "6 items" and "3 in stock", then actions: "Open recipe"
  (tonal), "Remove from list" (outlined) and "Add the in-stock items now".
- When there are none: a book icon, "No recipes on the list", and the share hint.

### Settings
- A large title. Each group (List, Aisles, Watch, Claude, Advanced) is a card
  with an icon and a title.
- **Units:** a segmented button. **Always in stock:** removable chips.
- **Aisles:** rows with the aisle badge and up/down buttons.
- **Claude:** the privacy note as a banner; the key status with ✓ or ✗
  icons; the model as radio rows with the cost line; spending as two
  progress bars (this month, today) plus the existing sentence; caps as pill
  fields.

## Review fixes (code review of the first cut)
- The list header is pinned, so "Finish shopping" and the menu stay in reach on a long list.
- Review: "Cancel" is a labelled text button (it discards the import; system Back keeps it ready).
  The badge row wraps so the busy spinner always shows; long titles show in full; the site link
  ends in "…" instead of clipping.
- Snackbars sit above the review screens' bottom bar, never over "Add N items".
- Fields keep a visible, spoken label once text is typed; the Add-item border is the full outline colour (3:1).
- Only the × removes a staple; the chip is no longer announced as a checkbox.
- Recipe card buttons wrap on narrow screens or large text.
- The launcher leaf sits inside the safe zone, and themed icons get a proper monochrome layer.
- Aisle styles are an exhaustive `when` (a new aisle won't compile without one); the theme
  provides `LocalDarkTheme` instead of guessing dark mode from colours.

## Colour pass (after phone testing: "sleek but dull")
- **Hero headers:** every tab's title sits on a basil-to-lagoon gradient card with two soft light circles. The text is white, and the overline and progress bar are citrus. "Finish shopping" is a white pill.
- **Aisles carry their colour:** the aisle header is tinted, the badge is solid with a light icon, and ticks fill in the aisle's colour.
- **Recipes have a hue:** each recipe gets a colour band picked from its title, and its review header uses the same gradient. Its fact chips are tinted to match.
- **Other screens:** "Ready to review" is a warm tomato-to-berry card. Each settings group has its own coloured icon tile. The empty state's circle uses the hero gradient.
- **Contrast:** white text on every gradient clears 4.5:1, and the gradients are darker in dark mode. State is still never shown by colour alone.

## Rows (after phone testing: accidental ticks)
- Only the round tick (a 56 dp target) ticks; the rest of the row opens the item card. TalkBack reads the tick as a checkbox named after the item.
- Swipe-to-delete is gone. Delete is the bin at the item card's top right, with Undo on the list.

## Adding a recipe (after phone testing)
- The Recipes tab has a + button. It opens a sheet with two choices:
  - **Paste a link:** the same path as sharing a page to the app.
  - **Type it in:** a name, an optional "serves" and the ingredients, one per line.
- A typed recipe is rule-parsed and opens the usual review screen. Claude is called only there, with a key, as for a shared page.

## Ingredient library (Settings)
- **Where:** Settings › Ingredients › Ingredient library lists every catalogue ingredient, with search.
- **Editing:** tap an ingredient to edit its name, plural, synonyms ("Also called"), aisle, how amounts are shown, usual pack and grams per cup, and to switch "Always in stock".
- **Adding:** "Add ingredient" adds your own.
- **Undoing:** "Reset to default" or "Delete" undoes an edit.
- **Storage:** edits live in `files/library/edits.json`, which is backed up. They are applied over the bundled catalogue at start-up, and the catalogue version includes them.
- **Saving:** a save re-keys the list, so an item whose name now matches merges into the ingredient. It also redraws every row, and moves an item already on the list to the new aisle.

## Not changing
- Navigation, view models, data, strings that tests or TalkBack depend on,
  the one-node-per-row semantics, and swipe-to-delete with its accessible
  equivalent.
- No new libraries: Material 3, its extended icons and Compose are already in
  the build. The only new files are the font and its licence.

## Order of work (one commit each)
1. Design system: font, colours, type, shapes, aisle styles, shared components.
2. Shell, list, list header and item sheet.
3. Review, row sheet and manual lines.
4. Recipes and settings.
5. Re-record the screenshots and contact sheet, check contrast and TalkBack,
   and update `docs/HANDOFF.md`.
