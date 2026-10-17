# HyperShop — Plan (rev 2)

Recipe URL or Instagram reel → parsed ingredients → merged, aisle-sorted shopping list on Android, mirrored and tickable on a Garmin watch. Optional Claude API integration for harder parsing.

> **rev 2:** revised after five independent reviews (Android, parsing/merge, Claude/Instagram, Garmin/sync, execution). The main changes are listed in §0.

## 0. What changed in rev 2
- **Build order.** The watch app and sync now come right after the phone app, before Claude and Instagram. The watch is the hardest part to check without hardware, so it should be tested first.
- **MVP cut line (§6).** It defines what the one-shot run has to deliver, and the rest is deferred.
- **Progress tracking.** `PROGRESS.md`, `HANDOFF.md` and `tools/verify.sh` let a fresh session resume from where the last one stopped.
- **Data model.** Item quantities come only from their contributions, and ticking is stored as *purchased* per contribution. Each item has a revision counter, so sync doesn't depend on the watch and phone clocks agreeing.
- **Parser.** Words that change what an item is (ground, red, tinned) are kept in the name, so different items never merge. Section headers are skipped. Multi-ingredient lines are split. The app knows each recipe's unit system (UK/US/AU) and the unit you shop in.
- **Sync.** The phone is the source of truth. The watch sends operations; the phone sends whole snapshots. The watch asks for a snapshot when it opens, because the phone can't wake it. Every message carries a protocol version.
- **Claude.** Calls go straight to the API over OkHttp, not through the Java SDK. The schema is strict. Image token costs are corrected, and on-device text recognition (OCR) runs before Claude.
- **Instagram.** Meta's oEmbed is tried first, there's no WebView scraping, and transcription from a video file is treated as an experiment.
- **Platform.** Target SDK 36. The API key is encrypted with the Keystore (EncryptedSharedPreferences is deprecated). Connect IQ SDK 9.x.

## 1. Architecture

```
 Chrome / Instagram ─share URL──▶ ┌──────────────── Android app (Kotlin) ─────────────────┐
 Gallery ─share image/video─────▶ │ Import (WorkManager job) ─▶ Extractor ─▶ Rule parser    │
 paste link (chip)                │        │                  (JSON-LD/WPRM/   │           │
                                  │        │                   microdata)      │           │
                                  │        └─▶ OCR (ML Kit) ─▶ Claude (opt-in)─┤           │
                                  │                                            ▼           │
                                  │                       Preview (user confirms)          │
                                  │                                            ▼           │
                                  │        Merge ─▶ Categorise ─▶ Room (:core:data)        │
                                  │                                 ▲        │             │
                                  │              WatchSync (App-scoped) ◀────┘  ▼ UI       │
                                  └──────────────────────────┬─────────────────────────────┘
                                                             │ Connect IQ Mobile SDK
                                                 ┌───────────▼────────────┐
                                                 │ Connect IQ watch-app   │ list, ticks,
                                                 │ + glance  (Monkey C)   │ outbox, offline
                                                 └────────────────────────┘
          api.anthropic.com ◀── HTTPS, only when the user enables Claude, with their key
```

- **Local-first, no backend.** Network is used only to fetch pages, Meta oEmbed, and Claude when it's turned on.
- **Why not a web app:** a Garmin watch app can only talk to a phone companion app through the Connect IQ SDK, or to a server. A web app would need a backend.

## 2. Tech stack

| Area | Choice |
|---|---|
| Android | Kotlin 2.x, Compose + Material 3, **compileSdk/targetSdk 36**, minSdk 26, edge-to-edge, predictive back |
| Build | Gradle wrapper pinned, AGP version that supports API 36, **KSP** (no kapt), JDK 21 runs Gradle, toolchain 17 |
| Storage | Room 2.7+ (`exportSchema`, migrations tested) in `:core:data`; DataStore for settings |
| Background | WorkManager (expedited work) for imports |
| Networking / HTML | OkHttp (5 MB body cap, no cleartext) + Jsoup |
| Serialization | kotlinx.serialization |
| DI | Hilt, app module only (core modules have no DI) |
| Claude API | **Direct calls to `/v1/messages` over OkHttp + kotlinx.serialization**. `anthropic-version: 2023-06-01`. Default model `claude-opus-5-5`, selectable in settings |
| Secrets | AES-256-GCM key in Android Keystore. The encrypted API key is kept in its own DataStore file, which is left out of backups |
| OCR | ML Kit Text Recognition v2 (Latin, bundled ~4 MB) |
| Media | `MediaMetadataRetriever.getScaledFrameAtTime`; Photo Picker (no media permissions) |
| Watch link | `com.garmin.connectiq:ciq-companion-app-sdk` ≥ 2.3.0 (Maven Central) |
| Watch app | Monkey C, **Connect IQ SDK 9.x**, `watch-app` + `(:glance)`, minApiLevel 3.2 |
| Logging | Timber, plus an in-app "Debug log → share" screen |
| Tests | JUnit5 for the pure-JVM modules; JUnit4 + Robolectric + Compose rule + **Roborazzi** screenshots for the app; Monkey C `(:test)` |
| CI | GitHub Actions: lint, tests, `assembleDebug`, `minifyReleaseWithR8`, `monkeyc` (optional job). Uploads the APK and `.prg` files, and keeps a rolling "nightly" release |

## 3. Core features

### 3.1 Import
- **Entry points:**
  - `ACTION_SEND` text. The app pulls the URL out with `Patterns.WEB_URL`, takes the first https link, strips `utm_*` and `igsh`, and uses `EXTRA_SUBJECT` as the fallback title.
  - `ACTION_SEND`/`SEND_MULTIPLE` with `image/*` or `video/*`, which goes to "Add media".
  - A **"Paste link" chip**. It shows only when the clipboard's `ClipDescription` says it holds text. The clipboard is read only when the chip is tapped, which avoids Android 12+ clipboard toasts.
  - `singleTop` + `onNewIntent`. The import state survives the app being killed (SavedStateHandle plus a Room `ImportJob`).
