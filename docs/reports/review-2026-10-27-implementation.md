# The review of 2026-10-27, implemented (Step Y)

Branch `ccr-5923064f-985kmq` from `main` at 1411182. The review itself (two parts: usefulness
and UX; a deep bug hunt) is docs/reports/review-2026-10-27.md, as delivered. This report says
what was done about every item in it, where a judgement differed from the review's wording,
and what only a phone or a watch can confirm. Progress: PROGRESS.md › Step Y, one gated commit
per sub-step (`tools/verify.sh` green before each). Nothing in this step has run on a real
phone or a Forerunner 245.

## 1. The bugs (Part 2, 37 findings)

| # | Finding | Done | Where |
|---|---|---|---|
| A.1 | Edit over-asks when an ingredient was bought on two rows | fixed: inheritance runs per ingredient, the new need reduced once | Y.1 `ShoppingList.inheritPurchases`, `RegressionTest` |
| A.2 | A backup from a normal database refused on restore (DeletedItem/alias rows at nothing) | fixed: prunes persist, export filters, dangling rows cleaned at purge | Y.2 `purgeDeleted`, `exportBackup`, `BackupTest.anExportAfterRowsWentForGoodStillRestores` |
| A.3 | Edit after a finished trip leaves a ghost live row | fixed: emptied rows go with their ticks | Y.1 |
| A.4 | A dropped ingredient comes back on a servings change | fixed: its bought share is `noScale` | Y.1 |
| A.5 / D.35 | Watch tick on a "Not available" item leaves it set aside | fixed: `watchTick` clears it as a phone tick does | Y.1 |
| A.6 | Shelf arrangement lost on a key change | fixed: ranks renamed on swap and catalogue re-key | Y.2 `ShelfOrder.renamed`, `renameShelfRanks` |
| A.7 | The 7-day purge can remove a live use's bought share | fixed: a deleted row with a live use's contribution waits for the use to be archived | Y.2b `purgeDeleted`, `UseProgressTest` |
| A.8 | Two quick Arrange taps lose a move | fixed: read-move-write in one transaction | Y.2 `arrangeWith` |
| A.9 / D.36 | Validator gaps; 64-bit ids on the wire | fixed: caps and cross-checks in the validator; a hello's `at` clamped; rows past 32 bits left off the wire | Y.2, Y.3 |
| B.10 | A Claude answer paid for after Stop is thrown away | fixed: laid over when it lands (`pendingAnswer`) | Y.6 |
| B.11 | System Back on an edit discards without asking | fixed | Y.6 |
| B.12 | "This week" stale across Sunday midnight | fixed: `refreshWeek` on resume and re-subscription | Y.6 |
| B.13 | Library "Add" asks twice; phantom ready card | fixed: one prompt, in the review; dismiss drops the throwaway job | Y.6 |
| B.14 | Finish with "Not available" rows stays in shop mode | fixed | Y.6 |
| B.15 | A sheet edit resets other lines' drafts | fixed: editors keyed by `LineOrigin` | Y.6 |
| B.16 | Aisle/clear-all Undo restores the whole map | fixed: only the rows changed | Y.6 |
| B.17 | Duplicate check on a stale list | fixed: adds serialised, read from the repository | Y.6 |
| B.18 | Review over Settings › Library pops Settings | fixed | Y.6 |
| B.19 | Aisle pill disagrees with the top bar for 2.5 s | fixed: counts by state | Y.6 |
| B.20 | "Try again" navigates after leaving | fixed | Y.6 |
| C.21 | List-marker offsets corrupt notes and "(optional)" | fixed: the marker is blanked, not cut | Y.4 |
| C.22 | "1-inch piece ginger" | fixed | Y.4 |
| C.23 | "juice from 1 lemon" | fixed | Y.4 |
| C.24 | "10 packs basil" on the review | fixed: a counted presence item is the name alone, as the list row | Y.5 |
| C.25 | Honey by the cup comes out as one jar | fixed: "one" only while the pack's size is unknown | Y.5 |
| C.26 | "1 tin beans" → dried beans | fixed: `Shelf.of` re-shelves dried pulses for a tin; catalogue row `beans_tin` | Y.4 |
| C.27 | Trailing amounts unchecked ("Serves 4") | fixed: flagged when the name is unknown to the catalogue | Y.4 |
| C.28 | Division by zero | fixed: a zero amount is flagged, never divided by | Y.4 |
| C.29 | "7g fresh yeast" | fixed: catalogue row `fresh_yeast` (Chilled) | Y.4 |
| C.30 | "lean minced beef" | fixed: "lean" is a qualifier | Y.4 |
| C.31 | 910 g of onions is seven | fixed: a count from a typical weight gets a tenth of slack; the KDoc's six holds | Y.4 |
| C.32 | Usage under-recorded on an SSE error / a late answer | fixed | Y.4 `ClaudeClient` |
| C.33 | "pkg" | fixed | Y.4 |
| D.34 | Wrist ticks silently dropped until the next epoch (foreign acks) | fixed on both sides: an ack for a seq the watch never issued settles nothing; an unsolicited push to an install not heard from carries no acks; vector `ack-foreign-install`, traces regenerated, protocol doc | Y.3 |
| D.37 | `BackupManager.inspect` parses twice | fixed: one header decode, JSON bounded at 16 MB | Y.2 |

