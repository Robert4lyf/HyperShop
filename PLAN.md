# HyperShop — Plan

Recipe URL or Instagram reel → parsed ingredients → merged, aisle-sorted shopping list on Android, mirrored and tickable on a Garmin watch. Optional Claude API integration for harder parsing.

## 1. Architecture

```
 Chrome / Instagram ─share URL─▶ ┌──────────────── Android app (Kotlin) ────────────────┐
 paste URL / screenshots / video │ Import ─▶ Recipe extractor ─▶ Ingredient parser       │
                                 │   │        (JSON-LD/microdata)   (rule-based)         │
                                 │   └──▶ Claude extractor (optional) ──┐                │
                                 │        text / images / transcript    ▼                │
                                 │                     ┌── normalised ingredient list    │
                                 │                     ▼                                 │
                                 │        Merge engine ─▶ Categoriser ─▶ Room DB (list)  │
                                 │                                        ▲    │         │
                                 │                         WatchSync ◀────┘    ▼  UI     │
                                 └──────────────────────────┬────────────────────────────┘
                                                            │ Connect IQ Mobile SDK (BLE)
                                                ┌───────────▼───────────┐
                                                │ Garmin Connect IQ app │  list by aisle,
                                                │ (Monkey C)            │  tick items, offline
                                                └───────────────────────┘
          Claude API (api.anthropic.com) ◀── HTTPS, only when the user enables it
```

- **Local-first, no backend.** Everything runs on the phone. There are no accounts or hosting costs, and it works offline once a recipe is imported. The only network calls are fetching the recipe or reel page and, if the user turns it on, the Claude API.
- **Why not a web app:** a watch app can only reach a phone app through the Connect IQ SDK, or reach a server over the internet. A web app would need a server, accounts and sync, which adds cost for no user benefit in v1.

## 2. Tech stack

| Area | Choice |
|---|---|
| Android | Kotlin, Jetpack Compose, Material 3, min SDK 26 |
| Storage | Room (SQLite) + DataStore for settings |
| Networking / HTML | OkHttp + Jsoup |
| Serialization | kotlinx.serialization |
| DI | Hilt |
| Claude API | Anthropic Java SDK (`com.anthropic:anthropic-java`) from Kotlin; default model `claude-opus-5-5`, with a setting to switch to Sonnet 5.5 or Haiku 5.5 |
| Secrets | User's API key in EncryptedSharedPreferences, backed by the Android Keystore |
| Media | `MediaMetadataRetriever` for video frames; on-device `SpeechRecognizer` for audio transcripts |
| Watch link | Garmin Connect IQ Mobile SDK (`ciq-companion-app-sdk`) |
| Watch app | Monkey C, Connect IQ SDK 7.x, `watch-app` type |
| Tests | JUnit5 + golden-file tests for parser/merger; Compose UI tests |
| CI | GitHub Actions: Android lint/test/build + `monkeyc` build |

## 3. Core features (v1)

### 3.1 Import
- Two entry points: the Android **share sheet** (ACTION_SEND text/plain) and a **paste URL** field (with clipboard detection).
- Extraction order:
  1. `application/ld+json` schema.org `Recipe` → `recipeIngredient`. This covers most major recipe sites, including BBC Good Food, AllRecipes and NYT.
  2. Microdata/RDFa `itemprop="recipeIngredient"`.
  3. Heuristic fallback: a list under a heading that matches "Ingredients".
  4. Failure → show the page text and let the user select lines by hand.
- The **preview screen** comes before anything is added. It shows the recipe title, a servings scaler, and the parsed lines with checkboxes. Pantry staples (salt, pepper, water, oil) start unchecked, and the user can configure that list.

### 3.2 Ingredient parser
Turns `"2 ½ cups (300g) plain flour, sifted"` into `{qty: 2.5, unit: cup, alt: 300 g, name: "plain flour", note: "sifted"}`.
- Handles fractions/unicode fractions, ranges ("2–3"), "a pinch", "1 x 400g tin", and parenthetical alternate units.
- Normalises names: lowercase, strips prep words (chopped, diced, minced…), singularises, and applies a synonym map (coriander↔cilantro, courgette↔zucchini, spring onion↔scallion).
- Rule-based and deterministic, with a large golden test set (≥300 real lines).

