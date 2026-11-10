# Dependable shopping implementation — November 2026

Base: `8d4c733`. Work is isolated on `codex/dependable-shopping`; no merge or release is authorized by this branch. This is a review handoff, not a claim that every requested scenario has passed.

## Confirmed defects and corrections

- Release previously rebuilt an unverified APK and deleted earlier releases. Promotion now consumes the main push's verified artifact and requires exact-commit Android/emulator/watch checks. Assets are staged before latest changes, and rollback builds are retained.
- Unit chips previously changed 400 g into 400 kg. Compatible changes now convert exactly; incompatible dimensions require explicit confirmation of a new amount. Non-terminating fractions remain fractions instead of being rounded to two decimal places.
- Clearing an existing amount now reaches validation instead of silently retaining the old amount; keyboard Done uses the same nonblank-name requirement as the Save button. Regression coverage checks both paths and correction after rejection.
- Save previously launched several independent writes and immediately reported acceptance. A repository transaction now validates and applies related database changes, rejects stale revisions and unsafe rename-plus-total collisions, and queues library preferences durably. Success follows outbox completion. File replacement failure keeps the original library.
- Rename merges and catalogue rekeys could discard home amounts. They now retain both amounts and contribution provenance.
- Partial home amounts could deduct from deliberate manual extras, and could be spent again after a tick and servings increase. Home deductions now apply only to outstanding recipe requirements, and covered contributions record their home allocation separately from actual purchases.
- The at-home check excluded fresh/chilled/frozen ingredients. These are now available through an explicit full-food view; likely cupboard items remain the compact default.
- Final native UI verification exposed a repeated layout pass in the partial-home dialog when editing an existing amount. Explicit window width with side margins and a shorter field label resolve it. The regression now opens the actual This week flow and checks the prefilled amount, invalid text, visible backend rejection, retained draft and successful retry, with native rendering and automatic idling unchanged. The rejected state is recorded in `docs/screenshots/home-amount-dialog-light.png`; real IME acceptance remains open.
- This week's start button bypassed store selection. Both entry points now use one operation and chooser. Store names are editable without replacing profile rows or deleting their walking orders.
- A quick-add request could be emitted before the cold-start UI subscribed. The request now waits for its consumer, including widget entry.

## Design and convenience changes

Name and total requirement lead the item editor. Save is outside the scrolling content, above IME padding. Aisle/category/lasting preferences are expandable; sources, substitutions and quantity explanations remain reachable. Dirty-sheet Back and dismissal ask before discarding. Draft fields use saveable state and the open item is recorded in saved state.

Within-aisle arrangements and optional pack preferences belong to a store. A 650 g need can suggest two 400 g packs without changing contributions or recording a purchase. Shopping's navigation label is **Back to list**; the separate Finish operation retains purchase-history/Undo semantics. Unavailable is labelled and asks before setting an item aside. Duplicate tick accessibility semantics are suppressed.

The Gradle helper accepts an optional `GRADLE_PROJECT_CACHE_DIR`, so independent runner sessions can isolate project caches without changing dependency versions or the default local build.

A RemoteViews widget previews outstanding items and opens the normal validated add flow. Plain-text export uses Android sharing and aisle headings, excluding bought items. Manual-backup health records only a successfully closed export and its photo counts, in installation-local metadata. Cooking view shows saved ingredient text and personal notes, serving context and source access; its awake flag follows visibility/lifecycle.

## Requirement coverage

