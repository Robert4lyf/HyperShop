# Compound-quantity label corrections

Two existing labels demonstrably omitted safely convertible extras:

| Label | Original source | Old quantity | Correct quantity | Independent arithmetic |
|---|---|---|---|---|
| tq-122 | 1 Tbsp plus 1/2 tsp fine sea salt | 1 tbsp | 7/6 tbsp | 15 + 2.5 = 17.5 ml |
| rc-196 | 3 tablespoons plus 1/2 teaspoon sugar | 3 tbsp | 19/6 tbsp | 45 + 2.5 = 47.5 ml |

These corrections change the reference purchasing meaning; they are not tuning to parser output. They were checked against the established spoon factors in `Units.kt`. Source text and all other labels remain unchanged. Existing baselines are retained in git history; any regenerated baseline must be reviewed against these two corrections and the newly explicit review warnings. Accuracy thresholds must not be lowered to hide implementation regressions.

Cup/spoon ratios vary by locale in HyperShop (240 ml US, 250 ml metric). The parser does not yet know the source locale, so those compounds must remain flagged with the original text; it must not silently pick a ratio. A quantity of flour plus spoonfuls cannot imply a density. Recorded Claude responses remain historical recordings, never presented as new live-assisted results.