### 3.3 Merge engine
- Key = normalised name.
- If the units share a dimension (mass, volume or count), convert and sum, then display in a sensible unit (1200 g → 1.2 kg).
- If the units can't be compared ("1 onion" + "200 g onion"), keep one item with a compound quantity: "1 + 200 g".
- Each item remembers which recipes contributed to it, so deleting a recipe removes its quantities.
- Merging into an already **ticked** item un-ticks it and shows only the new amount.

### 3.4 Aisle categorisation
- A built-in dictionary of roughly 1,500 ingredients → aisle: Produce, Bakery, Meat & Fish, Dairy & Eggs, Chilled, Frozen, Tins & Jars, Pasta/Rice/Grains, Baking, Spices & Condiments, Drinks, Household, Other.
- Lookup order: user override → exact match → head-noun match ("smoked paprika" → paprika) → keyword rules → Other.
- When the user moves an item to another aisle, that choice is **remembered** for future imports.
- Aisle order can be dragged per store profile (e.g. "Tesco Extra", "Local Lidl").

### 3.5 List UI
- Items are grouped by aisle in store order. Tap to tick, swipe to delete, and add items manually with the same parser.
- Ticked items sink to the bottom of their aisle. Actions: "Clear ticked", "Clear all", and undo snackbars.
- A recipes tab shows imported recipes, with a link back to the source and an option to remove one from the list.

### 3.6 Garmin app
- Shows aisle headers followed by items (name + short qty). Tick or untick with tap or the select button, and scroll with up/down.
- The list is cached in `Application.Storage`, so it works when the phone is out of range.
- Sync:
  - Phone → watch: sends a compact JSON payload on every change, capped at about 8 KB. Names are truncated and ticked items above N are dropped.
  - Watch → phone: sends tick events `{id, ticked, ts}`, queued and retried until acknowledged.
  - Conflicts: each item's state is decided by the latest timestamp.
- Device targets: a mid-range set to start with (Forerunner 255/265/965, Fenix 7, Venu 2/3, Vivoactive 5), then more.

### 3.7 Claude API integration (optional)
- **Settings toggle.** Off by default. When it's on, the user pastes their own Anthropic API key, which is stored encrypted and never leaves the device except in calls to Anthropic. The modes are:
  - *Off*: rule-based parsing only.
  - *Fallback* (recommended): Claude is used only when extraction fails or the rule parser has low confidence, e.g. unparsed lines or no quantity found.
  - *Always*: every import goes through Claude.
- **One call per import.** Each import uses the Messages API with **structured outputs** (`output_config.format`, a JSON schema), so there's no fragile text parsing. Schema:
  `{title, servings, ingredients:[{raw, name, quantity, unit, note, aisle_suggestion, optional}], source_urls[]}`.
  - The response is validated against the schema. Claude's results still go through the normal pipeline (normalise → merge → categorise). `aisle_suggestion` is used only for items the built-in dictionary doesn't know.
- **Model choice.** The default is Claude Opus 5.5 at `effort: "low"`, which is enough for extraction. The model can be changed in settings.
  - Requests include server-side refusal fallbacks (`fallbacks: "default"`), and the app checks `stop_reason` before reading content.
- **Inputs it accepts.** Pasted text, a page's cleaned text, images (screenshots or video frames, as base64 image blocks) and a speech transcript.
- **Cost:** Opus 5.5 is $4 per million input tokens and $20 per million output tokens.
  - A text-only import (~3k tokens in, ~1.5k out, thinking included) costs about **$0.04**.
  - A reel with 8 frames plus a transcript (~15k in) costs about **$0.10**.
  - Haiku 5.5 costs roughly 1/40th of that.
  - The app shows a running token/cost counter in settings.
- **Privacy note in the UI.** When enabled, the content of the recipe or reel is sent to Anthropic.

### 3.8 Instagram import (needs Claude)
Instagram has no public API for other people's reels and actively blocks scraping, so import is tiered, cheapest first:
1. **Share a reel link from Instagram to HyperShop.**
   - The app fetches the public page's metadata to get the caption (`og:description` / embed page). This is best-effort and may fail or return a truncated caption.
   - If the caption links to a recipe website, it runs the normal URL import instead. This is often the best source.
   - If the caption contains the ingredients, Claude extracts them from the caption text.