- **Imports run as WorkManager expedited jobs** that write `ImportJob(id, state, inputRefs, resultJson, error)`. The preview screen watches the job, so switching back to Instagram mid-import is safe.
- **Extraction order:**
  1. WPRM/Tasty plugin markup (has the section groups).
  2. JSON-LD `Recipe`.
  3. Microdata.
  4. Heading heuristic.
  5. "Select lines manually".
- **JSON-LD quirks handled, each with a test:** `@graph`; `@type` as an array; several Recipe objects; `recipeIngredient` as a single string or under legacy `ingredients`; nested arrays; HTML entities, including double-encoded ones; tags inside strings; lenient JSON (trailing commas, raw newlines); `recipeYield` written as "4", "4 servings", an array, or "Makes 12".
- **Blocked or JavaScript-only pages** open in a *visible* in-app WebView sheet with an "Extract recipe" button. The user solves any challenge themselves. The sheet uses a separate WebView profile, cookies are cleared afterwards, and file access is off. **It is never used for Instagram.**
- **"Review before adding" screen.** Nothing is added to the list until you confirm here. Every import goes through it, whether from a link, Instagram, Claude or several recipes at once.
  - **Header:** the recipe title(s), a servings scaler, and a summary line: "14 items · 9 new · 3 added to existing · 2 in stock".
  - **Items grouped by aisle**, in your store order, showing what each will look like *after merging* with the current list:
    - **New** items: "Red onion — 2"
    - **Existing** items, with a merge badge: "Milk — +200 ml → 700 ml total"
    - **Already on the list and ticked:** "Butter — need 50 g more (100 g bought)"
  - **An "In stock" checkbox on every row.** Ticking it means the item won't be added. In-stock rows sink to the bottom of their aisle and are greyed out, and stay there so you can untick them.
    - Staples (salt, pepper, oil…) and optional or garnish lines start ticked as in stock.
    - A long press offers **"I have some"**, so you can set how much you already have; only the rest is added.
    - **"Always in stock"** adds the item to your staples list, so future imports pre-tick it.
    - **It learns what you usually have.** Each review records, per item, whether you marked it in stock (`StockStat`). An item marked in stock in **3 of your last 4** reviews where it came up is pre-ticked with a small "usually in stock" label. Unticking it twice in a row stops the pre-ticking. Settings has "Forget stock habits" and a per-item reset. Pre-ticked items are always visible on the screen, never hidden.
  - **Bulk actions:** "Mark all in stock" for an aisle (e.g. Spices) and "Clear in-stock".
  - **Line details:** tapping a row shows the original recipe line(s), with editable raw text that is re-parsed live and structured fields underneath. "A or B" lines show the alternative and let you swap to it.
  - **Buttons:** "Add 12 items" (the count updates as you tick), and "Cancel" to throw the import away.

### 3.2 Ingredient parser
`"2 ½ cups (300g) plain flour, sifted"` → `{qty 5/2, unit CUP(locale), alt 300 g, name "plain flour", note "sifted"}`.
- **Prep words vs identity words.** Prep words (chopped, diced, sifted, minced…) go to `note`. Identity words (ground, smoked, red, dried, tinned, frozen, powder, unsalted, low-fat…) stay in the name, so "red onion" ≠ "onion" and "garlic powder" ≠ "garlic".
- **Units depend on the recipe's locale.** The locale comes from the TLD, `inLanguage` or a setting, and is stored on `Recipe`:
  - cup: 240 ml US, 250 ml metric/AU
  - pint: 568 ml UK, 473 ml US
  - tablespoon: 15 ml, or 20 ml in AU
  - "stick" of butter
  - Case matters: `T` = tbsp, `t` = tsp.
  - "oz" in front of a liquid might mean fl oz, so confidence is lowered.
- **Item-scoped units.** Clove, bunch, tin, packet, sprig and slice are each their own unit, not a generic count.
- **Multi-ingredient lines:**
  - "X and Y" is split only when both halves are in the catalogue: "salt and pepper" splits, "sweet and sour sauce" doesn't.
  - "A or B" becomes A, with B stored as the alternative.
  - "juice/zest of N lemon(s)" becomes N lemons.
- **Flags:** `optional` ("optional"), `garnish` ("to serve", "for garnish"), `noScale` ("to taste", "for frying", no quantity).
- **Section headers** are lines ending in ":", lines starting "For the…", and WPRM group names. They become section labels, not items.
- **Singularising:** only the head noun, and only when the singular form is in the catalogue (`plural` column). Otherwise the word is kept as written, which handles hummus, asparagus and couscous.
- **Synonyms** map to one canonical catalogue ID, and can depend on locale (corn flour UK ≠ US). Ambiguous words are resolved from context: "pepper" with a count or red/green/bell means the vegetable; with tsp, black or ground it means the spice.
- **Confidence:** starts at 1.0. Unparsed words −0.4, unknown unit −0.3, name not in the catalogue −0.2, ambiguous split −0.2. Lines that are meant to have no quantity ("to taste") score 1.0. The threshold for calling Claude is set in the eval (Step 7.6), with 0.6 as the starting value.
- **Personal corrections map:** when the user fixes a line, the raw pattern and its correct result are saved locally and applied to future imports.

### 3.3 Merge engine
- **Merge key = canonical catalogue ID**, or the exact `normName` for items not in the catalogue. The head-noun fallback is used **only for categorising**, never for merging.
- **Quantities are summed within each dimension**, in base units, as rationals.
  - Volume and mass are converted into each other only for catalogue items that have `gramsPerCup` (about 150 baking and dry staples). Otherwise the item shows a compound quantity: "1 onion + 200 g".
- **Shopping display policy** (catalogue `shoppingUnit` / `displayPolicy`):
  - Spoon or pinch amounts of pantry and liquid items show as just the name ("milk", "soy sauce").
  - Container conversions: clove → bulb (about 10), tin = 400 g, bunch.
  - The exact total is in the item's detail view.
- **Pack sizes** (catalogue `packSizes`, e.g. `500g|1kg|1.5kg`; on by default, can be turned off):
  - Shows what to buy: "Plain flour — 1 × 500 g pack (350 g needed)".
  - Uses the fewest packs that cover the need, with the least left over. The leftover shows in the item's detail view.
  - Items with no pack data show the rounded quantity as before.
  - Pack sizes start as typical UK supermarket sizes (~150 most common items). You can edit them per item in the item's detail view. Your edits are stored in `PackOverride` and win.
  - The pack line is display only. Contributions still hold the exact amount needed, so merging and removing a recipe stay exact.
