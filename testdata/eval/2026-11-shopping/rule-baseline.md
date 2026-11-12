# November synthetic shopping evaluation

20 lines · 5 label mismatches · 4 unflagged label mismatches.

Unflagged identity/quantity mismatches: 0. Explicit container sizes are expanded; no density is assumed.

Rule parser only. No live Claude results or user timing measurements. Labels are model-authored, not human-adjudicated.

| Source | Ingredient | Base amount | Flagged | Meets label |
|---|---|---|---|---|
| 400 g plus 0.1 kg plain flour | plain flour | {mass=500} | false | true |
| 1 tbsp plus 1 tsp olive oil | olive oil | {volume=20} | false | true |
| 250 ml milk plus 1/4 l milk | milk | {volume=500} | false | true |
| 200 g butter plus 2 tbsp oil | butter | {mass=200} | true | true |
| 200 g flour plus 2 tbsp for dusting | flour | {mass=200} | true | true |
| 2 onions, plus 1 onion | onions | {count=3} | false | true |
| 1 large egg plus 1 egg yolk | large egg | {count=2} | false | false |
| 2 x 400 g tins chopped tomatoes | chopped tomatoes | {mass=800} | false | true |
| half a cucumber | cucumber | {count=1/2} | false | false |
| 1½ kg potatoes | potatoes | {mass=1500} | false | true |
| 3/4 litre vegetable stock | vegetable stock | {volume=750} | false | true |
| 1 jar pesto | pesto | {count:jar=1} | false | true |
| a 300 g bag of spinach | 300 g bag of spinach | {count=1} | true | false |
| 2 heaped tablespoons peanut butter | peanut butter | {volume=30} | false | false |
| 200 g chicken or tofu | chicken | {mass=200} | true | true |
| 1 lemon, zest and juice | lemon | {count=1} | false | true |
| a splash of milk | milk | {} | false | false |
| 7 g fresh yeast | fresh yeast | {mass=7} | false | true |
| 500 g frozen peas | frozen peas | {mass=500} | false | true |
| 2 packs ready rolled puff pastry | ready rolled puff pastry | {count:packet=2} | false | true |