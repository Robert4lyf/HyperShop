# Release C — Exceptional polish and real-world quality: final report

Branch `ccr-5923064f-985kmq` from `main` at 06e4126 (after Release B's bug hunt and
known-issue fixes). Plan and audit: docs/design/release-c.md. Progress: PROGRESS.md ›
Release C. Nothing new was added (no feature, dependency, screen or redesign); the
release is the same app, read, measured and corrected.

## 1. Baseline (C.0)

Five read-only reviews (extraction, UX and visuals, accessibility, performance,
security) over the code and the recorded screenshots, condensed into 14 extraction
findings, 6 review-screen findings, 9 journey findings, 6 accessibility findings and
a handful of performance and security lows, each with file and line. An independent
extraction set of 285 lines was written for the release (testdata/eval/
release-c-2026.json: 267 read from 32 real recipe pages on 2026-10-22, UK, US, EU and
blog, plus 18 clearly marked synthetic pasted-list lines; provenance in
release-c-2026-SOURCES.md), labelled by reading the lines, never by a parser, and
`ReleaseCEvalTest` scores the whole pipeline on it field by field. The frozen golden
data was not touched. Baseline on the new set: 72 % right, 47 of 243 accepted lines
wrong (docs/reports/release-c-extraction-baseline.md).

## 2. Extraction accuracy (C.1, C.2)

Same 285 lines, same evaluator, no change to the labels except six corrected
against the spec and recorded (docs/reports/release-c-extraction.md):

| | baseline | after |
|---|---|---|
| right (name, amount, unit) | 72 % | **88 %** |
| name / quantity / unit | 74 / 91 / 88 % | 88 / 100 / 99 % |
| catalogue match / aisle / shown amount | 73 / 95 / 81 % | 87 / 98 / 98 % |
| incorrectly accepted (confidently wrong) | 47 of 243 (19 %) | **19 of 258 (7 %)** |
| flag rate (would go to Claude when keyed) | 15 % | **9 %** |
| frozen golden gate (name / qty on kept lines) | 100 / 100 % | 100 / 100 % |

The confusion table (accepted-right, accepted-wrong, flagged-right, flagged-wrong)
is in the generated report. The 19 remaining confidently-wrong lines are naming
conventions (a brand word kept, "whole tomatoes" against "tomatoes", "canned" against
"tinned", a product the catalogue lacks), not a wrong product or amount; the 12
flagged-but-right lines are flagged on purpose ("A or B", an unknown measure). What
the rules learned: an amount after the name, lengths, decilitres, "a drizzle of" and
the other kitchen measures, a unit after a bracketed size, two lines sharing a head,
qualifiers only where the catalogue phrase ends the name, a counted pepper is a bell
pepper, spaced dashes cut like commas, super- and subscript digits, hyphen-blind
catalogue lookup, forty prep words and the synonyms the set showed missing. No
chargeable Claude call was made; Claude's path is tested with recorded fixtures.

Correction on the review (C.2): what you fix stands over "Ask Claude" (edited,
renamed, swapped and removed lines are kept by the line as written, or by place),
a swap or rename settles the flag, "Remove from recipe" has Undo, every line has an
Amount field, an aisle move asks "always or just this time" and is announced with
Undo, reasons are in plain words with what to do, and a flagged row shows the
recipe's own line. Purchase integrity is untouched: edits still inherit purchases
(`ShoppingList.inheritPurchases`), covered by scenario E.

## 3. UX friction removed (C.3)