- **Rounding:**
  - Counts round up to a whole item, or to the nearest ½ for produce like lemons.
  - Mass and volume round to friendly steps (5 g / 25 g / ¼ cup).
  - Fractions show as ½ ⅓ ¼. There's a display setting for metric, imperial, or as written.
- **Scaling:** contributions are stored already scaled, and `Recipe.servings` remembers the chosen servings. Changing servings later recalculates that recipe's contributions. `noScale` lines are never scaled.
- **Ticking = purchased:** ticking marks all of an item's contributions as purchased. A later merge adds an unpurchased contribution, so the item shows "need 1 more (2 bought)". Removing that recipe goes back to the ticked state.
- **Dry run for the review screen.** `Merger.preview(currentList, incoming, inStock)` is a pure function that returns a diff (new / added to existing / still needed after purchases / skipped as in stock) without changing anything. The review screen shows that diff. Confirming applies the same diff in one Room transaction, so what you saw is exactly what gets added.
- **Manual edits** are stored as a `ManualAdjustment` (a change or an override) alongside the contributions. If a recipe is removed while an override exists, the override stays and the user is told.

### 3.4 Aisle categorisation
- **Aisles are a table** seeded from defaults and keyed by a stable `code`. Users can rename, hide or add aisles. The defaults are Produce, Bakery, Meat & Fish, Dairy & Eggs, Chilled, Frozen, Tins & Jars, Pasta/Rice/Grains, World Foods, Baking, Nuts & Dried Fruit, Oils & Vinegars, Spices & Condiments, Free-from, Drinks, Household and Other.
- **Lookup order:** user override → identity words (frozen → Frozen, tinned → Tins) → catalogue ID → head noun → keyword rules → Other.
- Overrides survive renames because they are keyed by catalogue ID.
- **Learned aisle order** (per store profile, v1.1):
  - Every tick on the phone or watch is logged as `TickEvent(storeId, aisleCode, tickedAt, source)`. Watch ticks carry the watch's timestamp; it's only used to compare ticks within the same trip, so clock drift doesn't matter.
  - A **shopping trip** is a run of ticks with gaps under 20 minutes and at least 5 ticks.
  - Trips are thrown out as "ticked at home" when 80% or more of the ticks fall within 3 s of each other, or when "Clear ticked" or a bulk action did the ticking.
  - For each trip, aisles are ranked by when you first ticked something in them. Across the last 5 trips, aisles are ordered by their median rank. An aisle needs to have appeared in 2 or more trips.
  - After 2 trips that qualify, the app suggests: "Reorder aisles to match how you shop at Tesco Extra?" It shows the new order and asks you to accept. It never reorders silently.
  - Aisles you've **pinned** by dragging them keep their place.
  - "Auto-apply learned order" is a setting, off by default.
- **The catalogue starts at about 400 common UK items**, built from the most frequent names in an openly licensed recipe corpus, with the source and licence recorded. It grows from "Other" hits that the app logs locally. It is a CSV, generated into a resource file loaded lazily, not a giant Kotlin map literal, which would hit the JVM's 64 KB method limit. The Gradle task checks for duplicates and unknown aisles.

### 3.5 List UI
- **Layout:** grouped by aisle in store order. Ticked items sink and are dimmed, and are also marked with something other than colour.
- **Actions:** tap to tick, swipe to delete (with an accessibility action as an alternative), add manually through the parser, clear ticked/all, undo.
- **Recipes tab:** open the source page, change servings, remove the recipe.
- **"Share list as text"**, grouped by aisle, for WhatsApp and similar. This partly covers list sharing until v2.
- **JSON export/import** through the Storage Access Framework. Auto-backup includes the database and excludes the key file.

### 3.6 Garmin app and sync
- **Watch UI:**
  - The first screen lists aisles, showing how many items are left in each. Selecting an aisle opens a `CheckboxMenu` for that aisle. Alternatively a `CustomMenu` with header rows; 5.4 prototypes both and measures memory.
  - Works with touch and buttons. Has "Last synced" and an empty state.
  - **Undo last tick:** a "↶ Undo" item at the top of each aisle menu, plus a long press on Back (where the device supports it). It undoes up to the last 5 ticks by queuing a reverse op; the outbox collapses the pair.
  - **Next aisle:** when every item in an aisle is ticked, the watch jumps to the next aisle with items left. An "Next aisle ▸" item at the end of each aisle does the same by hand.
  - **Feedback:** a short vibration on each tick, and a double pulse when an aisle is finished, using `Attention.vibrate` where the device has it. Vibration can be turned off in the watch app's settings.
- **Glance:** "Shopping: 14 left · Produce next", read from a small `glanceSummary` storage key.
- **Message format:** native Monkey C objects (Lists, Dictionaries, numbers, strings), not JSON strings. The watch has no JSON parser. Every message is `{v:1, t:"hello"|"snap"|"ops"|"ack"|"resync", ...}`. A version the watch doesn't know shows "Update watch app" (and the same on the phone).
- **Protocol:**
  - The phone is the source of truth. Every change to an item increments `Item.rev`, and the list as a whole has a `listRev`.
  - **Phone → watch:** always a **full snapshot**, `{listRev, items:[[id, name, qtyShort, aisleIdx, ticked, rev]], aisles:[…], more:N, ackedUpToSeq}`. Dropped items show as "+N more on phone". The size cap is measured on each target device.
  - **Watch → phone:** ops `{seq, itemId, ticked, baseRev}`. The phone deduplicates by `seq`. An op is ignored if the item has changed since `baseRev`, or if the item is gone (the phone keeps a short list of deleted IDs). Otherwise the op is applied.
  - **Pull, not push:** when the watch app starts or resumes, it sends `hello{listRev}` and the phone replies with a snapshot. Pushing on every change is best-effort only. When the phone sees the watch connect (`onDeviceStatusChanged(CONNECTED)`) or the app starts, it sends `resync`.
  - **Optimistic watch:** when a snapshot arrives while some ops haven't been acknowledged yet, the watch re-applies them on top. A tick never visibly reverts.
  - **The watch outbox is the durability guarantee:** capped at about 500 ops, with repeated ops on the same item collapsed into one.
