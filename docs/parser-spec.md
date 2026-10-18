# Ingredient parser spec

One output shape for both parsers: the offline **rule parser** (`:core`,
Step 2) and **Claude** (`:claude`, Step 4, strict JSON schema). Golden data
(`testdata/ingredients/`) is written in this shape. Rules are numbered so
that `tools/nyt-to-golden` overrides and `CHANGES.md` entries can cite them
(`R3.2` etc.).

## R1 — Output schema

One `ParsedIngredient` per ingredient line.

| Field | Type | Meaning |
|---|---|---|
| `raw` | string | The source line, unchanged. |
| `name` | string | What to buy, lower case, singular when R5.4 applies. Identity words kept (R4.2), prep words removed (R4.1). |
| `quantity` | number \| null | The amount (lower bound of a range). `null` = no quantity (R6). |
| `quantity_max` | number \| null | Upper bound of a range, else `null`. |
| `quantity_text` | string \| null | The amount as written (`"2 ½"`, `"1-2"`). |
| `unit` | `QtyUnit` \| null | R2. `null` for a bare count ("2 onions"). |
| `alt_quantity` | number \| null | A parenthetical alternative amount (R3.4), e.g. the `300` in "2 cups (300g)". |
| `alt_unit` | `QtyUnit` \| null | Its unit. |
| `note` | string \| null | Prep words, sizes, comments; `null` when empty. |
| `section` | string \| null | The section header this line sits under ("For the sauce"). |
| `optional` | bool | "optional", "(optional)", "if desired", "if you like". |
| `estimated` | bool | Amount was inferred (Claude only; v1.1 media). Rule parser: always `false`. |
| `aisle_suggestion` | `AisleCode` \| null | Claude only; the categoriser's 4th source (PLAN §3.4). |

Derived flags (computed in Kotlin from the fields above, for both parsers):

| Flag | Rule |
|---|---|
| `garnish` | `note` or `raw` contains "garnish", "to serve", "for serving", "to decorate". |
| `noScale` | `quantity == null`, or `raw` contains "to taste", "for frying", "for greasing", "for dusting", "as needed". |
| `check` | Rule parser only: the line matched an unhandled pattern (R7). Highlighted in review. |

Numbers are decimals in JSON; Kotlin holds them as `Rational` (unicode and
slash fractions are exact: `⅓` is 1/3, not 0.333).

## R2 — `QtyUnit`

