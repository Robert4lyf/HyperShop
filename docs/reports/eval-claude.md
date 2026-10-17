# Claude eval (Step 4.6)

Scoring: docs/parser-spec.md R11. Page fixtures: see testdata/pages (Step 5.1).

| parser | set | lines | name | qty | unit | cost (USD) | output tokens | failed batches |
|---|---|---|---|---|---|---|---|---|
| rules | nyt-80 | 80 | 96% | 100% | 100% | 0 | 0 | |
| rules | uk-25 | 25 | 100% | 100% | 100% | 0 | 0 | |
| claude-haiku-5-5 | nyt-80 | 80 | 94% | 96% | 93% | 0.0082 | 13134 |  |
| claude-haiku-5-5 | uk-25 | 25 | 100% | 92% | 92% | 0.0021 | 3167 |  |
| claude-sonnet-5-5 | nyt-80 | 80 | 86% | 98% | 93% | 0.1393 | 10696 |  |
| claude-sonnet-5-5 | uk-25 | 25 | 100% | 96% | 96% | 0.0422 | 3172 |  |
| claude-opus-5-5 | nyt-80 | 80 | 88% | 99% | 93% | 0.2809 | 10808 |  |
| claude-opus-5-5 | uk-25 | 25 | 100% | 96% | 96% | 0.0846 | 3181 |  |

Haiku rows re-run after `max_tokens` went from 4000 to 16000; Sonnet and Opus rows are from the run before (no batch of theirs hit the cap).