- **Watch storage:** the list is stored in chunks (one key per aisle), plus `meta`, `outbox` and `glanceSummary`, each well under the 32 KB per-value limit. Decoding is wrapped in try/catch, falling back to the cached list.
- **Android side:**
  - `WatchSync` lives at application scope. There's no permanent service: the watch's outbox and the `hello` handshake cover the gaps.
  - An optional user-started **"Shopping mode"** foreground service (`connectedDevice` type, with a notification, times out after 60 min) keeps the link live while shopping. It's deferred past the MVP.
  - The manifest needs a `<queries>` entry for `com.garmin.android.apps.connectmobile`.
  - The SDK init errors (Garmin Connect missing or out of date) show install or update prompts.
  - `ConnectIQ.openStore(appId)` opens the watch app's store page.
- **Devices:** exact product IDs are listed in `garmin/README.md` with each device's memory limit, taken from the SDK device files. To start: fr255/265/965, fr570/970, fenix7 family, fenix8, venu2 family/venu3, vivoactive5.

### 3.7 Claude API integration (optional, opt-in)
- **Settings:**
  - Mode: Off / Fallback / Always.
  - API key, with "Test key" calling `GET /v1/models` (no token cost).
  - Model picker.
  - An optional monthly spend cap.
  - A cost counter covering input, output, cache-write and cache-read tokens.
  - In-app advice to create a dedicated key with a spend limit set in the Anthropic Console.
- **Request:**
  - Default model `claude-opus-5-5`, with `output_config.effort:"low"` always set explicitly. `thinking` is never sent as disabled, because Opus 5.5 rejects that.
  - `max_tokens` 8000, non-streaming.
  - `fallbacks:"default"` plus the beta header, **but only for Opus 5.5 and Sonnet 5.5**. Haiku 5.5 has no server-side fallback.
- **Strict structured output** (`output_config.format` JSON schema):
  - `additionalProperties:false` everywhere, and all fields listed in `required`. Optional fields are nullable via `anyOf`.
  - Top level: `status` enum (`ok | no_ingredients | not_a_recipe`), `title`, `servings`, `ingredients[]`, `source_urls[]`.
  - Each ingredient: `raw`, `name`, `quantity`, `quantity_max`, `quantity_text`, `unit` (an enum that mirrors `Unit`, plus `other`), `alt_quantity`, `alt_unit`, `note`, `section`, `optional`, `estimated`, `aisle_suggestion` (an aisle-code enum plus `unknown`).
  - No numeric or string constraints in the schema, because the API rejects them; ranges are checked in Kotlin.
- **Response handling:**
  - Branch on `stop_reason` first. `refusal` shows "Couldn't parse, edit manually", with no retry. `max_tokens` is shown as an error.
  - Then validate the response.
  - HTTP errors: 401/403 → bad key, 429/529 → back off, 5xx → retry once, network → offline path.
  - The `x-api-key` header is redacted from all logs.
- **Prompt injection:**
  - Page text and captions are wrapped in `<untrusted_content>` delimiters, and the system prompt says to treat them as data only.
  - No tools are given to the model.
  - A returned URL is accepted only if it appears word for word in the source text, is https, and the user taps it.
  - Input length is capped.
  - Nothing reaches the list without the preview screen.
- **Offline:** the rule parser's result is shown, tagged "Claude pending", with a manual "Retry with Claude" option. It is never retried automatically, because that would spend the user's money. OCR text and transcripts are kept so a retry doesn't redo media extraction.
- **Caching:** off by default. Imports are occasional, so cache writes would rarely be reused. It is turned on only for bulk import and when the prompt is over 512 tokens.
- **Cost on Opus 5.5** ($4/$20 per MTok, thinking billed as output):
  - Text import: about 3k in and 1.5k out, roughly **$0.04**.
  - Reel with OCR text and 3 frames at 1092 px (about 600 tokens each): roughly **$0.05**.
  - Reel with 12 full-size frames: roughly **$0.13**.
  - Sonnet 5.5 is half the Opus price. Haiku 5.5 is about $0.001–0.003 per import.
  - Before a media import, the app shows an estimate.
- **Privacy dialog:**
  - Content is sent to Anthropic under the user's own account and Anthropic's data-retention terms.
  - Google may receive audio if its speech recogniser is used.
  - Frames and videos are deleted once the import finishes. Only the extracted text and the source link are kept.

### 3.8 Instagram import
Sharing from the Instagram app sends **only the URL**. The app tries these tiers in order:
1. **Caption via Meta oEmbed:**
   - `GET graph.facebook.com/v25.0/instagram_oembed?url=…`. A reviewer reported that it has needed no token since 2026-06, for public posts only. **Check this in Step 8.1.**
   - The caption is parsed out of the returned embed `html`, if it's there. If that fails, one plain `og:` fetch with a normal user agent.
   - One call per share the user starts, and **no WebView scraping of instagram.com**.
   - Using oEmbed to pull text is a grey area under Meta's terms.
2. **Caption routing:**
   - A recipe link in the caption → web import.
   - Text that looks like an ingredient list → rule parser first, then Claude if confidence is low.
   - Otherwise → "Add media".
3. **"Add media" screen:** includes a short how-to: use Instagram's own *Download* if it's offered, otherwise screen-record with audio (Android 11+), or screenshot the expanded caption.
   - **Screenshots:** OCR them on the phone, then the rule parser, then Claude if needed. This works **without Claude** for clean text.
   - **Video:**
     - Sample about 1 frame per second and OCR each one. Keep frames whose text changed.
     - Send the OCR text to the parser or Claude. Attach only 2–3 frames when OCR confidence is low.
   - **Transcript (experiment):**
     - Decode the audio to 16 kHz mono PCM.
     - On API 33+, use the on-device recogniser with `EXTRA_AUDIO_SOURCE` when it's available. Otherwise skip it and tell the user.
     - A downloadable whisper.cpp model is v1.1.
   - **Paste caption.**