| Requirement | Implementation / evidence | Status |
|---|---|---|
| Exact-commit publishing, manual gate, stale/failed/cancelled checks | `tools/release/check_verified.py`, workflow guard tests | Implemented; actual GitHub publication intentionally not exercised |
| Promote verified bytes, modest artifact retention | One two-day main APK artifact; release downloads by run ID | Implemented |
| Known-good latest, previous builds, commit/version/change notes | Draft staging, final recheck, immutable build tags, no old-release deletion | Implemented; cannot recover releases the old workflow deleted |
| Phone/watch assets from same build | Watch packaging checks out verified SHA before publication | Implemented; signed watch package not verified without Garmin credentials |
| Debug-only voice harness and secure release signing | Variant source sets, optional private signing environment, release manifest check | Implemented; private signing/install remains unverified |
| Installed debug data/update path | Existing debug package/key retained; explicit side-by-side private-release transition | Preserved; see signing guide |
| Name/amount first, keyboard Save, expandable preferences | ItemEditor/ItemSheet, rendered UI tests | Implemented; real IME/large-text acceptance remains |
| Dirty drafts / recreation | Saveable fields, saved open item, Back/dismiss confirmation | Implemented; full navigation-path/device acceptance remains |
| Required/home/purchased/to-buy and sources | Item sheet explanation and per-use sources; actual purchase display excludes home allocation | Implemented |
| Unit conversion / precision | Exact rational conversion and focused tests; unsupported dimensions prompt | Implemented |
| Atomic item changes, stale guards, duplicate-save prevention | Repository transaction, revision comparison, saving state | Implemented |
| Recoverable library edits | Durable outbox, idempotent replay before startup Ready; backup waits for queued preferences | Implemented; failed-file-write recovery and idempotent replay tests pass |
| Rename collisions, purchases and home/provenance | Rename-only merge; combined rename+amount collision is rejected with an actionable message | Implemented |
| Full food-at-home view and combined needs | All aisle types available, cupboard default, combined rows | Implemented for currently eligible recipe shares |
| Home check after a line is partly bought | Check the uncovered recipe shares; Need all restores current requirements minus covered shares, preserving purchases | Implemented; focused regression included |
| Partial input/direct edit/inline validation | Existing amounts editable, numeric examples, invalid drafts remain visible | Implemented; backend rejection retains the amount inline for correction, with a UI regression test |
| Safe Undo / manual extras | Home change snapshot with revision guard; deliberate manual items excluded from deductions | Implemented |
| Consistent store start and remembered choice | Shared ShopStart service and chooser | Implemented |
| Named stores, per-store aisle/shelf order, migration/backup | Additive migrations and arrangement-version marker for older backups | Implemented |
| Selected store identity and watch order | Phone names the actual selected store; both phone and watch read its walking/shelf order; protocol/app ID unchanged | Implemented; physical reconnect acceptance pending |
| Finish summary / carry-forward / Buy again | Compact bought/carried-forward result, existing carry-forward prompt and history/Undo retained; navigation clarified | Implemented |
| Mixed-unit extras / unknown density / extra ingredients | Compatible locale-independent sums; unresolved compounds flagged with source retained | Implemented within current parser representation |
| Review correction / source / optional Claude / async safety | Existing occurrence-based correction/alignment and stop handling retained; existing tests exercised | No new AI; live assistance unverified without key |
| Fresh independently labelled evaluation | 20 synthetic UK-style lines labelled before tuning; model-authored, not human-adjudicated | Initial report only; now a regression set; unflagged ingredient-part/heaped-qualifier risks remain |
| Existing labels/baselines | Two demonstrable omitted-spoon corrections, separately documented; no lowered rule floors | See label-correction report |
| Corrections/recipe and time-to-list scenarios | Repeatable manual protocol provided | **Not measured; no live timings invented** |
| Weekly sections/progressive disclosure | Existing meal/extras sections retained; home filter and wrapping controls added | Focused refinement, not a complete redesign |
| Accessibility/layout | Labelled unavailable action, duplicate tick semantics removed, wrapping home actions, pinned Save | Automated/UI review plus real-device checklist required |
| Quantity explanation | Sources plus required/home/purchased/remaining values; item corrections in same form | Implemented |
| Store pack preferences | Optional, labelled suggestion; positive compatible single-measure calculation | Implemented with backup support |
| Android widget | RemoteViews list preview and quick add through normal UI | Implemented; launcher-specific checks unverified |
| Plain-text export | Share sheet, aisle grouping, bought rows excluded | Implemented |
| Backup health | Last successful manual export, included/missing photos, accurate Android-managed-backup caveat | Implemented |
| Cooking view | Saved ingredients/notes/context/source only; lifecycle awake flag | Implemented |
| Maintainability | Shop start, complete quantity validation and compound DB edit operations extracted/centralised | Focused changes; existing architecture retained |
| Performance before/after | Existing query-budget tests retained; historical device baseline remains in `performance.md` | **No new device timings; emulator acceleration unavailable locally** |
| Hardware scenarios | Phone/Forerunner 245 reconnect, private installation transition, TalkBack/IME/widget behaviour | **Unverified: devices/signing credentials unavailable** |

