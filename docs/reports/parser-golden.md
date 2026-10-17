# Rule parser on the golden set

| stratum | lines | name | qty |
|---|---|---|---|
| comments_parens | 15 | 100% | 100% |
| comments_parens (held out) | 5 | 100% | 100% |
| fractions_ranges | 10 | 90% | 100% |
| fractions_ranges (held out) | 5 | 80% | 100% |
| no_qty | 15 | 93% | 100% |
| no_qty (held out) | 5 | 100% | 100% |
| simple | 20 | 100% | 100% |
| simple (held out) | 5 | 100% | 100% |
| synthetic-uk | 25 | 100% | 100% |

**Gate** (simple + comments_parens, not held out, 35 lines): name 100% (≥ 90%), qty 100% (≥ 95%).

## Misses

| id | line | got | want |
|---|---|---|---|
| nyt-167028 | 1/2 teaspoon grated fresh ginger root | fresh ginger root | 1/2 tsp | ginger root | 0.5 tsp |
| nyt-10905 | 1 1/2 pounds untrimmed French or Italian bread | untrimmed french | 3/2 lb | bread | 1.5 lb |
| nyt-135963 | Slivered fresh basil leaves or very small whole basil leaves for garnish | fresh basil leaves | null  | basil |   |
