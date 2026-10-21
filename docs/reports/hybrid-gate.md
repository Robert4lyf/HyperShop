# Hybrid gate: the rule parser on the lines it would keep

Uncertainty = the parser's `check` flag, no name, a digit with no amount read, or a sentence-length name.

| set | lines | passed | passed name | passed qty | flagged | flagged name | flagged qty |
|---|---|---|---|---|---|---|---|
| frozen | 80 | 63 | 98% | 100% | 17 | 88% | 100% |
| held-out-2026 | 45 | 37 | 95% | 95% | 8 | 63% | 63% |
| synthetic-uk | 25 | 25 | 100% | 100% | 0 | — | — |
| all | 150 | 125 | 98% | 98% | 25 | 80% | 88% |

## Passed lines the rule parser still got wrong (what the gate would miss)

| set | id | line | got | want |
|---|---|---|---|---|
| frozen | nyt-167028 | 1/2 teaspoon grated fresh ginger root | fresh ginger root | 1/2 tsp | ginger root / 0.5 tsp |
| held-out-2026 | ho-13 | 3/4 cup packed light brown sugar | packed light brown sugar | 3/4 cup | light brown sugar / 0.75 cup |
| held-out-2026 | ho-32 | 1 block (350g) extra-firm tofu, pressed | block extra-firm tofu | 1  | extra-firm tofu / 1.0 other |
| held-out-2026 | ho-33 | 3 kaffir lime leaves | kaffir lime leaves | 3  | kaffir lime leaf / 3.0 other |

## Flagged lines, with the reason

| set | id | line | reason | right? |
|---|---|---|---|---|
| frozen | nyt-35865 | 4 large ears corn | an unknown unit | yes |
| frozen | nyt-140461 | 1 bag baby spinach | an unknown unit | yes |
| frozen | nyt-157842 | 1 strip of orange zest | an unknown unit | yes |
| frozen | nyt-86658 | 8 leaves arugula | an unknown unit | yes |
| frozen | nyt-45930 | 4 tablespoons safflower or peanut oil | "A or B": one of two | yes |
| frozen | nyt-106750 | 16 ounces whole green peppers or 14 ounces coarsely chopped ready-cut green peppers (4 cups) | "A or B": one of two | yes |
| frozen | nyt-146839 | 2 garlic cloves or 1 small bulb green garlic, minced | "A or B": one of two | yes |
| frozen | nyt-68117 | 4 rectangles thinly sliced cooked ham, about 1/4 pound | an unknown unit | yes |
| frozen | nyt-10905 | 1 1/2 pounds untrimmed French or Italian bread | "A or B": one of two | no |
| frozen | nyt-148279 | 1/2 cup chopped parsley or basil | "A or B": one of two | yes |
| frozen | nyt-112979 | Corn oil or canola oil for deep frying | "A or B": one of two | yes |
| frozen | nyt-114955 | Finely grated zest of 2 limes | "zest of": the fruit is listed, not the zest | yes |
| frozen | nyt-47079 | Snipped basil or Italian parsley to garnish | "A or B": one of two | yes |
| frozen | nyt-135963 | Slivered fresh basil leaves or very small whole basil leaves for garnish | "A or B": one of two | no |
| frozen | nyt-37849 | Ice cream or whipped cream (optional) | "A or B": one of two | yes |
| frozen | nyt-36875 | Ground espresso (enough for 2 long shots, about 1/3 cup each) | an amount it couldn't read | yes |
| frozen | nyt-57565 | Few sprigs parsley to yield 2 tablespoons chopped | an amount it couldn't read | yes |
| held-out-2026 | ho-06 | 2 rashers smoked streaky bacon, chopped | an unknown unit | yes |
| held-out-2026 | ho-17 | Kosher salt and freshly ground black pepper | "X and Y": may be two items | no |
| held-out-2026 | ho-24 | 200g Greek yoghurt or crème fraîche | "A or B": one of two | yes |
| held-out-2026 | ho-27 | juice of 2 limes | "juice of": the fruit is listed, not the juice | yes |
| held-out-2026 | ho-28 | zest and juice of 1 lemon | "juice of": the fruit is listed, not the juice | yes |
| held-out-2026 | ho-36 | A handful of rocket, if you like | an unknown unit | no |
| held-out-2026 | ho-37 | •  2  tbsp   soy sauce | an amount it couldn't read | no |
| held-out-2026 | ho-39 | Eggs - 6, large | an amount it couldn't read | no |
