# t-2026.json — sources

A third held-out set for the ingredient pipeline, gathered after the final
quality pass so that it has never been used to tune either parser. 135 lines,
all real, read from the JSON-LD `recipeIngredient` array of 10 recipe pages on
2026-10-23 (HTML entities unescaped, tags stripped, surrounding whitespace
stripped, inner spacing kept). None of these pages is in `release-c-2026.json`
or `final-2026.json`; no method text or other page content was kept. Two
groups: `uk` (58 lines, 5 pages) and `us` (77 lines, 5 pages). There is no
synthetic group.

**Order of work, so the labels are independent:** the pages were fetched, the
lines labelled by reading them, and the file written before any parser — the
rule parser or Claude — was run on a single line of it. The parser's source was
not read while labelling, no test or Gradle task was run, and no Claude call
was made for it.

## Conventions

Those of `release-c-2026-SOURCES.md` and `final-2026-SOURCES.md`:

- `name` uses the UK term where the spec's synonym table has one (cilantro →
  coriander, baking soda → bicarbonate of soda, all-purpose → plain flour,
  scallion → spring onion, tomato paste → tomato purée, broth → stock); other
  US product names are kept as written with the UK equivalent in `notes`
  ("heavy cream", "half and half", "extra-sharp cheddar", "tomato sauce").
  Countable names are singular (`egg`, `onion`, `banana`); size words (large,
  small) and brands are dropped; kosher/sea/fine sea salt → `salt`.
- Multi-ingredient lines ("salt and pepper", a colon list of toppings) are
  labelled with the first ingredient, the rest in `notes`.
- The first amount of a dual amount is the label ("1/2 cup (1 stick)",
  "6 cups … (16 oz)"); repeating fractions are rounded to 4 dp (⅓ → 0.3333).
- `pack_size` only for a container size: "2 x 400g cans" → 2 tin, "400 g";
  "400g tin" with no count → 1 tin; "1 (14.5-oz) can" → 1 tin, "14.5 oz".
- A leading measure word with no number is not an amount ("handful dried
  banana chips" → no quantity, as rc-021); "a good pinch" is 1 pinch.
- "to serve", "for serving", "for topping", "for decoration" set `garnish`;
  "(optional)", "optional for serving" and "optional toppings:" set `optional`.
- `prep` holds the dropped preparation phrase, as in `release-c-2026.json`.
- **"plus" lines** (7, each with a `notes` entry starting `plus:`): amounts are
  added only when both are plainly more of the same ingredient in the same
  unit ("1 large egg, plus 1 egg yolk" → 2 eggs, a yolk being an egg to the
  shopper, as rc-096). An unquantified extra ("plus extra for the tin", "plus
  more to taste") is not added. A second amount in another unit ("1 Tbsp plus
  1/2 tsp fine sea salt") is not added, as rc-196. No line needed the
  `plus: ambiguous` reading.

Calls this file makes that the earlier ones had no exact example of, recorded
in `notes`: "10g piece of ginger" is 10 g (1 piece of 10 g also defensible);
"an 8-ounce block of … cheddar" is 8 oz (there is no block unit); "1 cup mashed
… bananas (from 2 to 3 large bananas)" is 1 cup; "400g minced meat (I like half
beef and half pork …)" is `mince` with no alternatives; "1/2 cup reserved
pasta water" is kept as `pasta water` though there is nothing to buy.

## Sources

| key | group | site | URL | fetched | lines |
|---|---|---|---|---|---|
| bbcgf-t1 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/brilliant-banana-loaf (requested …/recipes/banana-bread, redirected) | 2026-10-23 | 8 |
| bbcgf-t2 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/meatball-black-bean-chilli | 2026-10-23 | 12 |
| bbcgf-t3 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/speedy-lentil-coconut-curry | 2026-10-23 | 13 |
| jamie-t1 | uk | Jamie Oliver | https://www.jamieoliver.com/recipes/beef/one-pan-moussaka/ | 2026-10-23 | 16 |
| olive-t1 | uk | Olive Magazine | https://www.olivemagazine.com/recipes/family/tuna-pasta-bake/ | 2026-10-23 | 9 |
| once-t1 | us | Once Upon a Chef | https://www.onceuponachef.com/recipes/chicken-paprikash.html | 2026-10-23 | 15 |
| once-t2 | us | Once Upon a Chef | https://www.onceuponachef.com/recipes/banana-bread.html | 2026-10-23 | 10 |
| skinny-t1 | us | Skinnytaste (WPRM blog) | https://www.skinnytaste.com/chicken-enchiladas/ | 2026-10-23 | 23 |
| pinch-t1 | us | Pinch of Yum (WPRM blog) | https://pinchofyum.com/broccoli-cheese-soup | 2026-10-23 | 14 |
| natasha-t1 | us | Natasha's Kitchen (WPRM blog) | https://natashaskitchen.com/baked-mac-and-cheese/ | 2026-10-23 | 15 |

Total: 135 lines from 10 pages.

## Sites that refused or could not be used (2026-10-23)

| site | URL tried | result |
|---|---|---|
| Simply Recipes | https://www.simplyrecipes.com/recipes/banana_bread/ | HTTP 402 (People Inc. access block) |
| Well Plated | https://www.wellplated.com/healthy-banana-bread/ | redirected to an image file; no recipe |
| Guessed slugs (BBC Good Food, Olive, Jamie Oliver, BBC Food, Pinch of Yum, Natasha's Kitchen) | — | HTTP 404; replaced by pages found on each site's own index pages |

## Label corrections

None yet.
