# basket-2026 — sources and labelling

Ground truth for the final shopping LIST, not the per-line parse: for each
recipe, what a UK home cook puts in the basket. Labelled by hand from the
pages alone (no app code, no catalogue, no other eval data, no model call).
None of these URLs appears in `final-2026-SOURCES.md`,
`release-c-2026-SOURCES.md`, `t-2026-SOURCES.md`,
`testdata/ingredients/CHANGES.md` or `catalog/SOURCES.md`.

All pages fetched with curl on 2026-10-24. Lines are copied verbatim from the
page's JSON-LD `recipeIngredient` (non-breaking spaces and doubled spaces kept),
except Nigella, whose page has no Recipe JSON-LD: its lines are the visible
metric list (the page repeats the list again in US cups; that copy is ignored).

| id | source | group | servings | lines | basket | URL |
| --- | --- | --- | --- | --- | --- | --- |
| bk-01 | BBC Good Food — easy chicken fajitas | uk | 4 | 14 | 14 | https://www.bbcgoodfood.com/recipes/easy-chicken-fajitas |
| bk-02 | BBC Good Food — spiced carrot & lentil soup | uk | 4 | 8 | 9 | https://www.bbcgoodfood.com/recipes/spiced-carrot-lentil-soup |
| bk-03 | BBC Good Food — Thai green chicken curry | uk | 6 | 12 | 12 | https://www.bbcgoodfood.com/recipes/thai-green-chicken-curry |
| bk-04 | BBC Good Food — classic Victoria sandwich (baking) | uk | 10 (slices) | 11 | 9 | https://www.bbcgoodfood.com/recipes/classic-victoria-sandwich-recipe |
| bk-05 | Happy Foodie — Rangoon Sisters Burmese dal | uk | 4 (main) | 9 | 9 | https://thehappyfoodie.co.uk/recipes/dal/ |
| bk-06 | Mob — one-pot tomato orzo | uk | 4 | 10 | 10 | https://www.mob.co.uk/recipes/one-pot-tomato-orzo |
| bk-07 | Nigella — meatballs (visible list) | uk | 6 | 19 | 15 | https://www.nigella.com/recipes/meatballs |
| bk-08 | Jamie Oliver — sticky orange chicken | uk | 2 | 13 | 12 | https://www.jamieoliver.com/recipes/chicken/sticky-orange-chicken/ |
| bk-09 | RecipeTin Eats — chicken fried rice | us | 4 | 16 | 16 | https://www.recipetineats.com/chicken-fried-rice/ |
| bk-10 | RecipeTin Eats — easy apple cake (baking) | us | 12 | 11 | 13 | https://www.recipetineats.com/easy-apple-cake-recipe/ |
| bk-11 | Once Upon a Chef — penne alla vodka | us | 4 | 13 | 13 | https://www.onceuponachef.com/recipes/penne-alla-vodka.html |
| bk-12 | Bon Appétit — chickpea saag | us | 4 | 13 | 15 | https://www.bonappetit.com/recipe/chickpea-saag |
| bk-13 | Epicurious — caramelized onion pasta | us | null ("2–4") | 9 | 9 | https://www.epicurious.com/recipes/food/views/caramelized-onion-pasta |
| bk-14 | Pinch of Yum — best easy fish tacos | us | 2 | 18 | 16 | https://pinchofyum.com/best-easy-fish-tacos |
| bk-15 | Pinch of Yum — chickpea curry | us | 4 | 10 | 10 | https://pinchofyum.com/chickpea-curry |

8 UK, 7 US/AU; 182 basket entries. Two Pinch of Yum pages because Budget Bytes,
Cookie and Kate and Delicious answered 403, Waitrose reset the connection, and
several guessed URLs (Olive, Once Upon a Chef tacos, Epicurious shrimp) 404'd.

## Schema

