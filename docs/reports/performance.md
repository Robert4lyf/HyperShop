# Performance baseline (Release A)

Measured by `PerformanceProbeTest` (`tools/device_perf.sh`, run in CI's device job) on
CI's emulator: Android 14 (API 34), x86_64, `google_apis`, software rendering on a
hosted runner. **Not a phone.** Treat the numbers as relative: they catch a regression
of several times; they do not say how fast a Pixel or a Galaxy is. The phone checks in
docs/HANDOFF.md (Hand-off E) are the real-hardware answer.

Data latency is measured at the view model (the call to the state that shows it), so it
excludes drawing. The shop-screen figures go through the real composables and include
recomposition, the fold animation and the test's own synchronisation.

## First baseline (run 39736242502, commit f28c139)

| What | 20 items | 60 items | 150 items | Budget |
|---|---|---|---|---|
| List: first full state | 32 ms | 50 ms | 53 ms | 250 ms |
| Tick to state, median of 15 | 26 ms | 39 ms | 57 ms | 200 ms |
| Tick to state, worst of 15 | 28 ms | 42 ms | 66 ms | — |
| Watch: hello to snapshot sent | 29 ms | 49 ms | 76 ms | 300 ms |
| Watch: pages | 1 | 2 | 4 | — |

| What | Value | Budget |
|---|---|---|
| A 15-line saved recipe onto the list | 26 ms | 150 ms |
| Search 300 saved recipes: first search, which builds the index | 2785–4003 ms before; **1268 ms** after (run 39744922298) | 4000 ms |
| Search 300 saved recipes: a second search (index reused) | 1235 ms before; **40 ms** after (run 39744922298) | 250 ms |
| Shop screen: first frame with 150 items (cold) | 1227 ms | 4000 ms |
| Shop: tap to "N of 150 bought", median of 10 | 254 ms | 750 ms |
| Shop: tap to "N of 150 bought", worst of 10 | 963 ms | — |
| Shop: scroll to the end | 182 ms | — |
| Memory: total PSS after the shop session | 142 MB | — |
| Disk access on the main thread (StrictMode) | 0 | 0 |

## Second run (run 39738064579, commit bf73526)

Same emulator image, a different runner: list 28/29/36 ms, tick median 18/27/38 ms,
watch 25/32/49 ms, recipe add 18 ms, shop first frame 558 ms, tap median 188 ms (worst
517 ms), scroll 128 ms, PSS 124 MB, 0 main-thread violations. Run to run, the hosted
emulator varies by up to about 2x, which is why the budgets sit at about 3x.

## Release B: planning and history at scale (run 39771428395, commit 9481c10)

`PerformanceProbeTest.planningAndHistoryAtScale`: 300 saved recipes, twelve weeks
of plans (5 meals each), the full 26 trips of 60 items each, and 6 meals waiting.

| What | Time | Budget |
|---|---|---|
| Preview of 6 meals to add (applied to a copy of the list) | 97 ms | 300 ms |
| 6 meals onto the list, one transaction | 404 ms | 1300 ms |
| Another week's meals | 20 ms | 150 ms |
| Buy again suggestions from 26 trips | 26 ms | 150 ms |
| At home check over the week's list | 25 ms | 150 ms |

The same run kept Release A's numbers inside their budgets (list, ticks, watch,
shop screen; first 300-recipe search 2.5 s, later 89 ms) and found no disk access
on the main thread. Every Release B read runs on `Dispatchers.Default` (the
view models' `flowOn`) or in the repository's transactions; none on the main thread.

## What it says

- Data work scales gently: 20 → 150 items roughly doubles list and tick latency, and
  stays well under a frame budget's worth of waiting a shopper would notice.
- No database or file access on the main thread during a 150-item shop session.
- The shop screen's tap-to-visible (254 ms median) is about 200 ms more than the data
  path (57 ms). That gap is drawing, the fold animation and test synchronisation on a
  software-rendered emulator; it was not profiled further here, so no optimisation was
  made on a guess. It is the number to look at first on a real phone (Hand-off E), and
  the first candidate if a shopper finds ticking sluggish.
- Recipe search (KNOWN_ISSUES #16, fixed). Measured first, then fixed what the numbers
  showed. Every search normalised all 22,500 ingredient names into words again (about
  90% of a warm search on a JVM); they are now made once per index. Building the index
  ran the full merge `target` (key and aisle too) for all 4,500 lines, 1.45 s on the
  emulator; it now asks only for the catalogue entry (`Merger.catalogEntry`, 173 ms).
  First search 4.0 s → 1.3 s, later searches 1.2 s → 40 ms. The second search's budget
  is about 6x rather than 3x: at 40 ms the runners' 2x spread is a larger share, and
  250 ms still fails long before the old 1.2 s.
- The worst tap (963 ms) is the first one after the screen opened (cold code paths).

## Not measured

- Frame timing and jank while scrolling (needs Macrobenchmark or a profiler on a device).
- Battery.
- A real watch's round trip (the probe measures the phone's side only).
