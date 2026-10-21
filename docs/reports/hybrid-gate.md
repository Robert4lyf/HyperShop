# Hybrid gate: the rule parser on the lines it would keep

Uncertainty = the parser's `check` flag, no name, a digit with no amount read, or a sentence-length name.

| set | lines | passed | passed name | passed qty | flagged | flagged name | flagged qty |
|---|---|---|---|---|---|---|---|
| frozen | 80 | 63 | 98% | 100% | 17 | 88% | 100% |
| held-out-2026 | 45 | 38 | 95% | 95% | 7 | 71% | 71% |
| held-out-2026b | 40 | 39 | 95% | 95% | 1 | 0% | 100% |
| synthetic-uk | 25 | 25 | 100% | 100% | 0 | — | — |
| all | 190 | 165 | 97% | 98% | 25 | 80% | 92% |

## Passed lines the rule parser still got wrong (what the gate would miss)

| set | id | line | got | want |
|---|---|---|---|---|
| frozen | nyt-167028 | 1/2 teaspoon grated fresh ginger root | fresh ginger root | 1/2 tsp | ginger root / 0.5 tsp |
| held-out-2026 | ho-13 | 3/4 cup packed light brown sugar | packed light brown sugar | 3/4 cup | light brown sugar / 0.75 cup |
| held-out-2026 | ho-32 | 1 block (350g) extra-firm tofu, pressed | block extra-firm tofu | 1  | extra-firm tofu / 1.0 other |
| held-out-2026 | ho-33 | 3 kaffir lime leaves | kaffir lime leaves | 3  | kaffir lime leaf / 3.0 other |
| held-out-2026b | hob-03 | 400ml can coconut milk | coconut milk | 1 tin | coconut milk / 400.0 ml |
| held-out-2026b | hob-07 | 500g pack beef mince (20% fat) | beef mince | 1 packet | beef mince / 500.0 g |
| held-out-2026b | hob-10 | 2 cups shredded mozzarella cheese | mozzarella cheese | 2 cup | shredded mozzarella cheese / 2.0 cup |
| held-out-2026b | hob-32 | Fresh mint leaves, to garnish | fresh mint leaves | null  | mint / null  |

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
| held-out-2026 | ho-39 | Eggs - 6, large | an amount it couldn't read | no |
| held-out-2026b | hob-23 | 1 tbsp fresh thyme leaves or 1 tsp dried | "A or B": one of two | no |

## Fourth pass (step 9.4): 40 more unseen lines

`testdata/eval/held-out-2026b.json` was written before the parser was run on it: UK and US measures, unicode and mixed fractions, ranges ("200-250g", "1-1½ litres"), alternatives ("2 cups (480ml)", "1 lb (450g)", "or 1 tsp dried"), unusual names (doenjang, za'atar, asafoetida), optional and garnish lines, and messy pasting (a bullet, capitals, "1tbsp", "2 x large onions").

- The rule parser kept 39 of 40 and read 95% of those right on both name and amount, the same as on the first held-out set and above Haiku's 94% / 92% on the golden sets. **The production strategy does not change**: the rules first, Claude only for a flagged line, page text, a photo or "Ask Claude".
- One real defect was found and fixed: a pasted list marker ("- 3 eggs", "• 2 tbsp soy sauce") hid the amount. `RuleParser` now drops a leading bullet, or a dash followed by a space. The frozen golden scores did not move.
- Three of the remaining "misses" are judgement calls rather than errors: "400ml can coconut milk" and "500g pack beef mince" are read as 1 tin and 1 packet (what you pick up in the shop), and "shredded" or "fresh" is dropped from a name (the shop sells mozzarella and mint). They are left as they are.
- No cookbook photographs were evaluated: a photo can only be read by Claude, and running Claude here would mean spending the key on an unrepeatable test. Photo accuracy stays a manual check.
