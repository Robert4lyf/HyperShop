# Release B — how much effort a weekly shop takes

**What this is:** taps, typed entries and screens counted by driving the real app
on CI's emulator (`WeeklyShopScenariosTest`, printed by `tools/device_perf.sh`,
run 39774175091). **What it is not:** a measurement of people. The time column is
how long the emulator's automation took, not how long a person would; deciding what
to cook is left out by design. Real phones and real decisions are Hand-off F's
stopwatch checks (docs/HANDOFF.md).

## The task

A returning shopper's week: the same four meals as last week (chilli and dal share
an onion and cumin), the seven household items they always buy (milk, bread, eggs,
bananas, butter, toilet roll, washing-up liquid), and six cupboard ingredients the
meals need that are already at home (rice, tinned tomatoes, cumin, penne, pesto,
lentils). Then start shopping.

## Results

| Way through | Taps | Typed entries | Scrolls | Separate at-home decisions | Screens | Automated time | Source |
|---|---|---|---|---|---|---|---|
| **Release B, quick way**: Plan this week › Plan the same 4 meals › Add 4 meals to the list › Buy again: Add all › Already at home?: Have all › Start shopping | **6** | **0** | 2 | **1** (one batch) | List, Plan, Shop | 2.3 s (1.4 s on an earlier run) | measured |
| Release B, item by item: each meal planned, each regular typed, each cupboard item checked | 15 | 7 | 10 | 6 | List, Plan, List, Plan, Shop | 7.8 s | measured |
| Before Release B (main 64d1afb): Recipes › This week › Add ×4 › Go to the list › type 7 items › open and delete each of 6 cupboard rows › Start shopping | about 21 | 7 | — | 6 | List, Recipes, Meals, List, 6 item sheets, Shop | — | counted from the code, not run |

Before Release B there was no way to repeat last week (finishing a trip ended the
week's meals), no memory of household items, and no way to set a list row aside as
at home except deleting it; the "before" row is the shortest path through that code.

## What it says

- The routine week drops from about 21 taps and 7 typed items to 6 taps and none,
  and from six at-home decisions to one batch (each still reversible on its own).
- The gain depends on history: a first week, or a shopper who never presses Finish
  shopping, gets the item-by-item path (Buy again learns from finished trips).
- The two-minute target for a real phone is plausible from these counts but is **not
  measured** here. Hand-off F checks 2 and 3 time it.
- The quick-list path (type, shop) is unchanged: scenario A runs it with no planning
  step in the way.