4. The result goes to the preview. Amounts inferred from video or speech are marked "estimated".

### 3.9 Cookbook photo import (v1.1)
- **Entry points:** "Scan a cookbook page" in the import menu, which uses the system camera through `ActivityResultContracts.TakePicture` (no camera permission needed), the Photo Picker, or a shared image.
- **Crop step:** you drag a box around the ingredient list. It's pre-filled by finding an "Ingredients" heading, or the block of text with the most lines that start with a quantity.
- **Text recognition:**
  - ML Kit OCR runs on the phone.
  - Recognised text blocks are read in column order (by x position, then y), so two-column layouts aren't scrambled.
  - Lines that wrap are joined when the next line doesn't start with a quantity or a capital letter.
- **Parsing:** the rule parser runs first. Claude, if enabled, gets the OCR text plus the cropped image when OCR confidence is low.
- **Recipe details:** you type the title; the servings field suggests numbers it finds near "Serves" or "Makes". The recipe is saved with `source: PHOTO`, and only the text is kept — the photo is deleted after import.
- Everything then goes to the usual review screen.

### 3.10 Meal planner (v1.1)
- **Recipe library:** every imported recipe is saved, whether or not it's on the list right now. The Recipes tab becomes a library with search, "on list" and "planned" filters, and favourites.
- **Week view:** 7 days, each with optional Breakfast, Lunch and Dinner slots. You add a recipe from the library, or straight from an import ("Plan for…" on the review screen), and pick servings for each meal.
- **"Shop for this week":** sends every planned meal not yet added into **one combined review screen**, with ingredients merged across meals. Each row can show which meals need it ("Onion — 3 · Mon, Wed").
  - The in-stock ticks, "I have some" and stock learning all work the same as for a single recipe.
- **Linking meals to the list:**
  - Contributions record the `plannedMealId` as well as the `recipeId`.
  - Removing a planned meal removes only its contributions. If the same recipe is planned twice, each copy counts separately.
  - Moving a meal to another day doesn't change the list.
- **Extras:** copy last week's plan, and clear a week. A day shows a "Shopped" badge once its meals are on the list.
- **Watch:** no planner on the watch. It just sees the merged list.

## 4. Data model (Room, `:core:data`)
- `Item(id AUTOINCREMENT, catalogId?, normName UNIQUE, displayName, aisleCode, position, rev, tickedAt?, updatedAt)`
- `Contribution(id, itemId FK CASCADE, recipeId FK CASCADE nullable, plannedMealId FK CASCADE nullable, qtyBase (rational + dimension, per dimension), purchased)`
- `ManualAdjustment(itemId, kind: DELTA|OVERRIDE, qty)`
- `Recipe(id, url, title, servingsOriginal, servingsChosen, unitLocale, importedAt, source: WEB|INSTAGRAM|PHOTO|MANUAL, parsedBy: RULES|CLAUDE, claudePending, favourite, onList)`. Recipes stay in the library after they leave the list.
- `RecipeLine(id, recipeId, section, raw, parsedJson, status: ADDED|IN_STOCK|PARTIAL|EXCLUDED, haveQty?)`: keeps the parse results, so re-scaling or retrying doesn't parse or pay twice. It also records what you marked as in stock. Removing a recipe touches only lines that were actually added, and the recipe can later offer "Add the in-stock items now".
- `ImportJob(id, state, inputRefs, resultJson, error, createdAt)`
- `Aisle(code PK, name, hidden, defaultOrder)`, `StoreProfile(id, name)`, `AisleOrder(storeId, aisleCode, position)`. The active store is kept in DataStore.
- `CategoryOverride(catalogId | normName, aisleCode)`, `UserCorrection(rawPattern, parsedJson)`
- `ClaudeUsage(date, model, inputTokens, outputTokens, cacheWrite, cacheRead)`
- `DeletedItem(id, deletedAt)`: kept for 7 days, so late watch ops are dropped.
- `StockStat(key = catalogId | normName, history (last 4 outcomes as a bitfield), suppressed)`: stock learning.
- `PackOverride(key, packSizes)`: your own pack sizes.
- `TickEvent(id, storeId, itemId, aisleCode, tickedAt, source: PHONE|WATCH, bulk)` (v1.1): kept for 90 days. `AisleOrder` gains `pinned`.
- `PlannedMeal(id, date, slot: BREAKFAST|LUNCH|DINNER|OTHER, recipeId FK, servings, addedToList)` (v1.1)
- Schemas are exported to `android/app/schemas/` and covered by `MigrationTestHelper` tests.

## 5. Repo layout and project constants

```
android/      :app, :core:model, :core:catalog, :core:parser, :core:merge, :core:extract,
              :core:data, :core:sync (payload codec + protocol), :core:claude
garmin/       Connect IQ project (manifest, source/, resources/, test/), README with device table
catalog/      ingredients.csv, synonyms.csv (+ SOURCES.md with licences)
testdata/     ingredients/*.tsv golden lines, pages/ (trimmed JSON-LD/microdata fragments + synthetic HTML)
tools/        verify.sh, eval-claude/, fetch-live-check/
docs/         decisions/ (ADRs), HANDOFF.md, KNOWN_ISSUES.md
PROGRESS.md   live checklist
```

| Constant | Value |
|---|---|
| Android app ID | `com.robert4lyf.hypershop` (change before first store upload if you want) |
| Watch app UUID | generated in 0.4; beta and release UUIDs set as Android build flavours |
| Versioning | `versionCode` = CI run number, `versionName` from git tag |
| Signing | committed **debug** keystore, so updates install over each other; release keystore and Connect IQ developer key held as CI secrets **[YOU]**, with an offline backup |
| R8 | enabled for release from Step 4, with keep rules for serialization, Room and CIQ |

## 6. Build plan

### Rules for the agent
1. **Starting a session:** read `PROGRESS.md` and resume at the first unchecked item. Never redo checked items.
2. **Each sub-step:** implement it, run its check, then commit, updating `PROGRESS.md` in the same commit. Each line records `[x]`, `[~] stubbed` or `[!] blocked`, the commit SHA, a one-line note, and the check command.
3. **`tools/verify.sh`** runs every check that can be automated. It must be green before each step is closed.
4. **Stop at the MVP cut line.** No gold-plating. Shortcuts go in `docs/KNOWN_ISSUES.md`.
5. **Context running low:** commit, update `PROGRESS.md` and `HANDOFF.md`, and stop cleanly.
6. **Checks in the container use** Robolectric, Roborazzi, MockWebServer, fake adapters and `monkeyc`. **[YOU]** items are never blockers: mark them and move on.

