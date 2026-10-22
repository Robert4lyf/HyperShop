# Release C — exceptional polish and real-world quality

Not a feature release. Refine what Releases A and B built until the app is accurate,
intuitive, responsive and polished; keep the invariants they established (purchases
are never undone by an edit, Undo of a restore is guarded, snapshot pages name their
build, start-up waits for recovery, the plan and the list are separate, history and
household decisions are kept).

## Baseline audit (main at 06e4126)

Five read-only reviews (extraction, UX and visuals, accessibility, performance and
security) over the code and the recorded screenshots, plus a new independent
extraction dataset. Evidence is cited as file:line in the sections below; the full
notes are condensed here.

### Strengths (keep)

- The hybrid extraction: rules first, Claude only on a flagged line. 97 % / 98 % on the
  190 lines it keeps (docs/reports/hybrid-gate.md).
- Shop mode's mechanics: the slim pinned bar, the 52 dp tick separate from the detail
  tap, bought rows folded per aisle, "Next" within reach, no snackbar per tick.
- The list's aisle cards, badge and colour system, typography, both colour schemes,
  the tab bar, empty states, the item sheet, the Add-recipe sheet, Library and
  Settings; the review row's one-tap "At home" pill and the live "Read as:" preview.
- Data integrity: `ListInvariantsTest`, `PlanInvariantsTest`, journalled restore,
  the start-up barrier, purchase carry-over on edits.
- Security: Keystore-wrapped key excluded from every backup path, redacted logs,
  only the launcher activity exported, SafeFetcher's SSRF guards.

### Confirmed defects and weaknesses

**Extraction** (release-c-eval baseline, 285 independent lines: 72 % right; 47 of 243
accepted lines wrong, i.e. 16 % confidently wrong; 41 % of wrong readings flagged):

| # | Defect | Evidence |
|---|---|---|
| X1 | A quantity written after the name is never read ("Spinach 8.8 oz", "mince 500g", "bananas x6") | 19 eval lines, all flagged "an amount it couldn't read" |
| X2 | A length is read as a count ("5cm piece of ginger" → 5 of "cm piece of ginger") | rc-070, rc-110; `RuleParser.kt` has no length unit |
| X3 | "a/an" after a fraction becomes the name ("½ a bunch of coriander" → "a bunch of…") | rc-071, rc-090 |
| X4 | Size + container word outside R2 stays in the name ("125g ball mozzarella", "250g tub mascarpone") | rc-019; `RuleParser.kt:143-155` |
| X5 | Unknown size/qualifier before a unit joins the name ("2 fat cloves garlic", "1 rounded tsp") | rc-101, rc-115 |
| X6 | Prep verbs without a comma stay in the name ("chicken breasts cut into cubes", "large egg whites") | rc-005, rc-045, rc-141 |
| X7 | Decilitres unknown ("2 dl water" → 2 of "dl water") | rc-223–227 |
| X8 | "1 tsp each salt, cumin…" → "each salt"; " - " not a separator ("cheddar - big block") | rc-242, rc-277 |
| X9 | A unit after a non-amount parenthetical is lost ("1 (1o-oz.) package lasagna noodles") | rc-179; `RuleParser.kt:133-141` |
| X10 | A metric amount anywhere in the note overrides the measured one ("3 tbsp butter (from a 250g block)" → 250 g) | `ParsedIngredient.kt` `METRIC_IN_NOTE` unanchored |
| X11 | Cooked grains by the cup are weighed with the dry density ("4 cups cooked rice" → 740 g) | `Display.kt:265-270` |
| X12 | "A or B" where A is itself an ingredient picks the wrong product ("chicken or vegetable stock" → chicken); flagged, so caught when keyed | `RuleParser.kt:428` |
| X13 | Catalogue gaps and spelling variants split items: "self raising flour", "free range eggs", "green chilli", "peppers" (→ black pepper), "cardamom pods", "paprika powder" | eval name/catalogue columns |
| X14 | Several items on one line pass unflagged ("Toppings of choice: cheddar, avocado, lettuce") | rc-170 |

**Review and correction** (ReviewScreen/ReviewRowSheet/ReviewViewModel):

| # | Weakness | Evidence |
|---|---|---|
| R1 | "Ask Claude" re-reads the raw lines and discards edited, swapped, renamed and removed lines | `ReviewViewModel.kt:562-584`, `Importer.kt:123,135,163` |
| R2 | A swap keeps the "Check: A or B" flag and the "1 to check" count | `ReviewViewModel.kt:772-775` |
| R3 | "Remove from recipe" has no Undo and reads as a link | `ReviewViewModel.kt:885-896`, `ReviewRowSheet.kt:241-245` |
| R4 | Amounts can only be corrected by editing the raw text with the cursor | `ReviewRowSheet.kt:215-255` |
| R5 | An aisle move closes the sheet with no feedback; "always" must be switched before the chip | `ReviewRowSheet.kt:110-113,274-281` |
| R6 | Check reasons are terse ("has 'or'"); the original line is only visible inside the edit field | `ReviewScreen.kt:707-713` |

**Journeys and messages:**

