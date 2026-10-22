# release-c-2026.json — sources

Independent evaluation set for the ingredient parsers (Release C). 285 lines:
267 fetched from real recipe pages on 2026-10-22 plus an 18-line synthetic
group. Only the ingredient lines, the page URL, the site and the fetch date
were kept; no method text or other page content was copied.

**Labels were written by reading each line by hand. No parser (rule parser or
Claude) was run or consulted while labelling; the labels are independent of
the app's output.** Where `docs/parser-spec.md` gives a reading it was
followed (e.g. "juice of 1 lemon" → lemon, 1; "400 g tin X" → 1 tin,
pack_size "400 g"; kosher/table/flaky salt → salt); where it is silent the
reading a careful UK shopper would want was chosen and recorded in `notes`.

Conventions used in this file's labels:

- `name` uses the UK term when the spec's synonym table (R5.2) has one
  (cilantro → coriander, baking soda → bicarbonate of soda, ground beef → beef
  mince, all-purpose → plain flour, green onion → spring onion, tomato paste →
  tomato purée). Other US/Australian product names are kept as written with
  the UK equivalent in `notes` (e.g. "thickened cream", "ladyfingers").
- Brands are dropped from `name`. "egg whites"/"egg yolks" → `egg` (what the
  shopper buys), noted. "cloves" (the spice) is kept plural so it is not
  mistaken for the garlic unit.
- Lines that carry several ingredients ("salt and pepper", "1 tsp each salt,
  cumin, …") are labelled with the first ingredient and the others are listed
  in `notes`.
- Repeating fractions are rounded to 4 dp (⅓ → 0.3333, ⅔ → 0.6667).
- Two deliberate deviations from the task's unit list, both flagged in
  `notes`: `quart` is used once (rc-209, it is a spec R2 unit); Scandinavian
  decilitres (nordic-1) are converted exactly to `ml` (2 dl → 200 ml).
- One line (rc-079, "TIKKA MASALA PASTE") is a section header that the site
  ships inside its `recipeIngredient` array; its `name` is empty and the
  expected output is no ingredient (spec R8).
- `prep` holds the dropped preparation phrase; `pack_size` is only set when a
  container size is given ("400 g", "16 oz").

All pages were fetched with curl (desktop browser user agent) and the lines
were read from the page's JSON-LD `recipeIngredient` array (HTML entities
unescaped, surrounding whitespace stripped, inner spacing kept as-is).

## Fetched sources

| key | group | site | URL | fetched | lines |
|---|---|---|---|---|---|
| bbcgf-1 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/chicken-tikka-masala | 2026-10-22 | 10 |
| bbcgf-2 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/classic-lasagne | 2026-10-22 | 11 |
| bbcgf-3 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/easy-chocolate-cake | 2026-10-22 | 7 |
| bbcgf-5 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/slow-cooker-beef-stew | 2026-10-22 | 9 |
| bbcgf-6 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/ratatouille | 2026-10-22 | 7 |
| bbcgf-7 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/eton-mess | 2026-10-22 | 4 |
| bbcfood-1 | uk | BBC Food | https://www.bbc.co.uk/food/recipes/easy_spaghetti_bolognese_93639 | 2026-10-22 | 7 |
| bbcfood-5 | uk | BBC Food | https://www.bbc.co.uk/food/recipes/chipotle_chicken_with_18023 | 2026-10-22 | 12 |
| jamie-1 | uk | Jamie Oliver | https://www.jamieoliver.com/recipes/chicken/chicken-tikka-masala/ | 2026-10-22 | 16 (incl. 1 section header) |
| jamie-2 | uk | Jamie Oliver | https://www.jamieoliver.com/recipes/vegetables/aubergine-parmigiana/ | 2026-10-22 | 7 |
| gbc-1 | uk | Great British Chefs | https://www.greatbritishchefs.com/recipes/shepherds-pie-recipe | 2026-10-22 | 8 |
| sains-1 | uk | Sainsbury's Magazine | https://www.sainsburysmagazine.co.uk/recipes/mains/chicken-tikka-masala | 2026-10-22 | 18 |
| olive-4 | uk | Olive Magazine | https://www.olivemagazine.com/recipes/baking-and-desserts/classic-chocolate-brownies/ | 2026-10-22 | 5 |
| kingarthur-1 | us | King Arthur Baking | https://www.kingarthurbaking.com/recipes/classic-sandwich-bread-recipe | 2026-10-22 | 6 |
| kingarthur-2 | us | King Arthur Baking | https://www.kingarthurbaking.com/recipes/chewy-chocolate-chip-cookies-recipe | 2026-10-22 | 8 |
| bonapp-1 | us | Bon Appétit | https://www.bonappetit.com/recipe/bas-best-chocolate-chip-cookies | 2026-10-22 | 8 |
| epi-3 | us | Epicurious | https://www.epicurious.com/recipes/food/views/my-favorite-simple-roast-chicken-231348 | 2026-10-22 | 4 |
| pinchofyum-1 | us | Pinch of Yum (WPRM blog) | https://pinchofyum.com/the-best-soft-chocolate-chip-cookies | 2026-10-22 | 7 |
| onceupon-1 | us | Once Upon a Chef | https://www.onceuponachef.com/recipes/beef-stew-with-carrots-potatoes.html | 2026-10-22 | 9 |
| wellplated-1 | us | Well Plated (WPRM blog) | https://www.wellplated.com/crockpot-chicken-tacos/ | 2026-10-22 | 7 |
| pioneer-1 | us | The Pioneer Woman | https://www.thepioneerwoman.com/food-cooking/recipes/a11728/best-lasagna-recipe/ (requested …/a11728/chicken-pot-pie/, redirected) | 2026-10-22 | 10 |
| skinny-1 | us | Skinnytaste (WPRM blog) | https://www.skinnytaste.com/turkey-chili-stuffed-acorn-squash/ (requested …/turkey-chili/, redirected) | 2026-10-22 | 7 |
| delish-1 | us | Delish | https://www.delish.com/cooking/recipe-ideas/a19665918/oven-baked-tilapia-recipe/ (requested …/best-chicken-pot-pie-recipe/, redirected) | 2026-10-22 | 7 |
| tasteofhome-1 | us | Taste of Home | https://www.tasteofhome.com/recipes/basic-homemade-bread/ | 2026-10-22 | 6 |
| giallo-2 | eu | GialloZafferano (Italy, English edition) | https://www.giallozafferano.com/recipes/Lasagne-Bolognese.html | 2026-10-22 | 12 |
| giallo-3 | eu | GialloZafferano (Italy, English edition) | https://www.giallozafferano.com/recipes/Tiramisu.html | 2026-10-22 | 4 |
| gic-1 | eu | Great Italian Chefs (UK site, Italian dish) | https://www.greatitalianchefs.com/recipes/tiramisu-recipe | 2026-10-22 | 6 |
| nordic-1 | eu | Nordic Food & Living (Denmark, English) | https://nordicfoodliving.com/danish-rye-bread-rugbrod/ | 2026-10-22 | 6 |
| daring-2 | eu | The Daring Gourmet (German recipe, WPRM) | https://www.daringgourmet.com/authentic-german-sauerbraten/ | 2026-10-22 | 10 |
| recipetin-1 | blog | RecipeTin Eats (WPRM) | https://www.recipetineats.com/chicken-tikka-masala/ | 2026-10-22 | 12 |
| cafedelites-1 | blog | Cafe Delites (WPRM) | https://cafedelites.com/butter-chicken/ | 2026-10-22 | 8 |
| minimalist-1 | blog | Minimalist Baker (WPRM) | https://minimalistbaker.com/easy-vegan-fried-rice/ | 2026-10-22 | 9 |

