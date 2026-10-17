# HyperShop — progress (Run 1)

Legend: `[ ]` todo · `[x]` done · `[~]` stubbed · `[!]` blocked. Each item notes its check command.
Find a sub-step's commit with `git log --grep '^\[<id>\]'`.

## Step 0 — Toolchain and scaffolding
- [x] 0.1 Repo files (.gitignore, README, PROGRESS, HANDOFF, KNOWN_ISSUES) — check: `ls README.md PROGRESS.md docs/HANDOFF.md docs/KNOWN_ISSUES.md`
- [x] 0.2 Android SDK + SessionStart hook — check: `java -version; echo $ANDROID_HOME; sdkmanager --list_installed` (36 platform, build-tools 36.0.0, platform-tools installed). Note: `ANTHROPIC_API_KEY` is absent in this container (see KNOWN_ISSUES #1)
- [x] 0.3 BangGym build setup copied (ADR 000, BangGym @6a4b9d1); modules :app :core :data :extract :claude; minSdk 34; debug keystore; watch UUID 41c79afd24ac47168df768ef92d1bac0 — check: `cd android && ./gradlew --max-workers=1 assembleDebug`
- [ ] 0.4 HyperShop libraries + hello tests; versions frozen
- [ ] 0.5 tools/verify.sh + CI workflows

## Step 1 — Spec, enums, golden data, model, catalogue, sync codec
- [ ] 1.1 docs/parser-spec.md
- [ ] 1.2 Golden data (pick-golden, nyt-to-golden, 80 + 25 lines, CHECKSUM)
- [ ] 1.3 :core model (Rational, Dimension, QtyUnit, QtyBase, ParsedIngredient, ADR 002)
- [ ] 1.4a Top-400 NYT names script
- [ ] 1.4b Catalogue CSV
- [ ] 1.4c Validator, generated resource + loader, SOURCES, LICENSE/NOTICE, REVIEW.md
- [ ] 1.5a Sync codec (envelope, ops, paged snapshot, estimator, WatchProtocol.kt)
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
