# Step W — from "parsed right" to "bought right"

Why: a day of real use found seven faults, and every one that was not about colour sat between the
recipe's amount and what goes in the basket (olives read as "soft", lime juice as a bottle, missing
weights, olives in ml, celery stalks vs sticks). The evaluations measured the parse, never the
list, so none of them could have caught these, and each was fixed as its own special case.

## Phases (one PR each; merged when green)

### W.0 Measure the list, before changing it
- A new labelled set, `testdata/eval/basket-2026.json`: about 15 real recipes from pages in no
  other set, a fresh agent labelling each *list row* as a UK shopper would buy it — the item, how
  it is bought (each / weight / jar / pack / bottle / bunch / presence) and an acceptable range.
  Labelled from the pages alone, before W.1–W.3 exist.
- `BasketEvalTest` runs each recipe through the real pipeline (parse → `Merger.incoming` → list →
  `Display`) and scores: right item, right way of buying, amount in range. Baseline recorded.
- **Double-check:** the labels are judgement. A written guide decides the hard cases up front
  (garlic: bulbs or cloves? herbs: bunch or pack?), and labels are never revised to improve a score.

### W.1 Claude reads every new import
- New setting "Read recipes with Claude: Always / Only when unsure", default Always when a key is set.
  The rule parser still reads first, is shown while Claude works, and stays as the offline and
  no-key fallback and the cross-check (`ClaudeAlignment` flags amounts the two read differently).
- **Must stay true (CLAUDE.md):** Claude is called only while the review is in the foreground; a
  share on its own never spends; the spend guard and its cap apply; corrections laid over Claude's
  answer survive; a saved recipe re-added is not read again.
- **Double-check:** the cost (measured: about $0.001 a recipe on Haiku), the wait on opening a
  review (show the rules' reading straight away, not a spinner), offline and refusal paths.

### W.2 How each thing is bought
- One catalogue field, `buyAs` (EACH, WEIGHT, JAR, TIN, PACK, BOTTLE, BUNCH, PRESENCE), and one
  conversion field, `gramsEach`, next to the existing `gramsPerCup`.
- `Display` shows every list row in its buying unit, converting what it can, with the recipe's own
  figure alongside where it adds something: "Tomato 2 (≈560 g)", "Olives 1 jar (170 g)",
  "Celery 4 sticks". Display only: what is stored (contributions, ticks) does not change, so no
  migration and nothing for the watch to re-learn beyond the strings it already receives.
- The special cases from V.2–V.6 are kept where they are identity (lemon juice → lemon), retired
  where `buyAs` now covers them.
- **Double-check:** the watch's row width (long strings), rounding (never show less than needed),
  mixed units on one row, Buy again and the "N of M bought" counts, screenshots.

### W.3 The catalogue for what is actually bought
- `buyAs`, `gramsEach` and pack sizes filled for the ~150 items the labelled sets and real lists
  use most, each marked with its source; the rest keep today's behaviour.
- **Double-check:** every figure has a source or is marked an estimate; no eval set is the source.

### W.4 "This is wrong"
- On a review line and a list row: one tap saves the recipe line, how it was read, what the list
  shows and what you expected, to a local file you can share. Nothing leaves the phone by itself.

### W.5 Review and close
- Independent adversarial review, fixes with tests, `verify.sh`, CI, report with before/after on
  the basket set.

## Deliberately not in this step
- Supermarket baskets (no public API; scraping would break). Pantry learning (needs a season of
  "At home" history to be trustworthy). A broad refactor of `ListRepository` /
  `ReviewViewModel`: only the parts W.1–W.4 touch are split, because a large rewrite of working
  code is where regressions come from.