### Things only you can provide
| Item | Needed at |
|---|---|
| Android phone with developer mode, or just downloading the APK | Hand-offs A, B, C |
| Garmin watch + Garmin Connect app | Hand-off B |
| Garmin account, *if* device files need a login | Step 5.1 |
| Anthropic API key (dedicated, with a spend limit) | Hand-off C |
| 10 test reels (caption recipe, link in caption, voice-over only, on-screen text) | Hand-off C |
| Play Console and Connect IQ Store accounts | Release (outside the one-shot scope) |

### MVP cut line
- **The one-shot run delivers:** Steps 0–7, i.e. phone app, watch app and sync, and Claude for text.
- **v1.1:** Step 8 (Instagram and media), Step 9 (smart shopping: learned aisle order, cookbook photos, meal planner) and Step 10 (polish).
- **Inside the MVP:** pack sizes (Steps 1 and 3), stock learning (Step 4.6) and the watch extras (Step 5) — each is small and needs no extra hardware.
- **Deferred:**
  - Shopping-mode service.
  - Multiple store profiles in the UI (the tables exist, with one store).
  - The whisper.cpp transcript.
  - The visible WebView fallback (4.7).
  - The model picker beyond a simple dropdown.
  - The store release.

---

### Step 0 — Repo, toolchain, progress scaffolding
- **0.1** Create a `main` branch from this branch. The working branch is merged into `main` through pull requests.
- **0.2** Add `.gitignore` (Android, Gradle, IDE, `local.properties`, `*.prg`, `developer_key*`, `*.jks` except `debug.keystore`), `README.md`, `LICENSE`, `PROGRESS.md` (the checklist below), `docs/HANDOFF.md` and `docs/KNOWN_ISSUES.md`.
- **0.3** Install the Android command-line tools, platform 36, build-tools 36, platform-tools. Add a SessionStart hook (`.claude/`) so future sessions install them.
  - **Check:** `sdkmanager --list_installed` shows them.
- **0.4** Gradle project with the version catalog and all modules empty; pinned versions as in §2; the committed debug keystore; constants from §5. Generate the watch app UUID.
- **0.5** ktlint and detekt, `tools/verify.sh`, and the CI workflow. CI uploads the APK artifact and keeps a rolling nightly release.
- **Check:** a cold `./gradlew build` and `tools/verify.sh` pass locally and on CI.

### Step 1 — Model, catalogue seed, sync codec
- **1.1** `:core:model`:
  - `Rational`, `Dimension`, a `Unit` enum with locale-dependent factors (`UnitLocale` US/UK/METRIC/AU), item-scoped units, and `QtyBase`.
  - `ParsedIngredient` (with flags, alternative, section and confidence) and `RecipeImport`.
- **1.2** Write the stored form of contributions, purchases, manual adjustments and `rev` rules down in `docs/decisions/002-quantities.md`.
- **1.3** `catalog/`:
  - CSV schema `id, name, plural, synonyms, aisleCode, shoppingUnit, displayPolicy, gramsPerCup, packSizes`, with about 400 seed rows (pack sizes for about 150 of them), `synonyms.csv` and `SOURCES.md`.
  - A Gradle task that validates the CSV and generates a resource file. A lazy loader in `:core:catalog`.
- **1.4** `:core:sync`:
  - Message types (§3.6) and a codec to and from `List`/`Map` objects.
  - The size estimator.
  - The snapshot builder with truncation and `more:N`.
- **Check:** unit tests pass for `Rational` arithmetic, unit conversion per locale, the CSV checks, and the codec round-trip and size cap.

### Step 2 — Ingredient parser (`:core:parser`)
- **2.1** `docs/parser-spec.md`: the output format and rules (§3.2).
- **2.2** Golden set `testdata/ingredients/*.tsv`:
  - **60 hand-curated lines** marked `verified`, covering every rule in §3.2.
  - About 300 more marked `unverified`, from an openly licensed source such as the NYT ingredient-phrase-tagger data. You spot-check 20 at hand-off A.
- **2.3** Tokeniser: numbers, fractions, unicode fractions, ranges, case-sensitive T/t, unit aliases, parentheses.
- **2.4** Grammar: qty, unit, alt, name and note. Sizing words, containers, "of", "x 400g tin", "juice of", "and" splitting, "or" alternatives, header detection, and the flags.
- **2.5** Normaliser: prep words vs identity words, head-noun singularising guarded by the catalogue, synonyms mapped to catalogue IDs, and the "pepper" ambiguity rule.
- **2.6** Confidence formula (§3.2).
- **Check:** accuracy per field on the **verified** lines: name ≥97%, qty ≥98%, unit ≥97%, plus a report file. Unverified lines are reported but don't gate.

### Step 3 — Categoriser and merge (`:core:merge`)
- **3.1** `Categoriser` with the lookup order in §3.4.
- **3.2** `Merger`: catalogue-ID keys, sums per dimension, density conversion, shopping display policy, rounding and fraction display.
- **3.3** Scaling and `noScale`. Purchased contributions. `ManualAdjustment`.
- **3.3a** `PackPlanner`: picks the fewest packs that cover the need, with the least left over, and honours your overrides. Tests cover exact fits, combinations of sizes (e.g. 1 kg + 500 g vs 2 × 1 kg), items with no pack data, and turning the feature off.
- **3.3b** `Merger.preview` dry-run diff, covering in stock, partly in stock ("I have some") and items already ticked. Property test: applying the preview gives the same list as a direct merge with the in-stock items removed.
- **3.4** Tests:
  - Property tests: merging then removing returns the original list; merge order doesn't matter; tick, then merge, then remove gets back the ticked state.
  - Golden **must-not-merge** pairs (red onion/onion, garlic/garlic powder, fresh/tinned tomatoes, chicken breast/thigh).