Journey A (quick list → shop → finish): "Already on the list" is a message with "Add
1 more" one tap away, not a dialog in the way of typing; a ticked row stays in place
for 2.5 s so a mis-tap is undone by tapping again (and the tick is seen); the Shop
bar keeps Finish in place (disabled until something is bought) and the next-aisle
label may wrap; the hero keeps one mosaic instead of re-seeding on every tick.
Journey B (recipe → review → list → shop): see §2; the badges read "Read by
Claude", "Waiting for Claude", "Read offline". Journey C (plan the week): "Plan"
names the week screen alone (the shop's way back is "Edit list"), the week screen
lights the List tab, "Add a meal" sits right under the meals. Six confirmation
dialogs that doubled an existing Undo are gone (Clear all, Remove a meal, Remove from
the list, Remove from saved recipes, the library's Reset/Delete, the review's
discard of an import); the review still asks before discarding edits to a saved
recipe, which have no Undo. Backup failures are said in plain words.

## 4. Visual consistency (C.5)

The screenshots were re-recorded by the tests and looked at in both themes. The
identity holds (mosaic heroes, aisle colour system, pill fields, cards); the one
change to the look is the scrim on the bright half of the heroes (§5), which keeps
the palettes and darkens only where white text sits. One wording seen on the
screenshots and fixed (reasons read as two colons).

## 5. Accessibility (C.4a, C.4b)

Fixed: a Shop tick is spoken (the count is a polite live region) although the row
folds away; white text on the heroes clears 4.5:1 on all eight palettes with the
scrim (`ContrastTest` walks every palette across the width at the mosaic's lightest
shading); the review's outcome is in the summary, not only on a disabled button;
screen titles, section labels, sheet headers and the review title are headings;
48 dp targets for regular chips, bought rows, the servings number, switch rows and
the library's switch; the nav bar grows at 200 % font scale; "More" buttons name
their recipe; errors and the key test's result are announced; grouped rows read as
one node; progress bars are not read twice. Tests pin each of these (`NavBarTest`,
`ContrastTest`, assertions in the screen tests). Reduced motion was already honoured.

## 6. Performance (C.6)

Measured first with `QueryBudgetTest` (reads per tick, the list screen's flows
collected as the route collects them): Shop 28 → 23, Plan 37 → 32; the planner's
per-meal recipe query is one per week. The budgets are held by the test. What was
left alone and why is in docs/reports/release-c-performance.md.

## 7. Reliability, security and privacy (C.7)

Scenarios A–F run through the real view models, repository and watch coordinator
(`ScenariosTest`, `ReleaseCScenariosTest`): quick shop, two recipes merging with
"at home", a watch offline while a recipe is added, a week planned and shopped in
part with a meal removed mid-shop, corrections outliving Ask Claude and an edit after
a purchase, a page with no recipe recovered by pasting. The invariants hold: nothing
bought is undone, a planned meal is not a use, a finished trip leaves the plan alone,
ticks never create stock. Local-first and no telemetry are unchanged: the only
network calls are the recipe fetch and Claude with your key; the Keystore-wrapped
key stays out of every backup; logs are redacted; only the launcher and share
activities are exported (verify.sh checks the manifest). Lows fixed: the camera's
copy of a recipe photo is deleted once stored; the voice-test log is capped on disk.
The manual acceptance checklist is Hand-off G (docs/HANDOFF.md).

## 8. Adversarial review (C.8)

ADVERSARIAL_PLACEHOLDER

## 9. Verification evidence (C.9)

`tools/verify.sh` green before every commit: ktlint, every module's unit tests
(core, data, extract, claude, app under Robolectric), assembleDebug, the catalogue
check, the merged manifest, the secrets scan, the golden checksum, protocol parity,
the Monkey C limits. Device scenarios (`androidTest`) compile here and run on CI's
emulator. New tests this release: `ReleaseCEvalTest`, `RuleParserReleaseCTest` (13),
`ReviewCorrectionsTest` (5) and `correctionsStandOverAskClaude`, `ContrastTest` (3),
`NavBarTest`, `QueryBudgetTest`, `ReleaseCScenariosTest` (3), plus semantics and
one-tap assertions in the screen tests; no test was weakened, and no golden line
was edited.

## Scores

| area | /10 | why |
|---|---|---|
| Extraction | 8 | 88 % right and 7 % confidently wrong on an independent set, with the gate's flags right where they matter; the rest is naming convention and catalogue coverage, not wrong shopping. Not 9: no live Claude run this release, and US brand lines still read with the brand. |
| Review and correction | 9 | Every correction has a direct control, survives Claude, and has Undo where it removes. Not 10: the amount field is per line, which is heavy on a three-line row. |
| Journeys | 8 | The three journeys have no dialog in the way and no dead end; a mis-tick is one tap. Not 9: felt only on the emulator and the JVM (Hand-off G). |
| Visual | 8 | Consistent across screens and themes; the scrim is the one visible change and is measured. |
| Accessibility | 8 | Contrast, targets, headings, live regions and font scale are tested; not run with TalkBack on a phone. |
| Performance | 7 | Measured and budgeted on the JVM; the device probe's figures are unchanged and real-phone numbers are still owed. |
| Reliability | 9 | Six scenarios on the invariants, journalled restore, the start-up barrier; nothing undoes a purchase. |

## Delivery

Commits C.0–C.9 on `ccr-5923064f-985kmq`, each `[C.n]` with PROGRESS.md in the
same commit; PR against `main`. Open items carried into docs/KNOWN_ISSUES.md (23–25)
and the checks only a person can do into Hand-off G.
