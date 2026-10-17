# HyperShop — progress (Run 1)

Legend: `[ ]` todo · `[x]` done · `[~]` stubbed · `[!]` blocked. Each item notes its check command.
Find a sub-step's commit with `git log --grep '^\[<id>\]'`.

## Step 0 — Toolchain and scaffolding
- [x] 0.1 Repo files (.gitignore, README, PROGRESS, HANDOFF, KNOWN_ISSUES) — check: `ls README.md PROGRESS.md docs/HANDOFF.md docs/KNOWN_ISSUES.md`
- [x] 0.2 Android SDK + SessionStart hook — check: `java -version; echo $ANDROID_HOME; sdkmanager --list_installed` (36 platform, build-tools 36.0.0, platform-tools installed). Note: `ANTHROPIC_API_KEY` is absent in this container (see KNOWN_ISSUES #1)
- [x] 0.3 BangGym build setup copied (ADR 000, BangGym @6a4b9d1); modules :app :core :data :extract :claude; minSdk 34; debug keystore; watch UUID 41c79afd24ac47168df768ef92d1bac0 — check: `cd android && ./gradlew --max-workers=1 assembleDebug`
- [x] 0.4 HyperShop libraries + hello tests (JUnit5+kotest, Robolectric Compose semantics, Roborazzi NATIVE, AppContainer sdk 34+36, MockWebServer); versions frozen (ADR 000 addendum; Robolectric 4.16.1 for SDK 36) — check: `tools/gradle.sh test`
- [x] 0.5 tools/verify.sh (ktlint, all tests, assembleDebug, merged-manifest check, sk-ant- scan, golden CHECKSUM, parity + sync-model when present) + CI workflows (pushed OK in 0.3) — check: `tools/verify.sh` (cold build dirs: green)

**Step 0 check:** cold `tools/verify.sh` passes.

## Step 1 — Spec, enums, golden data, model, catalogue, sync codec
- [x] 1.1 docs/parser-spec.md (schema R1, QtyUnit R2, AisleCode R9, prep/identity R4, NYT mapping R10, scoring R11) — check: review the doc
- [x] 1.2 Golden data: 80 NYT lines (seed 20261017; 25/20/15/20 strata; 20 held out, listed in docs/golden-held-out.md; 25 overrides citing spec rules) + 25 UK lines; CHECKSUM + CHANGES.md — check: `python3 tools/pick-golden/pick_golden.py && python3 tools/nyt-to-golden/nyt_to_golden.py && git diff --exit-code testdata`; `sha256sum -c testdata/ingredients/CHECKSUM`
- [x] 1.3 :core model: Rational (exact), Dimension, QtyUnit (+ locale factors), UnitLocale, AisleCode, QtyBase (+ presence), ParsedIngredient, RecipeImport, Coverage (ADR 002) — check: `tools/gradle.sh :core:test`
- [x] 1.4a Top-400 NYT names script (tools/catalog/top_names.py → catalog/top-names.csv; prep/size stripped, salt/pepper folded, compounds split, plurals merged) — check: `python3 tools/catalog/top_names.py && git diff --exit-code catalog/top-names.csv`
- [x] 1.4b Catalogue CSV: catalog/ingredients.csv, 431 rows, 17 aisles, 5 staples (salt, black pepper, olive oil, vegetable oil, water), 20 pack notes, src=agent-estimate; covers 392/400 NYT top names (the 8 misses are non-names like "to taste") — check: validator in 1.4c
- [x] 1.4c Validator (tools/catalog/build_catalog.py + CatalogTest), generated catalog.tsv + lazy CatalogLoader, synonyms.csv, SOURCES.md, LICENSE-NYT, NOTICE-NYT, REVIEW.md generator — check: `python3 tools/catalog/build_catalog.py --check; tools/gradle.sh :core:test`
- [x] 1.5a Sync codec (:core sync/): WatchProtocol.kt (short keys, VERSION 1, WATCH_APP_ID), typed messages, untrusted-input decoder, PayloadSize estimator + PayloadCap (default 384 B × 16 pages), SnapshotPager (acks reserved, truncation order) — check: `tools/gradle.sh :core:test` (CodecTest, PagerTest incl. property test: every page under the cap)
- [x] 1.5b Protocol rules (PhoneSyncModel, WatchSyncModel; docs/garmin-protocol.md), 16 vectors in testdata/sync replayed through the real codec + pager (SyncVectorTest), tools/sync-model exhaustive checker (S1–S7 safety, C1 convergence; --small = 3 runs, ~113k states, 0 violations; mutation-checked) — check: `tools/gradle.sh :core:test`; `python3 tools/sync-model/model.py --small`. Design fixes found while writing vectors: ack-only replies set `helloDue` + acked-ok overlay (no flicker/stale view); a hello after epoch adoption voids discarded seqs so go-back-N has no gap

**Step 1 check:** model + catalogue validator unit tests, codec round-trips with every page under the cap, all listed sync vectors, model checker 0 violations.

## Step 2 — Rule parser
- [x] 2.1 Tokeniser: one pass, no regex (ints, decimals, 1,000, slash and unicode fractions, glued 1½, letter/digit split); 10 KB pathological lines < 50 ms — check: `tools/gradle.sh :core:test --tests "*TokeniserTest*"`
- [x] 2.2 Grammar: qty (mixed, ranges, number words), sizes, units (R2.1 T/t, fl oz, R2.4 other), alt amounts (parens, container, x, slash), name/note (prep vs identity backed by the catalogue, postnominal phrases), flags, section headers; check on R7 lines — check: `tools/gradle.sh :core:test --tests "*RuleParserTest*"`
- [x] 2.3 Normaliser (R5): catalogue lookup by name/plural/synonym, singular head noun when catalogued, `cat:<id>` or `name:<norm>` mergeKey; shared with Claude post-processing — check: `tools/gradle.sh :core:test --tests "*NormaliserTest*"`
- [x] 2 Check: GoldenScoreTest gate (simple + comments_parens, not held out, 35 lines): name 100%, qty 100%; held-out and info strata in docs/reports/parser-golden.md. Caveat: the parser was tuned while reading the 35 gate lines; the 20 held-out lines (also 100% on these strata) are the honest check — check: `tools/gradle.sh :core:test --tests "*GoldenScoreTest*"`

## Step 3 — Categoriser and merge
- [x] 3.1 Categoriser (override → identity words → catalogue → Claude suggestion → head noun → keywords → Other); 97% of the 105 golden names categorised (≥ 85%) — check: `tools/gradle.sh :core:test --tests "*CategoriserTest*"`
- [x] 3.2a Contributions: ShoppingList (items, uses, contributions, ticks, aliases, revs) + Merger.preview contribution building: servings scaling (range → upper bound), noScale, presence lines, "I have some" subtracted earliest line first, in-stock defaults (staples, optional, garnish), manual add / edit quantity, must-not-merge pairs — check: `tools/gradle.sh :core:test --tests "*ContributionsTest*"`
- [x] 3.2b Covered/DONE, tick (covers all uncovered), untick (exactly the last tick), finish shopping (soft delete + 7-day archive) with undo; property tests: a tick always makes DONE, a later recipe is uncovered, untick restores exactly — check: `tools/gradle.sh :core:test --tests "*Ticking*"`
- [x] 3.2c Display: counts round up (produce to ½), friendly mass/volume steps, ½ ⅓ ¼ glyphs, metric/imperial/as-written, gramsPerCup combining else compound amounts, plural names, pack-note sub-line, "1 more onion" / "2 already ticked" — check: `tools/gradle.sh :core:test --tests "*DisplayTest*"`
- [x] 3.3 Merger.preview (pure) + apply re-checks every shown rev (and that new items are still new) → Stale; property tests: add then remove a use is identity, preview+apply equals merging directly — check: `tools/gradle.sh :core:test --tests "*Preview*"`
- [x] 3.4 Catalogue re-key: name: → cat: keys; clashes merge into the lower id (survivor aisle/position win, rev = max + 1, contributions and ticks move, ItemAlias) — check: `tools/gradle.sh :core:test --tests "*RekeyTest*"`

**Step 3 check:** property tests (add/remove identity, tick → DONE, later recipe uncovered, untick restores, preview = direct merge), must-not-merge pairs, 97% golden names categorised — all in `tools/gradle.sh :core:test`.

## Step 4 — Claude client
- [x] 4.1 ADR 001: OkHttp + kotlinx.serialization, no SDK (docs/decisions/001-claude-http.md) — check: read the ADR
- [x] 4.2 Request builder: model ids + cost table (Haiku 5.5 default), effort "low" always, no thinking param, fallbacks "default" + beta header only for Sonnet/Opus 5.5, strict schema from QtyUnit/AisleCode (all required, nullable via anyOf), prompt rules, <untrusted_content> wrapping, ~30k cap — check: `tools/gradle.sh :claude:test --tests "*ClaudeRequestTest*"`
- [x] 4.3 Response handling: stop_reason first (refusal → Refused/rule parser, max_tokens → error), strict decode of the schema output (missing/extra fields rejected), Kotlin range checks (bad numbers dropped + check), verbatim https source_urls only, 401/403 bad key, 429/529 back-off ×2 honouring retry-after, 5xx retried once, IOException → offline, usage (incl. cache tokens) to a UsageSink, RingLog (500 lines) with key/header/query redaction, bodies never logged; GET /v1/models for Test key — check: `tools/gradle.sh :claude:test --tests "*ClaudeClientTest*"`
- [x] 4.4 Spend control: SpendGuard (monthly cap $2 incl. cache tokens and the next call's estimate, 50 calls/day), cost table per model (Haiku ≈ $0.001/import, Sonnet 20×, Opus 40×), unknown fallback models priced high, GuardedClaude wrapper — check: `tools/gradle.sh :claude:test --tests "*SpendGuardTest*"`
- [!] 4.5 Live smoke test — **blocked: no `ANTHROPIC_API_KEY` (or any Anthropic credential) in this container** (KNOWN_ISSUES #1). `LiveSmokeTest` is written (one call per model, redacted results to testdata/claude/live-*.json) and skips without a key — check: `tools/eval-claude/run.sh` with a key
- [!] 4.6 tools/eval-claude — **blocked: same reason**. `ClaudeEvalTest` + `tools/eval-claude/run.sh` written (Haiku/Sonnet/Opus 5.5 + rules on frozen 80 + UK 25, per-field accuracy, cost, output tokens → docs/reports/eval-claude.md); page fixtures join once 5.1 adds them. Not run, so no eval report is committed — check: `tools/eval-claude/run.sh`

**Step 4 check:** MockWebServer tests cover every stop_reason (end_turn, refusal, max_tokens, other) and every error (401, 403, 429, 529, 5xx, 4xx, offline, invalid/schema-violating output): pass. Live smoke test and eval report: **not done** (blocked, above).

## Step 5 — Android app (phone only)
- [x] 5.1 :extract: SafeFetcher (https only, DNS filter for loopback/RFC1918/link-local/CGNAT/ULA/multicast, raw IPs and non-443 refused, manual redirects ≤5 re-checked, no cookies, 10 s/30 s, html or ld+json only, 3 MB after gunzip); Extractor (WPRM → Tasty → JSON-LD → microdata → heading → page text → manual) with every JSON-LD quirk tested; 12 live fixtures + 4 synthetic + 3 hostile (testdata/pages/SOURCES.md) — check: `tools/gradle.sh :extract:test`
- [x] 5.2a Schema v1 (§4, exported to android/data/schemas/1.json; enums by name; Meta.nextId added; mergeKey unique among live items via a partial index; RecipeUse CHECK via triggers), Callback.onCreate seeds aisles, store 1 + AisleOrder, 5 staples, Meta; EpochMarker (noBackupFilesDir) → new epoch + watch installs cleared on mismatch. The repository (ListRepository, one transaction per op over the pure ShoppingList) lands here too — check: `tools/gradle.sh :data:testDebugUnitTest --tests "*SchemaTest*"`
- [x] 5.2b Repository: apply preview (rev re-check → Stale, LineDecision rows), tick/untick, manual add (one MANUAL use), edit quantity, move aisle (+ "always" override), already-on-list — check: `tools/gradle.sh :data:testDebugUnitTest --tests "*RepositoryTest*"`
- [x] 5.2c Remove use, Undo of an import (database equal apart from revs, recipe removed when unused), rescale, finish shopping + undo, soft delete + undo + 7-day purge — check: `tools/gradle.sh :data:testDebugUnitTest --tests "*UsesTest*"`
- [ ] 5.2d Re-key, apply watch ops
- [ ] 5.3 App shell (container, WorkManager, nav, theme, import worker, WatchLink fake)
- [ ] 5.4 Review screen
- [ ] 5.5 Review row sheet
- [ ] 5.6a List
- [ ] 5.6b List continued
- [ ] 5.7 Item sheet + Recipes tab
- [ ] 5.8 Settings
- [ ] 5.9 Share intent, paste chip, error states
- [ ] 5.10 Debug-only tools, backup rules
- [ ] 5.11 tools/contact-sheet
- [ ] 5 Check: verify.sh green; APK link in HANDOFF.md
- [ ] Hand-off A checklist written