Serialised lower case (Claude's enum and the golden JSON use these strings).

| Dimension | Units (serial name: accepted spellings) |
|---|---|
| mass | `g`: g, gram(s), gr · `kg`: kg, kilo(s), kilogram(s) · `oz`: oz, ounce(s) · `lb`: lb, lbs, pound(s) |
| volume | `ml`: ml, millilitre(s), milliliter(s) · `l`: l, litre(s), liter(s) · `tsp`: tsp, t, teaspoon(s) · `tbsp`: tbsp, tbs, tbl, T, tablespoon(s) · `cup`: cup(s), c · `fl_oz`: fl oz, fl. oz., fluid ounce(s) · `pint`: pint(s), pt · `quart`: quart(s), qt |
| count | `clove`, `bunch`, `tin` (tin, can), `packet` (packet, package, pack, sachet, envelope), `sprig`, `slice`, `head`, `stalk` (stalk, stick of celery when followed by celery), `fillet`, `piece`, `stick` (butter, cinnamon) |
| small | `pinch`, `dash` |
| — | `other`: a unit word that is none of the above (handful, bottle, ear, leaf…). The rule parser knows a fixed list (R2.4) and marks those lines `check`. |

- **R2.1 Case-sensitive spoons.** `T` and `Tbsp`-like capital T alone = tbsp; lower-case `t` alone = tsp. All other unit words are case-insensitive.
- **R2.2 Locale factors** (for `QtyBase`, PLAN §3.3): cup = 240 ml (US) or 250 ml (metric); pint = 473 ml (US) or 568 ml (UK); quart = 946 ml (US) or 1137 ml (UK); fl oz = 29.57 ml (US) or 28.41 ml (UK); tbsp = 15 ml; tsp = 5 ml; oz = 28.35 g; lb = 453.6 g. A recipe's `unitLocale` is `US` unless it uses metric masses/volumes (g, kg, ml, l), or the source is a UK site, in which case `METRIC`/`UK`.
- **R2.4 Other units (rule parser):** ear, leaf/leaves, bag, strip, rectangle, handful, bottle, jar, sheet, drop, loaf, bulb, splash, cube, square, wedge, stem, branch, pod, punnet, knob, rasher, sachet-like containers not in R2. Size words between the number and the unit go to `note` ("2 medium cloves garlic" → 2 clove, note medium). With no number, a leading unit word (and a following "of") goes to `note` and the quantity stays `null` — except R3.3's pinch, dash, bunch, sprig and head.
- **R2.3 Dimensions.** `MASS` (base g), `VOLUME` (base ml), `COUNT` (base: one of the unit, or one item when `unit == null`). Pinch and dash are `COUNT` (never converted).

## R3 — Quantities

- **R3.1** Integers, decimals (`1.5`, `1,5` with a comma before exactly one or two digits), slash fractions (`1/2`), mixed numbers (`1 1/2`, `1-1/2`), unicode vulgar fractions (`½ ⅓ ⅔ ¼ ¾ ⅕ ⅛` …), and a digit glued to a unicode fraction (`1½`).
- **R3.2 Ranges**: `1-2`, `1 – 2`, `1 to 2`, `1 or 2` (between two numbers) → `quantity` 1, `quantity_max` 2.
- **R3.3** Number words `a`/`an` before a unit (= 1), `one`…`twelve`, `half`, `dozen` (= 12). A line that *starts* with a unit word and has no number ("Pinch of cayenne", "Strip of orange peel" is not — strip is not a unit) has quantity 1.
- **R3.4 Parenthetical alternatives**: a parenthesised amount with a unit right after the primary amount/unit (`2 cups (300g)`, `1 (14-ounce) tin`) is `alt_quantity`/`alt_unit`. For "`N (size-unit) container`" the container is the unit and the size goes to alt: `1 (400g) tin tomatoes` → qty 1 tin, alt 400 g.
- **R3.5** `x` multipliers (`2 x 400g tins`) → qty 2, unit tin, alt 400 g. With no container (`4 x 150g salmon fillets`) → qty 4, no unit (a count), alt 150 g, name `salmon fillets`.
- **R3.6** "plus 2 tbsp", "and 1 tsp" after the first amount → the extra amount goes into `note` (not added).

## R4 — Name vs note

- **R4.1 Prep words go to `note`.** Verbs/participles and their adverbs: chopped, diced, minced, sliced, grated, shredded, crushed, sifted, peeled, cored, seeded, deseeded, pitted, halved, quartered, cubed, trimmed, rinsed, drained, beaten, whisked, melted, softened, room-temperature, cooked, toasted (when describing prep of a bought item: "toasted almonds" stays — see R4.2), thawed, defrosted, julienned, torn, squeezed, zested, finely, roughly, coarsely, thinly, lightly, freshly, divided. Sizes: small, medium, large, extra-large, medium-size, big, thick, thin. Qualifiers: fresh (when the dried form is not meant), about, approximately, heaped, heaping, level, scant, good, good-quality, high-quality, generous, ripe, plus more. Everything after the first top-level comma is `note`, except a comma that follows only adjectives such as boneless, skinless, ripe, fresh, large (and no catalogue name): "boneless, skinless chicken breasts" reads as one name.
- **R4.2 Identity words stay in `name`.** They change what you buy: ground, smoked, red, green, yellow, white, black, brown, dried, dry (wine), tinned, canned, frozen, powder/powdered, unsalted, salted, low-fat, whole (milk), skimmed, semi-skimmed, double, single (cream), plain, self-raising, strong (flour), caster, icing, granulated, light/dark (sugar, soy), sweet, hot, extra-virgin, virgin, toasted (sesame oil), roasted (peppers in a jar), baby (spinach), spring (onion), boneless, skinless, free-range, kosher (salt, folded per R5.3), sea (salt).
- **R4.3** A parenthetical that is not an amount (R3.4) goes to `note`: `1 onion (about 200 g)` → note "about 200 g". Exception: `(optional)` sets `optional` and is dropped.
- **R4.4** "or" between two ingredients ("butter or margarine") keeps one ingredient: `name` is the first, `note` gets "or margarine". Rule parser: R7.
- **R4.5** "of" after a unit is dropped: "a pinch of salt" → unit pinch, name salt.

## R5 — Normalising (`:core` normaliser, shared by both parsers)

- **R5.1** Lower case; collapse whitespace; strip surrounding punctuation; straight quotes.
- **R5.2** Synonyms (`catalog/synonyms.csv`) map to a catalogue id: aubergine/eggplant, courgette/zucchini, coriander (leaf)/cilantro, spring onion/scallion/green onion, rocket/arugula, double cream/heavy cream, single cream/light cream, plain flour/all-purpose flour, caster sugar/superfine sugar, icing sugar/powdered sugar/confectioners' sugar, bicarbonate of soda/baking soda, prawn/shrimp, minced beef/ground beef/beef mince, chickpea/garbanzo bean, pepper (vegetable)/bell pepper/capsicum, mangetout/snow pea, swede/rutabaga, beetroot/beet, chilli/chili/chile.
- **R5.3 Folds:** kosher salt, sea salt, table salt, flaky salt, coarse salt → salt. Black pepper, freshly ground black pepper, ground black pepper → black pepper.
- **R5.4 Singular** head noun when the singular is in the catalogue (eggs → egg, tomatoes → tomato, leaves → leaf). Mass nouns stay (peas, oats, noodles if catalogued plural).
- **R5.5 `mergeKey`** = `cat:<catalogId>` when the normalised name (or its singular, or a synonym) is a catalogue name; else `name:<normName>`.

## R6 — No quantity

"salt and pepper to taste", "oil, for frying", "a few basil leaves": `quantity` `null` unless a number or R3.3 word is present ("a few" is not a quantity → null, note "a few"). `noScale` true. Stored as *presence* (PLAN §3.3).

## R7 — Rule parser limits (`check`)

The rule parser does **not** split "X and Y", resolve "A or B", or parse
"juice of 1 lemon"/"zest of". It marks those lines `check = true` and does its
best: for "juice of N lemon(s)" it returns name "lemon", quantity N, note
"juice". Claude splits "X and Y" into two ingredients (except fixed phrases
such as "salt and pepper" → two, "mac and cheese" → one).

## R8 — Section headers

A line is a section header when it ends with `:` or is entirely "For the …" /
upper case with no digits, and is ≤ 40 characters. It produces no ingredient;
following lines get `section` = the header text without the colon.

## R9 — `AisleCode`

`PRODUCE, BAKERY, MEAT_FISH, DAIRY_EGGS, CHILLED, FROZEN, TINS_JARS,
PASTA_RICE_GRAINS, WORLD_FOODS, BAKING, NUTS_DRIED_FRUIT, OILS_VINEGARS,
SPICES_CONDIMENTS, FREE_FROM, DRINKS, HOUSEHOLD, OTHER` — in that default
store order. Display names: Produce, Bakery, Meat & Fish, Dairy & Eggs,
Chilled, Frozen, Tins & Jars, Pasta/Rice/Grains, World Foods, Baking, Nuts &
Dried Fruit, Oils & Vinegars, Spices & Condiments, Free-from, Drinks,
Household, Other. Serialised as the enum name.

## R10 — NYT label mapping (`tools/nyt-to-golden`)

The NYT ingredient-phrase-tagger snapshot (2015, Apache-2.0) has columns
`input, name, qty, range_end, unit, comment`.

| NYT | Spec | Rule |
|---|---|---|
| `input` | `raw` | — |
| `name` | `name` (R5.1 applied; R4.2 identity words from `comment` moved back into `name` when they directly precede the name in `input`) | R4.2 |
| `qty` | `quantity` (`0` → `null`) | R6 |
| `range_end` | `quantity_max` (`0` → `null`) | R3.2 |
| `unit` | `unit` via the R2 spelling table; a size word in `unit` (large, medium, small) moves to `note`; other words → `other` | R2, R4.1 |
| `comment` | `note` (`null` when empty; parenthetical amounts after the unit become `alt_*`) | R3.4, R4.1 |
| — | `optional` = "optional" in `input` | R1 |
| — | `quantity_text`, `section`, `estimated=false`, `aisle_suggestion=null` | R1 |

Every manual change on top of this automatic mapping lives in
`tools/nyt-to-golden/overrides.json` with the rule it applies.

## R11 — Scoring (Steps 2.Check and 4.6)

- **name**: equal after R5.1 + R5.3 + R5.4 (singularised, folded), or equal `mergeKey`.
- **qty**: `quantity` and `quantity_max` within 1% (or both null), and `unit` equal.
- **unit**: `unit` equal (null = null).
- **note**: not scored (informational).
- **Strata** (assigned by `tools/pick-golden`, first match wins):
  1. `no_qty` — NYT `qty == 0`, or `input` contains "to taste" and does not start with a digit.
  2. `fractions_ranges` — `input` has a slash or unicode fraction, a decimal point, or `range_end > 0`.
  3. `comments_parens` — `comment` non-empty, or `input` contains `(`, `,`, `;`, " or " or " and ".
  4. `simple` — everything else.
- **Sanity filter** before sampling: input 3–120 characters, no HTML or
  backslash, NYT name at least 3 characters and appearing verbatim
  (case-insensitive) in the input, no duplicate inputs.