- **Check:** all tests pass, and at least 90% of the verified golden names categorise to something other than Other.

### Step 4 — Android app, no Claude (`:core:extract`, `:core:data`, `:app`)
- **4.1** `:core:extract`: fetcher (user agent, 10 s timeout, 5 MB cap, upgrade http to https) plus the extractors in priority order (§3.1), with tests for every quirk.
- **4.2** Fixtures: about 15 trimmed real-page fragments (JSON-LD/microdata only, with no prose or images) plus synthetic HTML for `@graph`, `HowToSection`, WPRM groups, heading-only pages and broken JSON. `tools/fetch-live-check` is a manual script, not in CI.
- **4.3** `:core:data`:
  - Entities and DAOs (§4), schema export, and migration tests.
  - `ListRepository` with acceptance tests for import, merge, remove recipe, tick, override aisle, manual adjustment and servings change.
  - Each mutation increments `rev` and `listRev`.
- **4.4** App shell: Hilt, navigation, theme (dynamic colour, dark mode), edge-to-edge and predictive back. A `ClaudeExtractor` interface with a `Noop` implementation. A `WatchLink` interface with a fake.
- **4.5–4.8** One screen per sub-step. Each has a ViewModel test, a Robolectric Compose test and a Roborazzi snapshot:
  - **4.5** List (aisle grouping, tick, swipe or accessibility delete, add manually, clear, undo, share as text).
  - **4.6** "Review before adding" (§3.1): aisle-grouped merge preview, In stock checkboxes, "I have some", "Always in stock", bulk actions, the live "Add N items" count, line details and editing. Robolectric tests:
    - Ticking In stock means the item isn't added.
    - "I have some" adds only the rest.
    - Stock learning: pre-ticks after 3 of the last 4, stops pre-ticking after two untick-overrides in a row, and "Forget" resets it.
    - Cancel leaves the list unchanged.
  - **4.7** Recipes (source link, servings, remove, "Add the in-stock items now").
  - **4.8** Settings (aisle order and rename/hide for one store, staples, display units, export/import, debug log). The Claude section is a placeholder.
- **4.9** Share intents (text, image, video), the paste chip, WorkManager `ImportJob`, error states (offline, blocked, not found → "Select lines manually"). Unit tests use real share payloads from Chrome, Instagram, Firefox, Samsung Internet and YouTube.
- **4.10** Debug-only "Load sample list" (3 recipes, about 40 items across every aisle) and "Fake watch" toggle. Backup rules.
- **Check:**
  - `verify.sh` is green, including `minifyReleaseWithR8`.
  - Roborazzi snapshots of every screen in sample mode, committed in `docs/screenshots/`.
  - The APK is on the nightly release.
- **⏸ Hand-off A [YOU]:** install the APK and share 5 recipes from Chrome. Spot-check 20 golden lines. Fill in the yes/no checklist in `HANDOFF.md`.