2. **If there's no caption, or no ingredients in it**, the preview screen offers:
   - **Screenshots.** Pick 1–10 images, e.g. the frames that show on-screen ingredient text. Claude vision reads them.
   - **Video file.** The user picks a saved reel or a screen recording. The app takes about 8–12 frames spaced across the video, plus a speech-to-text transcript made on the device, and sends both to Claude. The Claude API doesn't accept video or audio directly, so this conversion has to happen on the phone.
   - **Paste caption text** by hand.
3. Every result lands on the same preview screen for review. Quantities that Claude inferred from video or speech are marked "estimated".

Caveats: the caption fetch will break whenever Instagram changes its pages, which is why it's one tier and not the only path. The app will not download reel videos itself, because that breaks Instagram's terms and is unreliable. The user supplies the video.

## 4. Data model (Room)

- `Item(id, name, normName, aisleId, qtyJson, ticked, updatedAt, manual)`
- `Recipe(id, url, title, servings, importedAt, source: WEB|INSTAGRAM|MANUAL, parsedBy: RULES|CLAUDE)`
- `ClaudeUsage(date, model, inputTokens, outputTokens)` for the cost counter
- `RecipeContribution(itemId, recipeId, qtyJson)`. The item's total is derived from these plus any manual quantity.
- `Aisle(id, name, defaultOrder)`, `StoreProfile(id, name)`, `AisleOrder(storeId, aisleId, position)`
- `CategoryOverride(normName, aisleId)`

## 5. Repo layout

```
android/        Gradle project (app module + :core:parser, :core:merge, :core:catalog, :core:claude pure-Kotlin modules)
garmin/         Connect IQ project (manifest.xml, source/, resources/)
catalog/        ingredients.csv (name, synonyms, aisle) → generated into Kotlin at build time
testdata/       saved recipe HTML pages + ingredient-line golden files
.github/        CI workflows
```

The parser, merge and catalog code lives in pure Kotlin/JVM modules with no Android imports. That keeps them fast to test, and they can be reused later in a server or KMP iOS build.

## 6. Build plan (step by step)

Each sub-step ends in a check that can be run. A step is finished only when its **Done when** check passes. Commit after every sub-step, so a failed run can pick up from the last good commit.

### What the build machine can do
- The cloud container has Java and Gradle. It does **not** have the Android SDK or the Connect IQ SDK, and it has no emulator, phone or watch.
- The Android SDK can be installed (Step 0). The Connect IQ SDK may be blocked, because Garmin's download needs an account and a licence acceptance.
- Anything that needs real hardware or your accounts is marked **[YOU]** and can't be done by the agent.

### Things only you can provide
| Item | Needed for |
|---|---|
| Anthropic API key, to test Claude parsing | Step 5.6 |
| Android phone with developer mode on, for installing test builds | Steps 4.9, 6.6, 8.6 |
| Garmin watch, Garmin Connect app, and a Connect IQ developer key | Steps 7–8 |
| A few Instagram reel links and saved videos to test with | Step 6.6 |
| Play Console and Connect IQ Store accounts, to publish | Step 9.5 |

---

### Step 0 — Repo and toolchain
- **0.1** Create a `main` branch from this branch, so pull requests have something to merge into.
- **0.2** Add `.gitignore` (Android, Gradle, IDE, `local.properties`, `*.prg`, `developer_key*`), `README.md` and `LICENSE`.
- **0.3** Install the Android command-line tools: platform 35, build-tools 35, `ANDROID_HOME`. Add a SessionStart hook so future cloud sessions install them too.
- **0.4** Create the Gradle project in `android/` with a version catalog (`libs.versions.toml`), Kotlin 2.x, AGP 8.x and JDK 17. Modules: `:app`, `:core:model`, `:core:parser`, `:core:merge`, `:core:catalog`, `:core:extract`, `:core:claude`. The `core` modules are pure Kotlin/JVM.
- **0.5** Add ktlint/detekt and a CI workflow (`.github/workflows/android.yml`) running lint, unit tests and `assembleDebug`.
- **Done when:** `./gradlew build` passes in the container and on CI.

