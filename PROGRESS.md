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
- [ ] 1.5b Protocol rules, testdata/sync vectors, tools/sync-model

## Step 2 — Rule parser
- [ ] 2.1 Tokeniser
- [ ] 2.2 Grammar
- [ ] 2.3 Normaliser, synonyms, mergeKey
- [ ] 2 Check: name ≥90%, qty ≥95% on simple+parentheses strata

## Step 3 — Categoriser and merge
- [ ] 3.1 Categoriser
- [ ] 3.2a Contributions
- [ ] 3.2b Covered/DONE, tick, untick, finish shopping
- [ ] 3.2c Display rounding, units, pack note
- [ ] 3.3 Merger.preview + rev check
- [ ] 3.4 Catalogue re-key + ItemAlias

## Step 4 — Claude client
- [ ] 4.1 ADR 001
- [ ] 4.2 Request builder
- [ ] 4.3 Response handling
- [ ] 4.4 Spend control
- [ ] 4.5 Live smoke test
- [ ] 4.6 tools/eval-claude report

## Step 5 — Android app (phone only)
- [ ] 5.1 :extract (safe fetch, extractors, fixtures)
- [ ] 5.2a Schema v1 + seeding
- [ ] 5.2b Repository: apply preview, tick/untick, manual add, edit qty
- [ ] 5.2c Remove use, undo import, rescale, finish shopping, soft delete
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