## Data and compatibility

Room advances from schema 12 to 16 through additive migrations: pending library-edit outbox; store-specific shelf keys; optional pack preferences; home portions attached to covered contributions. Existing amounts, contributions, recipe edits and purchase history are retained. Legacy covered rows infer their home allocation conservatively from the old on-hand amount. No protocol version, watch app ID, package ID, or installed debug signing key changes.

Backup additions have defaults. Old shared shelf arrangements are copied to each existing store during migration and old-backup restore. Exported new backups mark store-specific arrangements. Pending library writes must finish before an export can omit them; startup replays them before Ready. Export health lives in no-backup storage, so restore cannot masquerade as a new successful backup. The manual backup still excludes the Claude key. See `docs/releases/safe-builds.md` for installation/signing migration.

## Validation ledger

The final handoff records actual command results. Earlier intermediate failures were used to correct implementation, SQL test fixtures for the new column, and UI interactions for explicit unit/rename confirmations. No skipped check is represented as passing. No Android emulator or physical device run has been performed locally. No private release or live Claude results have been fabricated.

### Final continuation — 10 November 2026

`tools/verify.sh` passes with the restored JDK 21/Android SDK, the repository's frozen dependency versions and an isolated project cache. Runtime-only restoration and moving the cache out of memory-backed temporary storage resolved the missing compiler and build-daemon memory failure; no dependency versions were changed.

| JVM module | Passed | Skipped | Failed/errors |
|---|---:|---:|---:|
| core | 263 | 0 | 0 |
| extract | 35 | 0 | 0 |
| claude | 46 | 3 | 0 |
| data | 147 | 0 | 0 |
| app | 427 | 0 | 0 |
| **Total** | **918** | **3** | **0** |

The three skipped checks are `LiveSmokeTest.oneCallPerModel`, `ClaudeEvalTest.eval` and `ClaudeSetRecorder.recordTheSets`: live calls/recording require explicit credentials. They are not counted as passing or as new live-assisted evidence.

The gate also passed Kotlin formatting; debug, instrumentation-test and unsigned release APK builds; debug/release manifest checks; all seven release-promotion guard tests; catalogue generation; golden checksums; secret scan; protocol parity; and the small sync models (113,184 states, zero violations). The separate `:app:lintDebug` pass succeeds too. Watch debug/release and test-source compilation pass with SDK 9.2.0. These are device-independent compilations, with only the expected missing-device-definition warnings, not simulator executions or signed Forerunner builds.

All 427 app tests run with the revised home-amount scenario entered through This week. Native rendering, automatic idling and the original timeout remain enabled. The added invalid-entry, retained-rejection, reachable-button and successful-retry assertions pass. The new dialog screenshot and the refreshed 38-image contact sheet were inspected; they do not establish real-keyboard or large-font acceptance.

Accelerated emulator execution is unavailable locally (`/dev/kvm` is absent). Phone/watch reconnection, TalkBack/IME/launcher behaviour, private signing/install and live recipe/correction/timing measurements remain the acceptance items below. No release was published and no merge to main was performed.

Acceptance checklist: [2026-11-acceptance.md](2026-11-acceptance.md). Parser label rationale: [2026-11-label-corrections.md](2026-11-label-corrections.md). Release/signing: [safe-builds.md](../releases/safe-builds.md).
