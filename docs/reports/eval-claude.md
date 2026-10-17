# Claude eval (Step 4.6)

Scoring: docs/parser-spec.md R11. Page fixtures: see testdata/pages (Step 5.1).

| parser | set | lines | name | qty | unit | cost (USD) | output tokens | failed batches |
|---|---|---|---|---|---|---|---|---|
| rules | nyt-80 | 80 | 96% | 100% | 100% | 0 | 0 | |
| rules | uk-25 | 25 | 100% | 100% | 100% | 0 | 0 | |
| claude-haiku-5-5 | nyt-80 | 80 | 75% | 78% | 74% | 0.0085 | 13801 | MAX_TOKENS ×1 |
| claude-haiku-5-5 | uk-25 | 25 | 100% | 100% | 96% | 0.0021 | 3186 |  |
| claude-sonnet-5-5 | nyt-80 | 80 | 86% | 98% | 93% | 0.1393 | 10696 |  |
| claude-sonnet-5-5 | uk-25 | 25 | 100% | 96% | 96% | 0.0422 | 3172 |  |
| claude-opus-5-5 | nyt-80 | 80 | 88% | 99% | 93% | 0.2809 | 10808 |  |
| claude-opus-5-5 | uk-25 | 25 | 100% | 96% | 96% | 0.0846 | 3181 |  |
