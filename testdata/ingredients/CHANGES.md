# Golden data changes

Golden files are frozen. A real fix is its own `golden-fix: …` commit that
touches nothing under `android/`, updates `CHECKSUM`, and is logged here.
At most 3 per run (PLAN §6 rule 6).

| Date | Commit | File | Line id | Change | Spec rule |
|---|---|---|---|---|---|
| 2026-10-17 | [1.2] | frozen.json, synthetic-uk.json | — | Initial freeze: 80 NYT lines (seed 20261017, 25 overrides in `tools/nyt-to-golden/overrides.json`) + 25 hand-written UK lines | R10 |
