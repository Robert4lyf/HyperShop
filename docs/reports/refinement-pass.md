# Step T — refinement pass

Branch `claude/serene-lovelace-464dnp`, PR #63. Each sub-step is one commit
titled `[T.n]`, described in PROGRESS.md › Step T. **Run on:** the JVM
(Robolectric and plain unit tests) and CI's emulator. **Not run on:** a real
phone or a real Forerunner 245. Hand-off checks 29–34 (docs/HANDOFF.md) need a
real phone.

## 1. Summary

| Phase | Outcome |
|---|---|
| P1 Contrast | White text on a hero now reaches ≥ 4.5:1 on every palette, without darkening the whole hero |
| P2 Photo lifecycle | Notes and photos of removed recipes now expire; staging is per inspection; integrity is checked on backup and restore |
| P3 Discovery | Saved gains Sort and "4★ & up"; the planner offers your usual meals first; cooking notes shown on meals |
| P4 Photo performance | Measured at 20/100/300 photos; a bounded cache removed repeat decodes |
| P5 Parser | New held-out set; "plus" amounts added up; Claude and the rules share one tin/mince rule; two catalogue fixes |
| P6 Hand-off | Checks 29–34; known issues #32–34 added, #26 and #29 closed |
| P7 Review | Independent adversarial review: 5 findings, all fixed with tests |
| P8 Verification | `verify.sh` green on every pushed head; both blocking CI jobs green |

## 2. Contrast (T.1)

Each piece of white text or icon on a hero gets a soft, blurred shade behind
it (`TextBackdrop.kt`); the rest of the hero is drawn as before. On a mosaic the
shade's strength follows where the text sits across the hero: none on the
left fifth, which is dark enough already, rising to 0.55 at the bright end.
This curve was measured from each palette's brightest rendered pixel per
column. On a photo the shade is 0.58, enough for white text on a white photo.

`HeroContrastTest` renders 8 palettes × 12 recipe names that were not used for
tuning. It checks every pixel under the text for both white (≥ 4.5:1) and the
soft white of subtitles. The same test fails if the shade is removed, and it
checks that pixels away from text are unchanged. `HeroTextTest` covers a long
title at font scale 2 on a 320 dp phone. Light and dark themes are both
covered by screenshots.

## 3. Photo lifecycle (T.2)

- **Retention.** A note is kept while anything names its key, then marked
  `orphanedAt` (migration 6→7). It is kept 30 days more and then deleted at
  start-up, before the photo sweep.
  - "Anything" means a saved copy of the recipe, a removed copy still kept for
    Undo, a copy on the list, or a planned meal in any week.
  - Saving the page again within the month brings everything back.
  - Covered by `PhotoRetentionTest`, including a 52-week run whose storage
    stays bounded.
- **Staging.** Each inspection stages its photos in a folder of its own, so two
  inspections never clobber each other. Start-up clears whatever a dead
  process left behind.
- **Integrity.** A staged photo is hashed again as it goes in. A photo already
  on the phone counts only if it is whole. The restore runs off the main
  thread (T.7).

## 4. Discovery (T.3)

Two chips under the search on Saved:
- **Sort:** Recently added (newest first), Highest rated, Most planned, A–Z.
- **4★ & up:** a filter for highly rated recipes.

There is no separate "favourite": four or five stars already means that.
"Most planned" counts planned meals; the app records no cooking, so it never
says "cooked".

One ordering, `LibraryOrder`, serves Saved and the planner. Every sort ends on
the same tie-breaks, so equal recipes never swap places. The planner offers
the meals you planned most in *other* weeks first. A planned meal and the On
the list sheet both show the recipe's cooking notes.

## 5. Photo performance (T.4)

Measured on CI's emulator with 1280×960 photos (`PerformanceProbeTest`). The
first run measured 20 photos only: the 100 and 300 runs failed because of a
race in the probe, fixed in T.4c.

**Photos decoded, and bitmap bytes made, on returning to something already shown**

| Step (20 photos) | Before | After |
|---|---|---|
| Scroll back to the top | 2 / 9.4 MB | 0 / 0 |
| Leave the tab and come back | 2 / 9.4 MB | 0 / 0 |
| Open a recipe and close it | 1 / 4.7 MB | 0 / 0 |
| First screen | 2 / 9.4 MB | 2 / 9.4 MB |
| Scroll to the end | 3 / 14.1 MB | 3 / 14.1 MB |

At 100 and 300 photos (after only), the same steps decode 2, 3, 0, 0 and 0
photos. The work per screen does not grow with the library.

**Times (ms) and memory, one emulator run each**

| | 20 before | 20 after | 100 after | 300 after |
|---|---|---|---|---|
| First screen | 1418 | 1910 | 289 | 241 |
| Scroll to the end | 152 | 195 | 128 | 85 |
| Scroll back | 117 | 141 | 137 | 107 |
| Leave and come back | 240 | 291 | 280 | 183 |
| Open and close a recipe | 352 | 485 | 398 | 362 |
| Total PSS (MB) | 173 | 157 | 146 | 186 |