`basket[]` entries: `item` (plain UK name), `accept` (any one of these is
right), `optional`, `lines` (0-based indices into `lines`), `note`. Every line
index is used by at least one entry; a line may feed several entries ("salt,
cinnamon and ground cloves" → three). `min`/`max` are for the recipe as
written, never scaled.

## Labelling guide (as applied)

From the brief:

- Garlic → `EACH` 1 bulb (1 bulb covers up to ~10 cloves; all recipes here need ≤ 8).
- Fresh herbs → `BUNCH` 1 or `PACK` 1 (none here needed 2).
- Lemon/lime juice or zest → `EACH` fruit (lemon ≈ 3 tbsp, lime ≈ 2 tbsp); "juice of 1 lime" + "juice of 2 limes" → 3.
- Tins: count of UK 400 g tins. "A 400g tin" → `TIN` 1.
- Cheese and butter → `WEIGHT` (US stick = 113 g).
- Eggs → `EACH` exactly as written, plus `PACK` 1 (the box).
- Cream/milk in volume → `BOTTLE`/`PACK` count; stock → `PACK` of cubes/pots.
- Celery sticks, spring onions (`BUNCH` 1 or `EACH`), chillies `EACH`, ginger `WEIGHT` or `EACH` piece, olives/capers `JAR`.
- Flour/sugar → `WEIGHT`; baking powder/bicarb/vanilla → `PRESENCE`.

Added while labelling:

- **WEIGHT ranges.** `min` ≈ 90 % of what the recipe needs; `max` is the
  smallest normal UK pack that covers it when that is within reason (mince
  500 g, pasta 500 g, butter 2 × 250 g, carrots 1 kg bag), otherwise ≈ 1.5×.
  Where one shop pack obviously covers the need (flour, sugar, lentils, rice,
  pasta, bacon), `PACK` 1 is also accepted, rather than stretching `WEIGHT`
  to a 1.5 kg bag of flour.
- **Small amounts of a fridge staple** (≤ ~3 tbsp butter, a splash of milk)
  accept `PRESENCE` as well as the normal kind.
- **Tiny sugar** (a pinch, 1 tsp, 1 tbsp) → `PRESENCE`; a baking quantity → `WEIGHT`.
- **Oils** are `PRESENCE` even at 1 cup (apple cake also accepts `BOTTLE` 1).
- **"Olive oil" and "extra virgin olive oil" in one recipe** are merged (one bottle in most UK kitchens).
- **Condiments** (fish sauce, oyster sauce, soy, Tabasco, Shaoxing/mirin, wholegrain mustard, tomato purée in tbsp) → `PRESENCE`; the ones a cook may well not own also accept `BOTTLE`/`PACK`/`JAR` 1.
- **Curry paste** (green, red) → `JAR` 1 even at 1 tbsp: it is a specific purchase, not a staple.
- **Spirits** (brandy, vodka) → `BOTTLE` 1.
- **Yoghurt, soured cream, double cream** by the spoon or cup → `PACK` (pot/tub) count.
- **US can sizes** → UK tins: 14-15 oz → 1; 28 oz → 2.
- **Fractions of a whole thing** round up: ½ onion, ½ lemon → 1.
- **"X or Y"** in a line is one entry named for the more usual UK buy, accepting either form (semolina/breadcrumbs, sour cream/Greek yoghurt, lime leaves/lime zest, passata/chopped tomatoes).
- **"A and B, to serve"** (yoghurt and naan; rice and/or naan and lime) is split into separate optional entries.
- **Optional** = the page says optional, garnish, "to serve"/"for serving", or puts it under an "Optional Extra" heading. A topping in a "whatever you like" method step (avocado, red onion, mango salsa on tacos) is optional; the tortillas are not.
- **Cooked rice in the list** (fried rice) still has to be bought: dry weight or cooked pouches.
- **Frozen veg** → `PACK` 1 (bag), with a `WEIGHT` near the need.
- **Bought chilled things** (fresh salsa 230 g, watercress 60 g, cherry tomatoes 250 g) → `PACK` 1 or `WEIGHT` near the need.
- **US names → UK**: cilantro → coriander, green onions → spring onions, heavy cream → double cream, tomato paste → tomato purée, serrano → green chilli, all-purpose flour → plain flour, baking soda → bicarbonate of soda, kosher salt → salt, white sugar → caster sugar.

## Judgement calls, by recipe

**bk-01 fajitas.** Chicken breasts `EACH` 2 / `WEIGHT` 250-400 / `PACK` 1. "mixed salad" has no "to serve" on the page, so not optional (a bag of leaves). Fresh salsa 230 g → a chilled tub (`PACK`) or a jar. Tortillas: pack of 8, or 8 each. Tabasco `PRESENCE` (also `BOTTLE` 1).

**bk-02 carrot & lentil soup.** Carrots 600 g → `WEIGHT` 540-1000 (a 1 kg bag), `EACH` 6-9. Lentils 140 g → `WEIGHT` 126-500 or `PACK` 1. Stock 1 l is a meal's worth, so `PACK` 1-2, not `PRESENCE`. Milk 125 ml → `BOTTLE` 1. "plain yogurt and naan bread, to serve" → two optional entries.

**bk-03 Thai green curry.** New potatoes 225 g → up to a 1 kg bag. Sugar 1 tsp → `PRESENCE`. Chicken 450 g "breasts or thighs" is one entry, `EACH` 3-4 breasts allowed. Lime leaves line offers lime zest instead → one entry accepting `PACK` 1 (leaves) or `EACH` 1 (lime). "good handful of basil" → one pack. Rice "to serve" optional.

**bk-04 Victoria sandwich.** Servings 10 from "Cuts into 10 slices". Butter 200 g + 100 g merged → 300-500 g. Icing sugar 140 g + "to decorate" merged → 140-500 g or a box. Milk 2 tbsp → `PRESENCE` or `BOTTLE`. Vanilla optional. "half a 340g jar" jam → `JAR` 1.

**bk-05 Burmese dal.** Servings 4 ("Serves 4 as a main, 6 as a side"). 4 medium onions → `EACH` 4-5 or `WEIGHT` 550-800. Coriander optional.

**bk-06 tomato orzo.** "4 Tomato" → `EACH` 4 or `WEIGHT` 350-600. Stock 800 ml → `PACK` 1-2. Basil 25 g → one pack.

**bk-07 Nigella meatballs.** Lines are the visible metric list. Garlic (1 + 2 cloves), oregano, salt and pepper merged across the meatball and sauce sections. Parmesan 2 tbsp grated → `WEIGHT` 10-200 or a wedge (`PACK` 1). "semolina or breadcrumbs" → breadcrumbs, also `PRESENCE` since 3 tbsp is a cupboard amount. Butter 1 tbsp → `PRESENCE` or weight. Passata 700 g → one 690/700 g bottle, two 500 g cartons, the "chopped tomatoes" alternative as 2 tins, or `WEIGHT` 680-1000.

**bk-08 sticky orange chicken.** "2 x 150g … chicken breasts" → `EACH` 2 (the multiplier is a count). "½ a bunch of rosemary (10g)" → one bunch/pack. Olive oil + extra virgin olive oil merged. Watercress 60 g → a bag. Brandy 50 ml → `BOTTLE` 1. Sweet potatoes 500 g → `WEIGHT` 450-700 or `EACH` 2-3.

**bk-09 chicken fried rice.** Chicken 150 g → 1 breast. ½ onion → 1. Bacon 120 g → up to a 300 g pack. Corn: frozen bag or a tin (the line allows can). Cooked rice → buy rice: `WEIGHT` 130-500 (dry or two 250 g cooked pouches) or `PACK` 1-2. ¾ cup spring onions → 1 bunch / 5-8. Chinese cooking wine or mirin → one entry, `PRESENCE` or `BOTTLE`. Sesame oil and white pepper marked "optional" on the page.

**bk-10 apple cake.** 2 cups flour ≈ 300 g → 270-400. "¾ tsp EACH salt, cinnamon and ground cloves" → three entries from one line. White sugar → caster sugar, 1¼ cups ≈ 250 g. Oil 1 cup → `PRESENCE` or `BOTTLE`. "2 cups apple" → the visible list says ~2 large / 3 small → `EACH` 2-3. Almonds and icing sugar are under "Optional Extra" → optional.

**bk-11 penne alla vodka.** 28-oz can → `TIN` 2. Tomato paste 2 tbsp → tomato purée, `PRESENCE` or a tube/jar. ⅔ cup heavy cream (~160 ml) → double cream `PACK` 1-2 (300 ml pot, or two 150 ml). Parmesan "for serving" → optional.

**bk-12 chickpea saag.** 1" ginger → `WEIGHT` 15-100 or one piece. Serrano → green chilli `EACH` 1. 10-oz frozen spinach → UK frozen spinach bag (`PACK` 1, `WEIGHT` 250-1000). "Steamed rice and/or warm naan and lime wedges (for serving)" → three optional entries. Yoghurt (¼ cup + for serving) is a required pot.

**bk-13 caramelized onion pasta.** Servings null (page says "2–4"). 2 lb onions ≈ 900 g, "about 4 large" → `EACH` 4-5 / `WEIGHT` 850-1100. 8 oz linguine → 225-500 g or a pack. Parmesan 4 oz + more → 110-250 g. ½ lemon → 1.

**bk-14 fish tacos.** Limes 1 + 2 → 3. Salt merged across two lines. Cod 10-15 oz "thawed" → `WEIGHT` 280-450 or a frozen pack. Sauce oil ¼ cup → `PRESENCE`. "sour cream or Greek yogurt" → soured cream, one tub. Cabbage 2-3 cups shredded → a small cabbage, a bag of shredded, or `WEIGHT` 150-600. Tortillas: no count, required (`PACK` 1 or 4-8). Avocado, red onion, mango salsa are listed bare but the method calls them toppings "whatever you like" → optional.

**bk-15 chickpea curry.** Avocado oil → `PRESENCE`. Brown sugar 1 tbsp → `PRESENCE`. Red curry paste 1½ tbsp → `JAR` 1. Fresh spinach 2-3 cups (~60-90 g) → a bag or `WEIGHT` 60-260. Cilantro not marked optional. Jasmine rice is in the title → required, 1½ cups dry ≈ 280 g.
