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
| Search 300 saved recipes: first search, which builds the index (run 39738064579) | 2785 ms | 8000 ms |
| Search 300 saved recipes: a second search (index reused) | measured from the next run | 2000 ms |
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

## What it says

- Data work scales gently: 20 → 150 items roughly doubles list and tick latency, and
  stays well under a frame budget's worth of waiting a shopper would notice.
- No database or file access on the main thread during a 150-item shop session.
- The shop screen's tap-to-visible (254 ms median) is about 200 ms more than the data
  path (57 ms). That gap is drawing, the fold animation and test synchronisation on a
  software-rendered emulator; it was not profiled further here, so no optimisation was
  made on a guess. It is the number to look at first on a real phone (Hand-off E), and
  the first candidate if a shopper finds ticking sluggish.
- The first recipe search over 300 recipes (2.8 s) is the slowest thing measured. It
  builds the index from every line of every recipe; later searches reuse it. Not
  profiled yet, so not optimised (KNOWN_ISSUES #16).
- The worst tap (963 ms) is the first one after the screen opened (cold code paths).

## Not measured

- Frame timing and jank while scrolling (needs Macrobenchmark or a profiler on a device).
- Battery.
- A real watch's round trip (the probe measures the phone's side only).
