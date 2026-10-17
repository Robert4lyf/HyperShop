# Page fixtures

Captured once on 2026-10-17 with a mobile Chrome user agent and trimmed by
`tools/pages/trim.py` to the title, the Recipe JSON-LD (instructions,
descriptions, reviews and images removed) and the WPRM/Tasty ingredient
container. Nothing else from the page is kept.

| File | Source | Markup | src |
|---|---|---|---|
| bbcgoodfood-chocolate-cake.html | https://www.bbcgoodfood.com/recipes/easy-chocolate-cake | JSON-LD | live-2026-10-17 |
| bbcgoodfood-bolognese.html | https://www.bbcgoodfood.com/recipes/best-spaghetti-bolognese-recipe | JSON-LD | live-2026-10-17 |
| bbcgoodfood-chilli.html | https://www.bbcgoodfood.com/recipes/chilli-con-carne-recipe | JSON-LD | live-2026-10-17 |
| nytcooking-cookies.html | https://cooking.nytimes.com/recipes/1015819-chocolate-chip-cookies | JSON-LD | live-2026-10-17 |
| bonappetit-cookies.html | https://www.bonappetit.com/recipe/bas-best-chocolate-chip-cookies | JSON-LD | live-2026-10-17 |
| delish-pot-pie.html | https://www.delish.com/cooking/recipe-ideas/a19665918/classic-chicken-pot-pie-recipe/ | JSON-LD | live-2026-10-17 |
| pioneerwoman-potato-soup.html | https://www.thepioneerwoman.com/food-cooking/recipes/a8865/perfect-potato-soup/ | JSON-LD | live-2026-10-17 |
| tasty-pinchofyum-cookies.html | https://pinchofyum.com/the-best-soft-chocolate-chip-cookies | Tasty + Yoast @graph | live-2026-10-17 |
| wprm-recipetineats-chasseur.html | https://www.recipetineats.com/chicken-chasseur/ | WPRM + Yoast @graph | live-2026-10-17 |
| wprm-loveandlemons-hummus.html | https://www.loveandlemons.com/hummus-recipe/ | WPRM + @graph | live-2026-10-17 |
| wprm-skinnytaste-turkey-chili.html | https://www.skinnytaste.com/turkey-chili/ | WPRM + @graph | live-2026-10-17 |
| wprm-cookingclassy-noodle-soup.html | https://www.cookingclassy.com/chicken-noodle-soup/ | WPRM + @graph | live-2026-10-17 |
| synthetic/synthetic-microdata.html | hand-written (no live microdata site found) | microdata | synthetic |
| synthetic/synthetic-blocked-sallysbaking.html | stand-in for sallysbakingaddiction.com (403 at capture) | WPRM + @graph, @type array, double-encoded entity | synthetic |
| synthetic/synthetic-heading.html | hand-written | "Ingredients" heading only | synthetic |
| synthetic/synthetic-text-only.html | hand-written | prose only → page text for Claude | synthetic |
| hostile/hostile-deep-array.html | generated | 10,000-deep array | synthetic |
| hostile/hostile-50k-ingredients.html | generated | 50,000 ingredients | synthetic |
| hostile/hostile-oversized-block.html | generated | JSON-LD block over 512 KB | synthetic |

Blocked at capture (403/402): budgetbytes.com, allrecipes.com, simplyrecipes.com,
seriouseats.com, cookieandkate.com, foodnetwork.com, gimmesomeoven.com,
halfbakedharvest.com, deliciousmagazine.co.uk, sallysbakingaddiction.com,
twopeasandtheirpod.com — these go to "Select lines manually" until the v1.1 WebView.
