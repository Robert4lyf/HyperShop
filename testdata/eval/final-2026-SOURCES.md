# final-2026.json — sources

A second, smaller held-out set for the ingredient pipeline, gathered for the
final quality pass (docs/reports/quality-pass.md). 137 lines, all real, read
from the JSON-LD `recipeIngredient` array of 10 recipe pages on 2026-10-23
(HTML entities unescaped, tags stripped, surrounding whitespace stripped, inner
spacing kept). None of these pages is in `release-c-2026.json`; no method text
or other page content was kept. There is no synthetic group.

**Order of work, so the labels are independent:** the pages were fetched, the
lines labelled by reading them, and the labels committed (commit `[Q.2a]`)
before any parser — the rule parser or Claude — was run on a single line of
this file. No Claude call was made for it.

The labelling conventions are those of `release-c-2026-SOURCES.md` (UK names
where the spec's synonym table has one, otherwise as written with the UK name
in `notes`; multi-ingredient lines labelled with the first; `pack_size` only
for a container size; first amount of "600 g / 1.2 lb"). Two calls this file
makes that the earlier one had no example of, recorded in `notes`:
"2 tbsp butter plus 1 tbsp melted" is 3 tbsp (the shopper needs both), and
"500g pack lamb mince" is 1 packet of 500 g, as "400g tin" is 1 tin.

Label corrections after the first run are listed below with their reason;
none was made to improve a score.

## Sources

| key | group | site | URL | fetched | lines |
|---|---|---|---|---|---|
| bbcgf-b1 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/beef-bourguignon | 2026-10-23 | 14 |
| bbcgf-b2 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/best-ever-macaroni-cheese-recipe | 2026-10-23 | 9 |
| bbcgf-b3 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/easy-pancakes | 2026-10-23 | 6 |
| bbcgf-b4 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/chilli-con-carne-recipe | 2026-10-23 | 16 |
| bbcgf-b5 | uk | BBC Good Food | https://www.bbcgoodfood.com/recipes/shepherds-pie | 2026-10-23 | 10 |
| recipetin-b1 | blog | RecipeTin Eats (Australia, WPRM) | https://www.recipetineats.com/beef-stroganoff/ | 2026-10-23 | 14 |
| recipetin-b2 | blog | RecipeTin Eats (Australia, WPRM) | https://www.recipetineats.com/fettuccine-alfredo/ | 2026-10-23 | 9 |
| recipetin-b3 | blog | RecipeTin Eats (Australia, WPRM) | https://www.recipetineats.com/lasagna/ | 2026-10-23 | 24 |
| minimalist-b1 | us | Minimalist Baker (WPRM) | https://minimalistbaker.com/1-pot-chickpea-shakshuka/ | 2026-10-23 | 21 |
| lovelemons-b1 | us | Love and Lemons | https://www.loveandlemons.com/quinoa-salad-recipe/ | 2026-10-23 | 14 |

## Label corrections

None yet.