### Step 1 — Shared model (`:core:model`)
- **1.1** `Quantity` (amount: `Rational`; unit: `Unit?`), `Unit` (enum with dimension MASS/VOLUME/COUNT/OTHER and a factor to the base unit), and `CompoundQuantity` (a list of quantities that can't be combined).
- **1.2** `ParsedIngredient(raw, name, normName, quantity, altQuantity, note, optional, confidence)`.
- **1.3** `RecipeImport(title, servings, sourceUrl, source, parsedBy, ingredients)`.
- **1.4** `Aisle` enum with default order.
- **Done when:** the model compiles and the unit tests for `Rational` arithmetic and unit conversion pass.

### Step 2 — Ingredient parser (`:core:parser`)
- **2.1** Build the golden data set: `testdata/ingredients/*.tsv` with at least 300 real lines and their expected output. Include UK and US units, unicode fractions, ranges, "x 400g tin", "(300g)", "to taste" and "optional".
- **2.2** Tokenizer that handles numbers, fractions, unicode fractions, ranges, units (with aliases: tbsp/Tbsp/T/tablespoon(s)…) and parentheses.
- **2.3** Grammar: `[qty] [unit] [(alt qty unit)] name [, note]`, with sizing words (large/small), container words (tin/can/packet/clove/bunch) and "of".
- **2.4** Normaliser: lowercase, strip prep words, singularise (inflector plus an exceptions list), synonym map from `catalog/synonyms.csv`.
- **2.5** Confidence score: below 0.6 if any words go unparsed, there's no name, or the unit is unknown. Claude fallback (Step 5) uses this score.
- **Done when:** at least 95% of golden lines match exactly, and the failures are listed in a report file.

### Step 3 — Catalogue, categoriser and merge (`:core:catalog`, `:core:merge`)
- **3.1** `catalog/ingredients.csv` with about 1,500 rows (`name, synonyms, aisle`). Generate a first draft, then review it by hand for UK supermarket aisles.
- **3.2** Gradle task that turns the CSV into a Kotlin map at build time.
- **3.3** `Categoriser.categorise(normName, overrides)`: override → exact match → head noun → keyword rules → Other.
- **3.4** `Merger.merge(list, incoming, recipeId)`:
  - Group by `normName`, sum within each unit dimension, and pick a display unit.
  - Keep per-recipe contributions, so a recipe can be removed by subtraction.
  - Merging into a ticked item un-ticks it.
- **3.5** Property tests: merging then removing a recipe returns the original list, and merging is order-independent.
- **Done when:** all tests pass, and at least 98% of golden ingredient names get a category other than Other.

### Step 4 — Android app, no Claude yet (`:app`, `:core:extract`)
- **4.1** `:core:extract`: an OkHttp fetcher with a realistic User-Agent, a 10s timeout and redirect handling. Then a JSON-LD `Recipe` extractor (handles `@graph`, arrays and `HowToSection`), a microdata extractor and a heading-based fallback.
- **4.2** Save about 30 recipe pages into `testdata/pages/`, from BBC Good Food, AllRecipes, NYT, Serious Eats, Delicious, Jamie Oliver, RecipeTin Eats, blogs that use WPRM or Tasty plugins, and others. Write an extractor test for each page.
- **4.3** Room database:
  - Entities from §4, DAOs, migrations scaffold, and Hilt modules.
  - A `ListRepository` that wraps the merge and categorise logic in transactions.
- **4.4** Screens, built in Compose with Material 3, dark mode and dynamic colour:
  - **List**: grouped by aisle, tick, swipe to delete, add manually, clear ticked/all, undo.
  - **Import preview**: title, servings scaler, ingredient checkboxes, staples unticked, and editing a line before adding.
  - **Recipes**: list, open source link, remove from list.
  - **Settings**: store profiles and aisle order (drag to reorder), staples list, Claude section (placeholder until Step 5).
- **4.5** Share intent: the `ACTION_SEND` filter routes into the import flow. The paste field detects a URL on the clipboard.
- **4.6** Error states: no network, page blocked, no recipe found. Each offers "Select lines manually" (shows the page text with checkboxes).
- **4.7** WebView fallback for blocked or JavaScript-heavy pages: render the page off-screen, grab the DOM, then run the same extractors.
- **4.8** Tests: ViewModel unit tests, Room tests (Robolectric), and Compose UI tests for list ticking and the import preview.
- **4.9 [YOU]** Install the debug APK from CI on your phone. Share 5 recipes from Chrome and report anything that goes wrong.
- **Done when:** CI is green, the debug APK is produced, and 4.9 shows no blocking problems.

### Step 5 — Claude integration (`:core:claude`)
- **5.1** Feasibility test: add the Anthropic Java SDK to `:core:claude` and to a throwaway Android build. Check the APK size increase, minSdk 26 desugaring and R8 rules.
  - If it fails or adds more than about 5 MB, call the same Messages API over OkHttp plus kotlinx.serialization instead.
  - Record the decision in `docs/decisions/001-claude-client.md`.
- **5.2** Interface `ClaudeExtractor.extract(input: ClaudeInput): RecipeImport`. `ClaudeInput` is a text block plus images (JPEG, longest side 1568 px or less) plus a transcript.
- **5.3** Request:
  - Model and effort come from settings (default `claude-opus-5-5`, `effort: low`), with `max_tokens` around 8000.
  - A structured-output JSON schema (§3.7), a server-side fallbacks beta, and a fixed system prompt for prompt caching.
  - The prompt says to split combined lines, flag estimated quantities and leave out non-ingredients.
- **5.4** Response handling:
  - Check `stop_reason`, including `refusal` and `max_tokens`, then validate against the schema.
  - Map typed SDK errors: 401 bad key, 429 retry with back-off, 5xx retry once, network error.
  - Record `usage` into `ClaudeUsage`.
- **5.5** App wiring:
  - The settings screen gets an API key field (EncryptedSharedPreferences) with a "Test key" button, the mode (Off/Fallback/Always), a model picker, and a cost counter.
  - In Fallback mode, Claude runs when extraction fails or any line's confidence is below 0.6. It re-parses only those lines, or the whole page text if extraction failed.
  - The preview screen shows a "Parsed by Claude" badge and marks estimated lines.
  - A first-time privacy dialog explains what gets sent.
- **5.6 [YOU] Evaluation:** with your key, run a script (`tools/eval-claude`) over the golden lines and 30 pages. Compare accuracy and cost against the rule parser, then pick the default mode.
- **Done when:**
  - Unit tests pass against recorded API responses (MockWebServer).
  - A live call works on a test page.
  - The eval report is committed.

### Step 6 — Instagram import
- **6.1** Recognise share URLs from `instagram.com/reel/…`, `/p/…` and `instagr.am`.
- **6.2** Caption fetch: try the page's `og:description`, then the `/embed/captioned` page, through OkHttp, falling back to the off-screen WebView. Parse out the caption and any URLs.
- **6.3** Routing:
  - If the caption has a recipe URL, run the web import on it.
  - Otherwise, if the caption looks like an ingredient list, send it to Claude as text.
  - Otherwise, show the "Add media" screen.
- **6.4** Media inputs, on the "Add media" screen:
  - Pick screenshots (photo picker, up to 10).
  - Pick a video: `MediaMetadataRetriever` takes 10 frames evenly spaced over the video, skipping near-duplicates (perceptual hash), and resizes and compresses them.
  - A transcript from the audio track via `SpeechRecognizer` (on-device where the phone supports it; skip if not).
  - A "Paste caption" field.
- **6.5** Send everything as one Claude call: frames + transcript + caption. Same schema; any quantity Claude infers from video or speech is marked estimated.
- **6.6 [YOU]** Test 10 reels: a caption containing the recipe, a caption with a link, a voice-over only, and on-screen text only.
- **Done when:** the routing and frame-extraction unit tests pass, and 6.6 shows that the ingredients come through for the reel types that can work.

### Step 7 — Garmin watch app (`garmin/`)
- **7.1** Install the Connect IQ SDK and generate a developer key.
  - **Risk:** this may not be possible in the container. If so, the agent writes the code, and you build and run the simulator locally with the instructions in `garmin/README.md`.
- **7.2** `manifest.xml`: `watch-app` type, target devices (§3.6), the Communications permission.
- **7.3** Data layer: a list model saved to `Application.Storage`, a compact payload decoder `{v, items:[[id,name,qty,aisle,ticked]], aisles:[...]}`, and an outbox queue of tick events.
- **7.4** UI:
  - A `WatchUi.Menu2`/`CheckboxMenu` with aisle headers, and ticked items dimmed or moved to the end of their aisle.
  - Works with both touch and buttons.
  - A "Last synced" footer, and an empty state.
- **7.5** Test in the simulator with sample data, on the round and rectangular targets. Check memory use is under each device's limit.
- **Done when:** builds with `monkeyc` for every target device (in CI if the SDK can be installed there), and the list works in the simulator.

### Step 8 — Phone ↔ watch sync
- **8.1** Android `WatchSync` service: set up the ConnectIQ SDK, find the paired device and the installed app, and register for app events.
- **8.2** Phone → watch:
  - Whenever the list changes, wait 1 s, build the payload (trimmed to 8 KB) and send it.
  - Track delivery, and resend when the watch next connects.
- **8.3** Watch → phone: apply tick events by latest timestamp, send an acknowledgement, and remove acknowledged events from the outbox on the watch.
- **8.4** Edge cases:
  - The phone is out of range.
  - The Garmin Connect app isn't installed: show an install prompt.
  - The watch app isn't installed: link to the store page.
  - The same item is ticked on both devices.
- **8.5** Unit tests: payload builder and size limits, conflict resolution, outbox replay.
- **8.6 [YOU]** Real-device test:
  - Import a recipe and check it appears on the watch within 10 s.
  - Tick items on the watch while out of range, reconnect, and check the phone matches.
- **Done when:** the 8.6 checks pass.

### Step 9 — Polish and release
- **9.1** App icon, splash screen and watch launcher icon. Accessibility pass: content descriptions, touch target sizes, font scaling.
- **9.2** Performance: a list of 200 items scrolls smoothly, and import takes under 3 s (not counting Claude).
- **9.3** Privacy policy page, Play Data Safety answers, and a crash reporter (opt-in, or none).
- **9.4** Signed release builds in CI. Play internal testing track. Connect IQ beta app.
- **9.5 [YOU]** Store listings and submissions.
- **Done when:** the app is live on Play internal testing and the Connect IQ beta.

### Doing this "in one shot"
The agent can build and test Steps 0–6, plus most of 7 and 8, without stopping, if it works in order and commits after each sub-step. It can't finish the **[YOU]** checks: real devices, your API key, Garmin's SDK and the store accounts. Plan on 3 hand-offs, after Steps 4, 6 and 8, where you install the build, test it and report back.

## 7. Risks and honest caveats

- **Sites that block fetching.** Cloudflare, paywalls and JavaScript-only pages defeat a plain HTTP fetch. Mitigation: a WebView fallback that renders the page, then extracts from it.
- **Ingredient parsing is never 100% right.** The preview screen exists so the user can catch errors, and Claude fallback covers the hard cases.
- **API key in the app.** Building a shared key into the APK isn't safe, because anyone can pull it out of the app. So v1 asks each user for their own key. That's fine for you, but it puts off ordinary Play Store users. Offering Claude to everyone without their own key needs a small backend proxy that holds the key and enforces per-user limits; that is v2.
- **Instagram is hostile to automation.** Caption fetching will break regularly. Video-based extraction depends on on-screen text or clear speech, and quantities in videos are often vague ("a splash of soy"), so expect estimates.
- **On-device speech recognition** quality varies by phone and language. Cloud speech-to-text could be an option later, but it means another key and another privacy disclosure.
- **Watch memory.** Older or cheaper Garmins give apps very little memory (around 64–128 KB). Keep the payload small, and limit supported devices at first.
- **Connect IQ phone messaging is flaky.** It needs the Garmin Connect app running, and delivery is sometimes delayed. The retry queue and the watch's local cache are required, not optional.
- **iPhone users are out of scope.** Keeping the core in pure Kotlin leaves a path to KMP later.
- **Sharing a list with a partner** needs a backend. That is v2.

## 8. Later ideas (v2+)
- Shared lists via a sync backend (e.g. Supabase/Firebase).
- Meal planner, and "Have it already" pantry tracking.
- Wear OS tile, and home-screen widgets.
- Backend proxy so Claude works without users bringing their own key.
- Ask Claude to scale or substitute ingredients ("make it vegetarian").