A second after-run (the 3× repeated device run) gave the same decode counts
exactly, with times 5–40% different (20 photos: first screen 2713 ms, open and
close 657 ms; 300 photos: 301 and 449 ms). These times do not show a speed-up. The emulator has no GPU of its own, and
the first test in a run carries the process's warm-up: 1.4–1.9 s at 20
photos, against 0.2–0.3 s at 100 and 300. Run-to-run noise is larger than any
difference the cache could make to a time. What the cache measurably removes is
repeated work: about 23 MB of bitmap allocation per return trip at 20 photos.
On a phone, that should mean no grey flash when scrolling back. Hand-off check
32 is how to see it.

The cache is bounded to an eighth of the heap or 32 MB, whichever is smaller,
with the least recently used photo dropped first. Photo files are named by
their content, so a name never changes picture, and the cache is cleared after
a restore. Rotation, process recreation and cold start were not measured
(KNOWN_ISSUES #33).

## 6. Parser (T.5)

**The held-out set came first.** `t-2026.json` has 135 real lines (58 UK, 77
US) from ten pages that are in no other set. It was labelled from the lines
alone and committed (T.5a) before the parser ever read it. Its baseline was
recorded before any parser change (T.5b). No label in any set was changed.

| Set | Right before | Right after | Accepted and wrong, before → after |
|---|---|---|---|
| T held-out (135) | 112 | 113 | 16 → 15 |
| Final held-out (137) | 114 | 115 | 11 → 10 |
| Release C (285) | 259 | 261 | 11 → 9 |
| Golden, first held-out sets | unchanged | unchanged | — |

- **"Plus" amounts (T.5c, and fixes in T.7).**
  - A second amount in the same unit, of the same thing, is added: "2 tbsp
    butter plus 1 tbsp melted" is 3 tbsp, and "1 egg, plus 1 egg yolk" is 2.
    Any metric amount written alongside is added in too.
  - What an extra is *for* ("plus 2 tbsp for dusting") counts as the same
    thing.
  - "Plus" an amount of something else is flagged.
  - Another unit, or an extra in brackets, is left as it was (#34).
  - The T set's one gain (tq-047) came from a rule written to the spec, but it
    is a held-out line: read that +1 as slightly optimistic.
- **Claude and the rules (T.5d).** The tin and mince rules now live in
  `Shelf`, used by both readers (#29). This is tested offline only: no Claude
  call was made, so it is not measured on live output.
- **Catalogue (T.5e).** Two fixes, taken only from gaps in the sets already
  tuned on: "crushed red pepper flakes", and a new "chilli garlic sauce"
  entry. The T set is unchanged by them, which is the point: it remains a fair
  measure.

## 7. Adversarial review (T.7)

An independent agent reviewed the whole diff read-only. It reported five
findings, all confirmed and fixed with tests:

1. A summed "plus" kept the first amount's grams, so the list said 30 g where
   45 g was needed.
2. A quantified "plus … for dusting" was flagged as a different ingredient.
3. A restore hashed every photo on the main thread.
4. The photo cache could keep showing a damaged photo after a restore had
   repaired it.
5. "Your usual" could read −1 for one frame.

It found retention, staging, the parser's bookkeeping, `Shelf`, the ordering
and the backdrop sound.

## 8. Verification

- `tools/verify.sh`: all checks passed on every pushed head. That covers
  ktlint, every module's unit tests, the eval gates, the golden checksum, the
  catalogue freshness check, the merged-manifest permission checks and the
  `sk-ant-` scan.
- CI on 94c8ea5: "Build and test" ✅ and "Device scenarios (emulator)" ✅,
  which includes the process-death procedure and the performance probe.
- The device scenarios repeated three times on fresh installs (workflow
  dispatch, run 40007168033): all passed, no flake.
- Earlier in the step, T.4a was pushed before its gate result had been read,
  and that gate had failed. T.4b fixed it. Since then the gate has been run to
  the end, and read, before every push.

## 9. Limits and what is left

- No real phone and no real watch (#32). Contrast at real brightness, scroll
  feel and the watch are still the user's checks.
- Photo timings come from the emulator; only the decode counts are firm.
- Rotation, process recreation and cold start with photos were not measured
  (#33).
- An extra in another unit is still not added (#34, by design).
- The Claude path's tin/mince alignment is untested against live Claude output.
- If Settings is closed while a restore waits, its staged photos stay until
  the next start (harmless; swept then).

## 10. Ratings

Out of 10, for the app as it stands, not for this step alone.

| | Rating | Why not higher |
|---|---|---|
| UX | 7 | Clean and consistent, but never checked on a real phone; the plan screen is dense (check 14) |
| Functionality | 8 | The whole weekly loop is there; the parser is still wrong on about 1 line in 9 of fresh recipes, flagging only some of those |
| Visual design | 7 | A distinctive look, and readable now; the shade behind the text is untested in daylight on a phone |
| Reliability | 8 | Journalled restore, startup barrier, heavy test coverage; nothing hardware-verified |
| Architecture | 8 | Clear modules, repository-only writes, gates that ratchet; hand-wired DI is at the edge of comfortable |
| Everyday usefulness | 7 | Likely high for its one user, but that depends on the import quality and the watch, neither checked on real devices yet |