| # | Weakness | Evidence |
|---|---|---|
| U1 | "Plan" names two places (the list's planning mode and the week screen) | `ShopScreen.kt:178-182` vs `ListHeader.kt:232` |
| U2 | A mis-tick in Shop mode needs expand + untick; the tick animation never plays | `ShopScreen.kt:245-263` |
| U3 | The list hero changes palette on every tick ("5 left" seeds the mosaic) | `ListScreen.kt:412`, `LowPoly.kt:123` |
| U4 | Six confirmation dialogs double an existing Undo | ListScreen 483, MealsRoute 203, RecipesRoute 352/377, ReviewRoute 397, LibraryScreen 473 |
| U5 | The Shop bottom bar jumps on the first tick; "Next: Dairy & E…" truncates | `ShopScreen.kt:137-143` |
| U6 | Opening Plan from the List tab lights the Recipes tab | `HyperShopApp.kt:729-732` |
| U7 | Exception names and validator text reach the user ("Couldn't restore (IOException)") | `SettingsViewModel.kt:299,255,306` |
| U8 | "+1?" dialog interrupts quick typing | `ListHeader.kt:293-307` |
| U9 | The Plan screen's "Add a meal" picker sits ~3 screens down with 8 meals and 15 chips | `MealsRoute.kt:196,528-566` |

**Accessibility:**

| # | Gap | Evidence |
|---|---|---|
| A1 | White text on the bright end of the mosaic falls to 2.4–3.9:1 | `Components.kt:179-191`, `LowPoly.kt:107-125` |
| A2 | A Shop tick removes the focused row; TalkBack hears nothing | `ShopScreen.kt:257-260` |
| A3 | The review's outcome is stated only by a disabled button at ~2.2:1 | `ReviewScreen.kt:172-194` |
| A4 | Targets under 48 dp: regular chips, bought rows, review servings, sheet switch rows, library switch | MealsRoute 414, ShopScreen 345, ReviewScreen 534, ReviewRowSheet 166, LibraryScreen 394 |
| A5 | Nav bar fixed at 60 dp clips labels at 200 % | `HyperShopApp.kt:277` |
| A6 | No headings on screen titles and section labels; identical "More" buttons; errors not announced; rows not grouped | Components 149-189, RecipesRoute 533/590, LibraryScreen 442, SettingsRoute 579, MealsRoute 487-521 |

**Performance:** every Shop-mode tick also recomputes the invisible "This week" card
(two extra transactions, ~15+N queries) (`ListRoute.kt:36`, `ListViewModel.kt:223-243`,
`BuyAgain.kt:47,61`, `Planner.describe`); the probe's tap-to-visible figure includes
the fold animation; archived uses are never deleted; the voice-test log is unbounded.

**Security:** nothing high. Low: the camera shot in `cacheDir/camera` is not deleted
after import; photos of purged jobs are swept only on the next save.

### Areas to leave alone

Shop mode's layout and mechanics; the aisle badge and colour system; typography and
colour schemes; the tab bar; the Recipes tab's cards; the Add-recipe sheet; Library;
Settings; empty states; the staged loading with "Paste the ingredients"; snackbar
placement; the data layer's transactions and invariants; the watch protocol.

## Plan (in order; each step verified with `tools/verify.sh` before its commit)

| Step | Priority | What |
|---|---|---|
| C.0 | — | This audit; the independent dataset `testdata/eval/release-c-2026.json` (285 lines, provenance in `release-c-2026-SOURCES.md`) and `ReleaseCEvalTest`, which scores the whole pipeline field by field and reports the gate's confusion table |
| C.1 | P0 | Parser: X1–X10, X12 (prefer the shared head), the Uncertainty rules the audit measured (size-then-container, container word in a name, a list of several items, a unit word inside a name, a bare "pepper"); catalogue synonyms for X13; display X11. Baseline and after on the same set, no label edits except documented spec corrections |
| C.2 | P0 | Review: edits survive "Ask Claude" (R1), a swap clears the flag (R2), Undo for a removed line (R3), amount fields on the sheet (R4), aisle feedback (R5), plain-language reasons with the original line visible (R6) |
| C.3 | P0 | Journeys: one name for the week screen (U1), a ticked row stays a moment so tapping again undoes it (U2), a steady hero palette (U3), dialogs replaced by Undo where safe (U4), a steady Shop bar (U5), tab lighting (U6), plain messages (U7), "+1?" without a dialog (U8), the Plan picker within reach (U9) |
| C.4 | P0 | Accessibility: A1–A6 with semantics tests (headings, 48 dp targets, font scale 2.0, the Shop live region) |
| C.5 | P1 | Visual pass from the re-recorded screenshots in both themes |
| C.6 | P1 | Performance: gate the week card on Plan mode, drop the redundant load in Buy again, batch the planner's recipe reads, measure before and after; purge archived uses; cap the voice log; tidy the camera file |
| C.7 | P0 | Integration scenarios A–F where gaps exist; the manual acceptance checklist |
| C.8 | P0 | Adversarial review; fix what it finds |
| C.9 | — | Reports, PROGRESS, KNOWN_ISSUES, HANDOFF, PR |