Fetched total: 267 lines from 32 pages.

## Synthetic group

| key | group | description | lines |
|---|---|---|---|
| synthetic | synthetic-paste | **Not fetched.** 18 informal shopping-list lines written by hand on 2026-10-22 to imitate text pasted from a notes app ("milk", "2x bread", "bananas x6", "loo roll", "chicken thighs 1kg", …). Every entry's `notes` starts with "synthetic:". | 18 |

## Sites that refused or could not be used (2026-10-22)

| site | URL tried | result |
|---|---|---|
| Allrecipes | https://www.allrecipes.com/recipe/10813/best-chocolate-chip-cookies/ | HTTP 402 (People Inc. access block) |
| Serious Eats | https://www.seriouseats.com/the-best-chili-ever-recipe | HTTP 402 (People Inc. access block) |
| Simply Recipes | https://www.simplyrecipes.com/recipes/homemade_chicken_noodle_soup/ | HTTP 402 (People Inc. access block) |
| Food Network | https://www.foodnetwork.com/recipes/ree-drummond/chicken-pot-pie-recipe-2107654 | HTTP 403 |
| Sally's Baking Addiction | https://sallysbakingaddiction.com/chewy-chocolate-chip-cookies/ | HTTP 403 (bot challenge) |
| Budget Bytes | https://www.budgetbytes.com/dragon-noodles/ | HTTP 403 (bot challenge) |
| Smitten Kitchen | three recipe URLs | HTTP 429 on every attempt |
| Food52 | two recipe URLs | HTTP 429 |
| delicious. magazine | two recipe URLs | HTTP 403 (bot challenge) |
| Tesco Real Food | two recipe URLs | HTTP 403 |
| Waitrose | two recipe URLs | HTTP/2 stream error, no response |
| The Kitchn, Cookie and Kate, Gimme Some Oven, Half Baked Harvest | one URL each | HTTP 403 (bot challenge) |
| ScandiKitchen | https://www.scandikitchen.co.uk/recipe/swedish-meatballs/ | HTTP 520 |
| SwedishFood.com | https://www.swedishfood.com/swedish-food-recipes-main-courses/190-swedish-meatballs | HTTP 403 |
| NYT Cooking | https://cooking.nytimes.com/recipes/1017089-roasted-chicken-provencal | HTTP 404 |
| Sainsbury's Magazine (2nd page), Inspired Taste | 200 but no JSON-LD recipe block; not used | — |
| Various guessed slugs (Olive, BBC Food, Epicurious, Bon Appétit, Jamie Oliver, GialloZafferano, Arla, Love & Lemons, Natasha's Kitchen, German recipe sites) | — | HTTP 404; replaced by the pages listed above |

## Label corrections after the baseline (Release C, step C.1)

The baseline report (docs/reports/release-c-extraction-baseline.md) was scored on the
labels as first written. Six labels were then corrected where they contradicted
docs/parser-spec.md or the catalogue's own reading, each with the reason in the
entry's `notes`; nothing else was changed, and no label was changed to match a
parser output:

| id | was | now | why |
|---|---|---|---|
| rc-012, rc-029 | unit `stick` | no unit (a count) | "celery stick" is the catalogue's synonym for one celery (R5.2) |
| rc-021 | quantity 1 `other` | no quantity | R2.4: a leading measure word with no number is not an amount |
| rc-110 | name `cinnamon`, unit `stick` | name `cinnamon stick`, no unit | a cinnamon stick is the product sold; "2cm" is its size |
| rc-188 | no unit | unit `fillet` | R2 lists `fillet` as a count unit |
| rc-280 | name `pepper` | name `bell pepper` | "peppers" on a shopping list are the vegetable |