### Step 5 — Garmin watch app (`garmin/`)
- **5.1** Download the public Connect IQ SDK 9.x Linux zip (it's reachable without login). Get the device files. Generate a developer key with openssl (RSA 4096, PKCS#8 DER) to use in the container only.
  - **If the device files need a Garmin login:** mark the step `[!]`, build for whatever devices are available, and document how you can fetch them.
  - The real developer key for signing is **[YOU]**.
- **5.2** `manifest.xml`: watch-app, minApiLevel 3.2, the exact product IDs (§3.6), the Communications permission, the UUID from 0.4.
- **5.3** Data layer: message decode/encode mirroring `:core:sync`; chunked storage; the outbox (capped and collapsed); re-applying pending ops on a new snapshot; `glanceSummary`.
- **5.4** UI: the aisle list, then a per-aisle `CheckboxMenu`, with undo (last 5 ticks), "Next aisle" (by hand and automatic), and vibration feedback (with a setting). A `CustomMenu` prototype for comparison, with the choice recorded in an ADR. The glance view, empty state, "Last synced" and "Update watch app".
- **5.5** A `:debug` build with embedded sample data.
- **5.6** Monkey C `(:test)` unit tests for the decoder, the outbox, the re-apply rule, and an undo pair collapsing in the outbox.
- **Check:**
  - `monkeyc` builds for every available target.
  - The unit tests pass where they can run.
  - The device memory table is filled in `garmin/README.md`.
  - The CI job is optional if the SDK can't be cached.

### Step 6 — Phone ↔ watch sync
- **6.1** `WatchSyncImpl` (application scope): SDK init (`WIRELESS`, or `TETHERED` in debug for the simulator via `adb forward tcp:7381 tcp:7381`); device and app discovery; re-register listeners on CONNECTED; `<queries>` entry; init-error prompts; `openStore`.
- **6.2** Protocol on the phone: reply to `hello` with a snapshot; best-effort push after a 1 s pause; apply ops (dedupe by `seq`, `baseRev` rule, deleted-ID list); acknowledge with `ackedUpToSeq`; `resync` on connect and on app start.
- **6.3** Tests against a fake `IQDevice` transport:
  - A snapshot arrives while ops are pending.
  - A stale op after a merge.
  - An op on a deleted item.
  - Replaying the outbox after reconnecting.
  - A version mismatch.
  - Truncation.
- **6.4** Optional end-to-end run with the simulator and a tethered phone (needs a display, so **[YOU]** or skip).
- **Check:** the 6.3 tests are green and `verify.sh` is green.
- **⏸ Hand-off B [YOU]:** sideload the `.prg` file (copy to `GARMIN/APPS` over USB) and install the APK.
  - Import a recipe, open the watch app, and check the list appears within 10 s.
  - Tick items while out of range, reconnect, and check the phone matches.
  - Check the glance shows the count.

### Step 7 — Claude for text (`:core:claude`)
- **7.1** ADR 001: the client is OkHttp plus kotlinx.serialization. Optionally spend one timeboxed sub-step trying the SDK; use it only if it adds under 2 MB after R8 with no reflection warnings.
- **7.2** Key storage (Keystore AES-GCM, DataStore, left out of backups, recovery when it can't be decrypted). Settings: mode, key, "Test key", model dropdown, cost counter, spend cap, privacy dialog.
- **7.3** Request builder: the per-model fallbacks rule, explicit effort, the strict schema (§3.7), untrusted-content wrapping, input cap.
- **7.4** Response handling: `stop_reason` branches, schema validation, error mapping, usage → `ClaudeUsage`, header redaction.
- **7.5** Wiring:
  - Fallback mode re-parses only the low-confidence lines, or the whole page if extraction failed.
  - Offline goes to "Claude pending" with manual retry.
  - The "Parsed by Claude" and "estimated" badges.
  - Results are cached in `RecipeLine`.
- **7.6** `tools/eval-claude`: runs offline against recorded fixtures in CI. The live run is **[YOU]**; it compares the rule parser with Claude on accuracy, cost and `output_tokens`, and sets the confidence threshold.
- **Check:** MockWebServer tests cover every `stop_reason` and error path, and `verify.sh` is green.
- **⏸ Hand-off C [YOU]:** enter your key and run the live eval. Choose the default model and mode (see the note in the Claude section of the chat).

**— MVP cut line: the one-shot run ends here. —**

### Step 8 — Instagram and media (v1.1)
- **8.1** Check the tokenless Meta oEmbed and whether its `html` carries the caption. Record the result in ADR 003. Then build the `og:` fallback and strip `igsh`.
- **8.2** Caption routing (§3.8), with the rule parser first.
- **8.3** "Add media" screen with how-to text: the Photo Picker for screenshots and video, and the image/video share intents.
- **8.4** ML Kit OCR on screenshots.
- **8.5** Video sampling at about 1 fps, OCR on each frame, keeping frames whose text changed, resized to 1092 px. Claude image blocks (2–3 frames). A cost estimate shown before sending.
- **8.6** Transcript experiment (ADR 004): decode to PCM, then the on-device recogniser via `EXTRA_AUDIO_SOURCE` on API 33+ when it's available. Otherwise skip it and show a notice.
- **8.7** Delete temporary media after import. Update the privacy dialog.
- **Check:** unit tests for routing, the frame sampler (on test videos) and the OCR text merge.
- **[YOU]:** test the 10 reels.

### Step 9 — Smart shopping (v1.1)
- **9.1** Learned aisle order (§3.4):
  - `TickEvent` logging on the phone. Watch ops bring their timestamps along (protocol v2: ops gain `ts`; the phone still accepts v1 ops without it).
  - A pure-Kotlin `TripDetector` and `AisleOrderLearner` in `:core:merge`.
  - The suggestion card, pinned aisles, and the auto-apply setting.
  - Multiple store profiles in the UI, which this feature needs, are pulled forward from 10.3.
  - **Check:** tests on made-up trip logs — bulk "ticked at home" trips are ignored, the median order is computed correctly, pinned aisles are kept, and no suggestion appears before 2 trips.
- **9.2** Cookbook photo import (§3.9):
  - Camera and picker entry points, the crop UI with a pre-filled box, OCR column ordering and joining wrapped lines.
  - **Check:** OCR post-processing tests on recorded ML Kit outputs from 5 photographed pages (single-column, two-column, wrapped lines).
  - **[YOU]:** scan 5 pages from your own books.
- **9.3** Meal planner (§3.10):
  - Database migration (`PlannedMeal`, `Contribution.plannedMealId`, `Recipe.onList`/`favourite`), with a `MigrationTestHelper` test from the MVP schema.
  - The library, the week view, "Plan for…" on the review screen, and the "Shop for this week" combined review.
  - **Check:** repository tests — removing a meal removes only its contributions, a recipe planned twice counts twice, and moving a meal doesn't change the list — plus Roborazzi snapshots.

### Step 10 — Polish (v1.1)
- **10.1** Icons (app, splash, watch launcher), an accessibility pass, font scaling.
- **10.2** A JVM benchmark: parsing and merging 20 recipes takes under 200 ms. A Robolectric list with 200 items.
- **10.3** Optional "Shopping mode" foreground service. (Multiple store profiles in the UI now arrive in 9.1.)
- **Separate release checklist [YOU]:**
  - Privacy policy and Play Data Safety: content is shared with Anthropic at the user's request, and the key is stored on the device only.
  - Store listings, with no third-party logos.
  - Play internal testing and the Connect IQ beta (separate UUID; allow several days for review).

### Exit criteria for the one-shot run
- Every non-[YOU] item up to the cut line is `[x]`, or `[~]`/`[!]` with a reason.
- `tools/verify.sh` is green on CI.
- `HANDOFF.md` lists hand-offs A–C as checklists with download links.
- `KNOWN_ISSUES.md` lists every shortcut taken.

## 7. Risks
- **Blocked recipe sites.** Some sites block fetching (Cloudflare, paywalls). The fallback is the visible WebView, deferred to v1.1. Until then, "Select lines manually".
- **Parsing is never perfect.** The preview screen, the personal corrections map and the Claude fallback are there to catch errors.
- **Your own API key.** Each user brings their own key, which is fine for you but off-putting for the general public. A proxy backend is v2.
- **Instagram.** oEmbed behaviour may change, and so may Meta's terms. Video extraction depends on on-screen text or clear speech, so quantities will often be estimates.
- **Speech recognition.** Recognising a file isn't available on API 26–32, and some OEM recognisers send audio to servers.
- **Watch messaging:**
  - The watch app has to be open to receive. That's why sync is pull-based, with an outbox.
  - Background message events and the glance have tight memory limits, so both are kept tiny.
- **Connect IQ device files** may need a Garmin login, which would block CI builds for some devices.
- **Learned aisle order can be wrong** when you tick items at home or out of order. Mitigations: the trip filter, the app only *suggests* changes, and pinned aisles stay put.
- **Out of scope:** iPhone (KMP later) and shared lists (v2; "Share as text" for now).
- **Copyright:** test fixtures contain only trimmed structured data, and the golden lines come from openly licensed sources.

## 8. Later ideas (v2+)
- Shared lists via a sync backend, and a backend proxy for Claude.
- Full pantry tracking (stock levels and expiry dates), beyond the stock learning in v1.
- Wear OS tile and home-screen widget.
- whisper.cpp transcripts.
- Claude-assisted substitutions and scaling.