The three labelled extraction sets read exactly as before after the parser changes (their
baselines did not change under `HYPERSHOP_EVAL_UPDATE=1`); the basket evaluation's floors held.

## 2. Usefulness and UX (Part 1)

### Improvements 1–25

| # | Change | Done |
|---|---|---|
| 1 | Rules' reading at once while Claude works; Stop keeps the import | Y.7b (`askClaude`; a photo still waits) |
| 2 | Whole row ticks in the shop; Not available off the right thumb | Y.7c: the row ticks, details on a long press. The Not available icon stays where it was: with the row a tick, the icon is the one control that is not, and the 2.5 s linger covers a stray tap. |
| 3 | First run opens on Shopping | Y.7a |
| 4 | "Planned for this week" on the review | Y.7a (in the summary line) |
| 5 | "Waiting for Claude" only in flight; otherwise "Read offline" + reason | Y.7a |
| 6 | Row sheet order | Y.7b |
| 7 | Finish leaves shop mode and says what is left | Y.6 (B.14) + Y.7a |
| 8 | After "Add N meals", the home check | Y.9: the message says "· N to check at home" rather than scrolling (the section is one swipe below) |
| 9 | Capital at the start of the row only | Y.7a |
| 10 | Hero titles: three lines | Y.7a |
| 11 | Buy again's long-press said | Y.7a |
| 12 | One place for "the recipes on the list" | Y.7d: Recipes › "In this shop" went; its actions are on the Shopping tab's recipe cards |
| 13 | Drop the plan card | Y.7d |
| 14 | Compact saved cards | Y.7d (96 dp band, photo or mosaic alike) |
| 15 | Remove from your recipes out of the primary area | Y.7a |
| 16 | Drop "1 ×" | Y.7a |
| 17 | Arrange: move to top/bottom and a hint | Y.7d |
| 18 | "Later" keeps the card; Discard in the menu | Y.7b |
| 19 | Hint under Always at home | Y.7a |
| 20 | Review hint only until a few sheets opened | Y.7b |
| 21 | Month names keep their capital | Y.7a |
| 22 | Settings › Aisles as its own screen | Y.7d |
| 23 | A watch word in the shop header | Y.7c |
| 24 | "Done" as the shop's exit | Y.7a |
| 25 | Hero contrast on the brightest facets | Y.9: a soft shade sits under the title on every mosaic (header and cards), the title-only scrim the review offered as the alternative. Not measured on a phone (hand-off check 42). |

### Cuts 1–6

1. Recipes › "In this shop": gone (Y.7d). 2. The plan card: gone (Y.7d). 3. Voice test: debug builds
only (Y.7a); the microphone service stays declared for voice mode. 4. Model choice: **kept, moved
under Advanced** with its cost note (Y.7a) rather than deleted — the review's own evidence was
cost, not harm, and a cookbook photograph can need the bigger model. 5. "Group by type": gone; the
store order labels an aisle's groups whenever it has more than one (Y.7d). 6. Four ways to change
a line: the text and the name are behind "Edit the line" (Y.7b); dead `watchVibrate`: gone (Y.7a).

### What's missing 1–8

1. Keep the screen on in the shop: Y.7c. 2. "Cooked it": Y.9 (`RecipeNote.timesCooked`, migration
9→10; "your usual" sorts by it; Undo on the message). 3. Carry-over prompt after Finish: Y.7c.
4. A second store's walking order: Y.9 — Settings › Aisles picks the store to edit; once the second
store's order exists, "Shop" asks which store, and the list, the review and the watch follow it.
The per-item arrangement (X.9) is one for both stores (KNOWN_ISSUES 37). 5. "Ran out" on a staple:
Y.9 (the cart on an Always-at-home chip). 6. Find on a long list in shop mode: Y.7c. 7. A launcher
shortcut to Shopping with the Add field focused: Y.9. 8. A one-time nudge to add a key on the first
offline-read review: Y.9.

## 3. Judgement calls, in one place

- Model choice kept (cut 4), under Advanced.
- "Not available" stays a row icon (improvement 2); the row's tap is the tick.
- The home check is announced on the add's message (improvement 8), not scrolled to.
- Hero contrast by a shade (improvement 25), not by measurement.
- A.7 is fixed by holding the purge back while the use is live, not by changing progress.
- The second store has its own aisle order, not its own item arrangement (missing 4).

## 4. What the JVM cannot tell you

Hand-off H in docs/HANDOFF.md gains checks 35–43: the whole-row tick and the long press in a
shop; the watch word; the screen staying on; the carry-over prompt; the store picker and the
watch following it; "Cooked it"; the launcher shortcut; the key nudge; and D.34 on a real watch
(reinstall the watch app, tick, confirm the tick lands).

## 5. Delivery

Fourteen commits, `[Y.1]`–`[Y.9]` (Y.7 in four parts a–d; Y.8's cuts landed inside Y.7), each
with `tools/verify.sh` green: the catalogue check, the manifest check, ktlint, every JVM test in
`:core`, `:data`, `:claude` and `:app` (Robolectric), the sync model checker (`--small`), the
Monkey C limits and the protocol parity check. Database version 10 (schema 10.json committed).
Golden data untouched; the catalogue gained two rows (`beans_tin`, `fresh_yeast`).
