# November synthetic shopping evaluation

20 lines · 3 label mismatches · 2 unflagged label mismatches.

Unflagged identity/quantity mismatches: 0. Explicit container sizes are expanded; no density is assumed.

Unflagged meaning losses, counted apart from identity/quantity: ingredient part 0 · consequential qualifier 0.

Rule parser only. No live Claude results or user timing measurements. Labels are model-authored, not human-adjudicated.

| Source | Ingredient | Base amount | Flagged | Meets label | Part | Qualifier |
|---|---|---|---|---|---|---|
| 400 g plus 0.1 kg plain flour | plain flour | {mass=500} | false | true | n/a | n/a |
| 1 tbsp plus 1 tsp olive oil | olive oil | {volume=20} | false | true | n/a | n/a |
| 250 ml milk plus 1/4 l milk | milk | {volume=500} | false | true | n/a | n/a |
| 200 g butter plus 2 tbsp oil | butter | {mass=200} | true | true | n/a | n/a |
| 200 g flour plus 2 tbsp for dusting | flour | {mass=200} | true | true | n/a | n/a |
| 2 onions, plus 1 onion | onions | {count=3} | false | true | n/a | n/a |
| 1 large egg plus 1 egg yolk | large egg | {count=2} | true | true | flagged | n/a |
| 2 x 400 g tins chopped tomatoes | chopped tomatoes | {mass=800} | false | true | n/a | n/a |
| half a cucumber | cucumber | {count=1/2} | false | false | n/a | n/a |
| 1½ kg potatoes | potatoes | {mass=1500} | false | true | n/a | n/a |
| 3/4 litre vegetable stock | vegetable stock | {volume=750} | false | true | n/a | n/a |
| 1 jar pesto | pesto | {count:jar=1} | false | true | n/a | n/a |
| a 300 g bag of spinach | 300 g bag of spinach | {count=1} | true | false | n/a | n/a |
| 2 heaped tablespoons peanut butter | peanut butter | {volume=30} | true | true | n/a | flagged |
| 200 g chicken or tofu | chicken | {mass=200} | true | true | n/a | n/a |
| 1 lemon, zest and juice | lemon | {count=1} | false | true | n/a | n/a |
| a splash of milk | milk | {} | false | false | n/a | n/a |
| 7 g fresh yeast | fresh yeast | {mass=7} | false | true | n/a | n/a |
| 500 g frozen peas | frozen peas | {mass=500} | false | true | n/a | n/a |
| 2 packs ready rolled puff pastry | ready rolled puff pastry | {count:packet=2} | false | true | n/a | n/a |