# HyperShop — Plan (rev 3)

Recipe URL, Instagram reel or cookbook photo → parsed ingredients → you review it and tick what's in stock → merged, aisle-sorted shopping list on Android, mirrored and tickable on a Garmin watch. Optional Claude API integration for harder parsing.

## 0. Change log
- **rev 2:** five reviews (Android, parsing, Claude/Instagram, Garmin/sync, execution). Watch work moved before Claude; MVP cut line; progress files; new sync protocol; Claude calls made directly over HTTP.
- **Since rev 2:** review screen with in-stock ticks; stock learning; pack sizes; watch extras; learned aisle order; cookbook photos; meal planner.
- **rev 3:** four adversarial reviews (user scenarios, consistency, one-shot failure, new features). Main changes:
  - **The build is split into three runs (§6).** One run can't build everything. The watch can't be compiled without device files that you have to download, and Claude can't be tested without your key.
  - **Purchases are recorded per item** (`Purchase`), not as a flag on each recipe's contribution. Removing a recipe, rescaling it, or buying more than needed through packs no longer loses or miscounts what you've bought.
  - **Each time a recipe is added counts separately** (`RecipeUse`), so a recipe planned twice, or added twice, is tracked correctly.
  - **Items are keyed by `mergeKey`.** Catalogue updates merge duplicate items instead of crashing.
  - **Sync fixes:**
    - Every acknowledgement now says whether each op was applied or rejected, and returns the item's new revision, so undo works.
    - Each watch install and each phone database has its own ID, so reinstalls and restores don't tick the wrong items.
    - Ops carry timestamps from v1, so aisle-order learning later needs no protocol change.
    - When the list is too big for the watch, the true counts per aisle are still sent.
  - **Several rules are now defined exactly:** the pack choice, stock learning (only your own taps count, plus an "assumed in stock" banner), and aisle learning (which aisle came before which, ignoring "ticked at home" trips). Auto "next aisle" is replaced by a prompt, and long-press Back is dropped.
  - **Agent safeguards:**
    - Exact library versions are pinned.
    - The golden test data is frozen before any parser code.
    - Every data row that wasn't checked by a person is tagged.
    - Gates run locally; CI is best-effort.
    - Clear memory limits for Gradle.
    - No branch or PR changes made by the agent.

## 1. Architecture

```
 Chrome / Instagram ─share URL──▶ ┌──────────────── Android app (Kotlin) ─────────────────┐
 Camera / gallery (v1.1) ───────▶ │ Import worker ─▶ Extractor ─▶ Rule parser               │
 paste link (chip)                │      │           (WPRM/JSON-LD/  │                      │
                                  │      │            microdata)     │                      │
                                  │      └─▶ OCR (v1.1) ─▶ Claude (opt-in) ─┤               │
                                  │                                         ▼               │
                                  │               Review before adding (in-stock ticks)     │
                                  │                                         ▼               │
                                  │        Merge ─▶ Categorise ─▶ Room (:data)              │
                                  │                                 ▲        │              │
                                  │              WatchSync (App-scoped) ◀────┘  ▼ UI        │
                                  └──────────────────────────┬──────────────────────────────┘
                                                             │ Connect IQ Mobile SDK
                                                 ┌───────────▼────────────┐
                                                 │ Connect IQ watch-app   │ list, ticks,
                                                 │ + glance  (Monkey C)   │ outbox, offline
                                                 └────────────────────────┘
          api.anthropic.com ◀── HTTPS, only when the user enables Claude, with their key
```

- **Local-first, no backend.** Network is used only to fetch pages, Meta oEmbed (v1.1), and Claude when it's turned on.
- **Why not a web app:** a Garmin watch app can only talk to a phone companion app through the Connect IQ SDK, or to a server. A web app would need a backend.

## 2. Tech stack

**Pinned versions.** In step 0.4 the agent writes `libs.versions.toml` with exact versions from the list below, using the latest *stable* patch release within each listed line. It proves the whole stack with one empty Robolectric + Roborazzi + Hilt test before any feature code, then **freezes the versions for the rest of the run.** No betas.

| Area | Choice |
|---|---|
| Build | Gradle **8.14.3** wrapper, **AGP 8.13.x** (not 9.x), Kotlin **2.2.x** stable with the matching KSP, JDK **21** toolchain for compile and tests (`jvmTarget` 17) |
| Android | Compose BOM (stable for Kotlin 2.2), Material 3, **compileSdk/targetSdk 36**, minSdk 26, edge-to-edge, predictive back |
| Storage | Room 2.7.x (KSP, `exportSchema`) in `:data`; DataStore |
| Background | WorkManager plain `OneTimeWorkRequest` (not expedited); `hilt-work` with `Configuration.Provider` and the default initializer removed from the manifest |
| Networking / HTML | OkHttp 4.12.x (5 MB body cap, no cleartext) + MockWebServer; Jsoup |
| Serialization | kotlinx.serialization |
| DI | Hilt 2.5x (latest stable that supports AGP 8.13), `:app` only |
| Claude API | Direct calls to `/v1/messages` over OkHttp, `anthropic-version: 2023-06-01`. Default `claude-opus-5-5`; the model is chosen in settings |
| Secrets | Android Keystore AES-256-GCM key; the encrypted API key goes in its own DataStore file, excluded from backup |
| Watch link | `com.garmin.connectiq:ciq-companion-app-sdk` **2.4.0** |
| Watch app | Monkey C, Connect IQ SDK **9.2.0**, `watch-app` + `(:glance)`, minApiLevel 3.2 |
| Logging | Timber, plus an in-app "Debug log → share" screen |
| Tests | JUnit5 + kotest-property (pure JVM); JUnit4 + Robolectric **4.16+** (`sdk=36`, plus `sdk=26` for worker tests) + Compose test rule with **semantics assertions** + Roborazzi (snapshots recorded only, never a gate) |
| Lint | ktlint and detekt **Gradle plugins** (no CLI downloads) |
| CI | GitHub Actions, best-effort: tests, `assembleDebug`, APK artifact, nightly release (`permissions: contents: write`) |
| **Not in v1 dependencies** | ML Kit, `MediaMetadataRetriever` frame sampling, Photo Picker, `SpeechRecognizer`, CameraX/FileProvider: these arrive in Steps 8–9 |

`gradle.properties`: `org.gradle.jvmargs=-Xmx4g`, `kotlin.daemon.jvmargs=-Xmx2g`, `org.gradle.workers.max=3`, test `maxHeapSize=2g`, `maxParallelForks=1` for `:app`. The container has 15 GB and no swap.

## 3. Core features

### 3.1 Import
- **Entry points (v1):**
  - **`ACTION_SEND` text.** The app pulls the URL out with `Patterns.WEB_URL`, takes the first https link, strips `utm_*` and `igsh`, and uses `EXTRA_SUBJECT` as the fallback title.
  - **"Paste link" chip.** It appears only when `ClipDescription` says the clipboard holds text, and the clipboard is read only when you tap it.
  - **Surviving restarts.** `singleTop` + `onNewIntent`, with state in SavedStateHandle and a Room `ImportJob`.
  - **Image and video shares are v1.1** (Step 8).
- **Imports run as a WorkManager job** that writes `ImportJob(id, state, inputRefs, resultJson, error)`, and the review screen observes it.
- **Extraction order:**
  1. WPRM/Tasty plugin markup
  2. JSON-LD `Recipe`
  3. Microdata
  4. Heading heuristic
  5. "Select lines manually"
- **JSON-LD quirks**, each with its own test: `@graph`; `@type` given as an array; several Recipe objects on one page; `recipeIngredient` as a single string or under `ingredients`; nested arrays; HTML entities, including double-encoded ones; tags inside strings; lenient JSON; the forms `recipeYield` takes.
- **Blocked or JavaScript-only pages (v1.1, Step 10.4):** a visible in-app WebView sheet with an "Extract recipe" button. It uses a separate profile, clears cookies afterwards and has file access off. It is never used for Instagram. **Until then, these pages go to "Select lines manually".**
- **Importing the same URL again** asks: "Already on your list — add again?" Yes creates a second `RecipeUse`.

#### Review before adding
Nothing is added to the list until you confirm on this screen. It is used for every import, and in v1.1 for "Shop for this week".
- **Header:** title(s), a servings scaler, and a summary line such as "14 items · 9 new · 3 added to existing · 2 in stock".
- **Rows**, grouped by aisle in the active store's order. Each row shows what the item will look like after merging:
  - **New:** "Red onion — 2"
  - **Existing:** "Milk — +200 ml → 700 ml". With packs on: "Milk — still 1 × 1 l" or "Flour — 1 → 2 packs".
  - **Partly bought:** "Butter — need 50 g more (100 g bought)"
- **"In stock" checkbox on every row.** Ticking it stops the item being added.
  - In-stock rows **stay where they are** in their aisle, greyed out with a strikethrough. They do not sink.
  - **Pre-ticked by rule:** staples (from the `Staple` table, seeded with salt, pepper, oil and water), and optional or garnish lines.
  - **"I have some" (long press):** set how much you have. It's stored once per item per review (`ItemHave`), so it is never counted twice when several recipes need the same item.
  - **"Always in stock"** adds the item to `Staple`.
- **Stock learning**, which pre-ticks items you usually have:
  - **What gets recorded:** only reviews you *confirm* count, and each item counts at most once per review, in `StockStat`.
  - **Only explicit choices count.** Items pre-ticked by the staple or flag rules, or by learning itself, are **not** counted as "marked in stock" unless you tap them.
  - **When it pre-ticks:** after 3 or more explicit in-stock marks in the last 4 confirmed reviews where the item came up. Such rows get a "usually in stock" label.
  - **When it stops:** if you untick a learned pre-tick twice in a row, it's suppressed. The suppression expires after 5 more reviews, or as soon as you mark the item in stock by hand 3 times.
  - **It decays:** if the item hasn't come up in 60 days, learning for it resets.
  - **Banner above the Add button:** "5 assumed in stock: Butter, Eggs, … [Review]". Tapping it scrolls through those rows.
  - **Settings:** "Forget stock habits", plus a reset on each item.
- **Bulk actions:** "Mark aisle in stock" and "Clear in-stock".
- **Line details:** tapping a row shows the source line(s), with live re-parsing as you edit the raw text and structured fields below it. "A or B" lines let you swap to the alternative.
- **"Add N items" / "Cancel".**
  - Confirming applies the preview's **insert-only** diff (new contributions, haves and decisions) in one transaction.
  - If `listRev` changed while the screen was open, the preview is recomputed and the screen shows "List changed — review updated" before applying.

### 3.2 Ingredient parser
`"2 ½ cups (300g) plain flour, sifted"` → `{qty 5/2, unit CUP(locale), alt 300 g, name "plain flour", note "sifted"}`.
- **Prep words vs identity words.** Prep words (chopped, diced, sifted…) go to `note`. Identity words (ground, smoked, red, dried, tinned, frozen, powder, unsalted…) stay in the name.
- **Units depend on the recipe's locale.** The locale comes from the TLD, `inLanguage`, or a setting.
  - cup: 240 ml (US) or 250 ml (metric)
  - pint: 568 ml (UK) or 473 ml (US)
  - "stick" of butter
  - Case matters: `T` = tbsp, `t` = tsp.
  - "oz" in front of a liquid lowers confidence.
  - The Australian 20 ml tablespoon is v1.1.
- **Item-specific units:** clove, bunch, tin, packet, sprig and slice.
- **Several ingredients on one line:**
  - "X and Y" is split only when both halves are in the catalogue.
  - "A or B" becomes A, with B stored as the alternative.
  - "juice/zest of N lemon(s)" becomes N lemons.
- **Flags:** `optional`, `garnish`, and `noScale` ("to taste", "for frying", or no quantity at all).
- **Section headers:** lines ending in ":", lines starting "For the…", and WPRM group names. These become labels, not items.
- **Singularising:** only the head noun, and only when the singular form is in the catalogue. Otherwise the word stays as written.
- **Synonyms:** map to one catalogue ID, which can depend on locale. "pepper" is disambiguated from context.
- **Confidence:** start at 1.0, then subtract:
  - unparsed tokens −0.4
  - unknown unit −0.3
  - name not in the catalogue −0.2
  - ambiguous split −0.2

  Lines that are meant to have no quantity score 1.0. `CONFIDENCE_THRESHOLD` defaults to 0.6, and the eval in step 7.6 can change it.
- **Personal corrections map (v1.1):** when you correct a line, the raw pattern and its parse are saved and applied to future imports.

### 3.3 Merge engine and quantities
- **Item identity:**
  - Every item has a single `mergeKey`, either `cat:<catalogId>` or `name:<normName>`, and it is unique.
  - When the catalogue changes version, a re-resolution migration merges items that now share a key, and rewrites the keys in `CategoryOverride`, `StockStat`, `PackOverride` and `Staple`.
  - Head-noun matching is used **only to pick an aisle**, never to merge items.
- **What you need:** `need = max(0, Σ contributions − Σ purchases − Σ haves)`, worked out separately for each dimension.
  - Contributions are stored in base units as rationals.
  - Volume and mass are converted into each other only for catalogue rows that have `gramsPerCup`. Other mixes stay as a compound quantity, e.g. "1 onion + 200 g".
- **Item states:**
  - **NEED** — nothing bought yet.
  - **PARTIAL** — something bought, more still needed. Shown as "need 1 more (2 bought)" and counted as **unticked** everywhere.
  - **DONE** — `need = 0` and at least one purchase.
  - "Ticked" in the UI and in the sync protocol means **DONE**.
- **Ticking and unticking:**
  - **Ticking** records a `Purchase` of the amount shown: the remaining need, or the total of the planned packs when packs are on. So leftover from a pack covers later recipes.
  - **Unticking** deletes the most recent `Purchase` for that item.
- **Removing or rescaling a recipe** changes only the contributions. Purchases are never deleted this way. If the purchases now exceed the need, the item becomes DONE.
- **"Clear ticked"** deletes DONE items along with their purchases.
- **Shopping display:**
  - Spoonfuls or pinches of pantry and liquid items show as the name alone.
  - Container conversions: clove → bulb, tin = 400 g.
  - The exact totals are shown in the item detail sheet.
- **Pack sizes:**
  - Each catalogue row lists its sizes with dimensions, e.g. `500g|1kg`, `6ct|12ct`, `1l|2l`. Packs are on by default.
  - **Choice rule:** among combinations of up to 4 packs that cover the unpurchased need, minimise the leftover. Leftovers up to max(the smallest pack, 15% of the need) count as equal. Then prefer fewer packs, then the larger single pack. A small dynamic-programming search over base units does this.
  - If no combination of 4 or fewer packs covers the need, or the item has a compound or mixed-dimension quantity, it shows the rounded quantity instead.
  - **Display:** "1 × 500 g (need 350 g)". On the watch the sub-label is limited to 18 characters, e.g. "1×500g · need 350g".
  - `PackOverride` lets you edit the sizes per item.
- **Rounding:**
  - Counts round up, or to the nearest ½ for produce.
  - Mass and volume round to friendly steps.
  - Fractions are shown as ½ ⅓ ¼.
  - A setting chooses metric, imperial or as-written.
- **Scaling:**
  - Each `RecipeUse` has its own servings, and its contributions are stored already scaled.
  - Changing servings rescales that use's contributions; `noScale` lines are left alone. If an `ItemHave` exists, it is kept as it is.
- **Manual changes:**
  - A `ManualAdjustment` records `DELTA` or `OVERRIDE(qty, baselineSum)`.
  - **Overrides:** if the contributions change after an override is set, the item is flagged: "Recipe changed: keep 3 or use 6?" Need = override − purchases. Removing the last contribution asks whether to keep the override.
  - **Manually added items** are a `RecipeUse` with no recipe (`kind=MANUAL`).
- **Dry run:** `Merger.preview(list, incoming, decisions, haves)` is a pure function. It returns an **insert-only** diff, which can be safely combined with ticks that arrive in the meantime.

### 3.4 Aisles and categorisation
- **Aisles** are a table seeded with stable codes, and you can rename, hide or add them.
  - The defaults are Produce, Bakery, Meat & Fish, Dairy & Eggs, Chilled, Frozen, Tins & Jars, Pasta/Rice/Grains, World Foods, Baking, Nuts & Dried Fruit, Oils & Vinegars, Spices & Condiments, Free-from, Drinks, Household and Other.
- **Stores:**
  - Migration 1 seeds `StoreProfile(1, "My store")`, with `AisleOrder` rows copied from `Aisle.defaultOrder`.
  - The list always reads its order from `AisleOrder` for the active store, which is kept in DataStore and starts as 1.
  - The v1 UI supports one store. More stores arrive with Step 9.1.
- **Lookup order:**
  1. User override
  2. Identity words (frozen → Frozen, tinned → Tins)
  3. Catalogue ID
  4. Head noun
  5. Keyword rules
  6. Other
- **Moving an item to another aisle** asks "Always put <item> in <aisle>?". Yes writes a `CategoryOverride`; "Just this time" doesn't.
- **Catalogue:**
  - **Source:** the most frequent names from the **NYT ingredient-phrase-tagger snapshot (Apache-2.0)**, about 400 rows.
  - **UK names, aisles, staples and pack sizes** are written by the agent from general knowledge, and every row is tagged `src=nyt-freq` or `src=agent-estimate`. The MVP has pack sizes for about 40 items.
  - **Format:** a CSV, generated into a resource file that is loaded lazily. A Gradle task validates it.
  - **Review:** `catalog/REVIEW.md` is generated for you. It lists the top 60 rows, every UK/US synonym and every pack size, and takes about 10 minutes to check.
- **Learned aisle order (v1.1, Step 9.1):**
  - **What's logged:** `TickEvent(storeId, aisleCode, tickedAt (UTC, phone-corrected), source, afterAutoPrompt, bulk)`. The phone works out the watch's clock offset from the watch time sent in `hello`.
  - **What counts as a trip:** ticks from **one device**, with gaps under 20 minutes, at least 5 ticks, and at least 8 minutes long.
  - **Trips that are thrown out:**
    - 80% or more of the ticks fall within 5 s of each other
    - ticks that came from "Clear ticked" or a bulk action
    - ticks made straight after a "next aisle" prompt
  - **How the order is learned:**
    - It counts, for each pair of aisles, which came first: A before B in at least 70% of the trips where both appear. The pairs are combined into one order with a Borda count.
    - Only trips whose order *differs* from the order shown on screen count, so the learner doesn't just repeat the current sort.
  - **Applying it:** after 2 trips that count, a card suggests the reorder and you choose. Pinned aisles stay where they are.
    - There's no silent auto-apply. If you turn on "Auto-apply", a snackbar with Undo appears each time it reorders.
  - **Simpler option, also offered:** "Sort aisles like my last trip".
  - **Privacy:** `TickEvent` is kept for 90 days, left out of backup and export, and cleared together with "Forget stock habits".
  - **Active store:** a "Shopping at: <store>" chip on the list shows which store the ticks are logged against.

### 3.5 List UI
- **Layout:** grouped by aisle, in store order. DONE items sink to the bottom of their aisle and are dimmed, with a tick mark too, so it doesn't rely on colour alone. PARTIAL items stay in place and show "need X more".
- **Actions:** tap to tick or untick, swipe to delete (with an accessibility action as an alternative), add by hand (parsed), clear ticked, clear all, undo.
- **Item detail sheet:** the exact total for each dimension, which recipes it comes from, the pack plan and leftover, edit pack sizes, change aisle, and manual adjustment or override.
- **Recipes tab:**
  - Shows the recipe uses that are on the list, with their source and servings.
  - **Remove from list** deletes the use's contributions and decisions. The `Recipe` row stays.
  - **"Add the in-stock items now"** reopens the review screen, pre-filled with the full amounts and nothing pre-ticked.
- **Other:** "Share list as text". JSON export/import through SAF: import **always assigns new item IDs** and a new `listEpoch`. Auto-backup leaves out the key file and `TickEvent`.

### 3.6 Garmin app and sync
- **Watch UI:**
  - **Aisle list:** shows the true number of items left in each aisle, from `leftByAisle`. An aisle with hidden items shows "+N on phone".
  - **Opening an aisle:** a `CheckboxMenu`. Choosing an item ticks it, and choosing it again unticks it. That *is* the undo.
  - **Finishing an aisle:** shows the prompt "Produce done ✓ — [Next: Bakery ▸] [Undo <last item>]". Moving on happens only when you choose it, via `switchToView`.
  - **Optional auto-advance**, off by default, in the watch app's settings.
  - **There's no long-press Back.** Devices reserve it for the system.
  - **Vibration:** on tick and on aisle done, only `if (Attention has :vibrate)`, and it can be turned off.
  - **Each row:** the item name as the label, plus a sub-label of up to 18 characters with the quantity or pack.
  - "Last synced", an empty state, and "Update watch app".
  - A `CustomMenu` prototype is v1.1.
- **Glance:** "Shopping: 14 left · Produce next", read from the `glanceSummary` key.
- **Messages:**
  - Messages are native Monkey C objects, not JSON. Each one is `{v, t, epoch, ...}`.
  - `hello` carries `minV`/`maxV`, `installId`, `listRev` and the watch clock.
  - **Version rules:** unknown keys are ignored. `v` changes only for breaking changes. The two sides use the highest version they share, or show "Update app" if there's none.
- **Protocol (v1):**
  - **The phone is the source of truth.**
    - Every change to an item increments its `rev`, and every change to the list increments `listRev`.
    - `listEpoch` is a UUID that is regenerated whenever the database is restored or imported. The watch throws away its cache and outbox when the epoch changes.
  - **Phone → watch:** always a full snapshot, `{epoch, listRev, items:[[id, name, sub, aisleIdx, done, rev]], aisles:[[code, name, leftCount, hiddenCount]], more, acks:[[seq, result, itemId, newRev]]}`.
    - **When the list doesn't fit:** items are dropped in this order:
      1. DONE items
      2. Names shortened to 16 characters
      3. Unticked items, starting from the end of the store order

      Then `more` and `hiddenCount` are set. The size cap is measured on each target.
  - **Watch → phone:** ops `{installId, seq, itemId, done, baseRev, ts}`.
    - The phone deduplicates on `(installId, seq)`.
    - It applies the op if the item exists and either `rev == baseRev` **or** every change since `baseRev` came from this same install (`Item.lastWriter`).
    - **A stale tick**, where the item gained a contribution since the watch last saw it, records a `Purchase` of what the watch saw. The result is `partial`, and the watch shows "need 1 more".
    - Ops on deleted or unknown items are acked as `rejected`, and the watch shows "Changed on phone".
    - **Every op is acked with a result and the item's `newRev`.** The watch updates its local rev from it, so a second op on the same item (such as an undo) is accepted.
  - **Pull, not push:**
    - When the watch app starts or resumes it sends `hello`, and the phone replies with a snapshot.
    - Pushing a snapshot after each change is best-effort only.
    - On `onDeviceStatusChanged(CONNECTED)` or when the phone app starts, the phone sends `resync`. That means "send `hello` and your unacked outbox".
  - **Optimistic watch:** pending ops are re-applied on top of every snapshot.
  - **Outbox:**
    - Holds up to 500 ops.
    - Only ops that haven't been **sent yet** are collapsed, to the latest state for each item, keeping the first `ts`.
    - Ops that have been sent but not yet acked are never collapsed.
- **Watch storage:** one key per aisle chunk, plus `meta` (epoch, installId, listRev), `outbox` and `glanceSummary`. Each is under 32 KB, and everything is wrapped in try/catch.
- **Android side:**
  - `WatchSync` is application-scoped, with no permanent service.
  - Add `<queries>` for `com.garmin.android.apps.connectmobile`.
  - Handle the init errors with prompts. `openStore(appId)` opens the store page.
  - A "Shopping mode" foreground service (`connectedDevice`) is v1.1.
- **Devices:** the exact product IDs and memory limits come from the device files and go in a table in `garmin/README.md`. Start with 3 devices you supply (e.g. fr265, fenix7, venu3), then add more.

### 3.7 Claude API integration (optional, opt-in)
- **Settings:**
  - Mode: Off / Fallback / Always.
  - **Key:** "Test key" calls `GET /v1/models`, which costs nothing.
  - **Model dropdown:** Opus 5.5 is the default, with Sonnet 5.5 and Haiku 5.5 as the other options.
  - A monthly spend cap, and a cost counter that includes cache tokens.
  - Advice to create a dedicated key with a spend limit set in the Console.
- **Request:**
  - `output_config.effort:"low"` is always set explicitly, and `thinking` is never sent as disabled.
  - `max_tokens` 8000, non-streaming.
  - **Refusal fallbacks:** for Opus 5.5 and Sonnet 5.5 only, send `fallbacks:"default"` with `anthropic-beta: server-side-fallback-2026-07-01`. Haiku gets neither.
- **Strict schema** (`output_config.format`):
  - `additionalProperties:false` on every object, all fields listed in `required`, and optional fields made nullable with `anyOf`.
  - **Top level:** a `status` enum (`ok | no_ingredients | not_a_recipe`), `title`, `servings`, `ingredients[]`, `source_urls[]`.
  - **Each ingredient:** `raw`, `name`, `quantity`, `quantity_max`, `quantity_text`, a `unit` enum (plus `other`), `alt_quantity`, `alt_unit`, `note`, `section`, `optional`, `estimated`, and an `aisle_suggestion` enum.
  - No numeric or length constraints; those are checked in Kotlin.
- **Response handling:**
  - Check `stop_reason` first. A `refusal` gives "Couldn't parse — edit manually" with no retry; `max_tokens` is an error.
  - Then validate against the schema.
  - **HTTP errors:** 401/403 means a bad key; 429/529 back off; 5xx retries once.
  - `x-api-key` is always redacted from logs.
- **Prompt injection:**
  - Page and caption text goes inside `<untrusted_content>` tags, and Claude gets no tools.
  - A URL is accepted only if it appears word for word in the source text, is https, and you tap it.
  - Input length is capped, and everything still goes through the review screen.
- **Offline:** the rule parser's result is used, marked "Claude pending", with a manual "Retry with Claude".
- **Prompt caching:** off. It's turned on only for the combined weekly review in v1.1, and only when the prompt is over the model's minimum cacheable length.
- **Cost on Opus 5.5:**
  - About $0.04 per text import.
  - About $0.05 per reel with OCR and 3 frames.
  - Sonnet 5.5 costs half that. Haiku 5.5 costs about $0.001–0.003 per import.
  - Media imports show an estimate before sending.
- **Privacy dialog:**
  - Content goes to Anthropic under your account's terms.
  - Google may receive audio (v1.1).
  - Media is deleted after import.

### 3.8 Instagram import (v1.1)
Sharing from the Instagram app sends only the URL.
1. **Caption.** Try Meta oEmbed first (`graph.facebook.com/v25.0/instagram_oembed`). A reviewer reported it needs no token since 2026-06; **check this in 8.1**. The caption comes from the returned `html`, with one plain `og:` fetch as a fallback.
   - One call per share, and no WebView on instagram.com.
   - Pulling text this way is a grey area under Meta's terms.
2. **Routing.** If the caption contains a recipe link, run the web import. If the text looks like an ingredient list, use the rule parser, then Claude if confidence is low. Otherwise go to "Add media".
3. **"Add media"** explains: use Instagram's Download, or screen-record with audio (Android 11+), or screenshot the expanded caption.
   - **Screenshots:** OCR (§3.9 post-processing), then the parser, then Claude if needed. Works without Claude.
   - **Video:**
     - Sample about 1 frame per second, run OCR on each, and keep the frames where the text changed.
     - Send the OCR text, plus 2–3 frames at 1092 px when confidence is low.
   - **Transcript (experiment, ADR 004):** decode the audio to PCM, then use the on-device recogniser with `EXTRA_AUDIO_SOURCE`. This needs API 33+, and is only used when the phone supports it; otherwise it's skipped with a notice. whisper.cpp is v2.
   - **Paste caption.**
4. Everything goes to the review screen. Anything taken from video or speech is marked "estimated".

### 3.9 Cookbook photo import (v1.1)
- **Entry points:** "Scan a cookbook page", which uses `TakePicture` with a `FileProvider` cache file, deleted after use. Also the Photo Picker and image shares.
  - `verify.sh` checks that the merged manifest does **not** contain `CAMERA`.
- **No crop UI.** OCR runs on the whole page, and the recognised lines appear as a tappable list. The ingredient block is preselected: lines under an "Ingredients" heading, or the densest run of lines that start with a quantity. This reuses the "Select lines manually" screen.
- **OCR post-processing:**
  - **Columns:** blocks are grouped into columns by how much their x-ranges overlap, then sorted top to bottom within each column, with columns read left to right.
  - **Wrapped lines:** joined using layout, not wording: an indent relative to the column edge, and a line gap under 1.3× the line height. Text cues only break ties.
  - **Fractions** (`source=OCR`):
    - `%`, `Y2`, `V2` or `Vz` next to a number becomes ½.
    - `O` inside a number becomes `0`, and `l` becomes `1`.
    - "N1/2" patterns that give implausible amounts (more than 4 cups or 6 tbsp) are re-read as "N ½" and lower the confidence.
  - OCR quantities are flagged "check" on the review screen. Low-confidence lines go to Claude with a downscaled page image.
- **Saving:** title and servings are suggested from "Serves/Makes". `source: PHOTO`. Only the text is kept.

### 3.10 Meal planner (v1.1)
- **Library:** every imported `Recipe` is saved. There's search, favourites, and filters for "on list" and "planned". Deleting a recipe that's on the list or planned asks first, then turns its contributions into manual ones (FK RESTRICT plus a confirm).
- **Week view:** 7 days, each with optional Breakfast, Lunch, Dinner and Other slots. You add from the library, or from the review screen with "Plan for…". Each `PlannedMeal` has its own servings.
- **"Shop for this week":**
  - Opens one combined review of every meal not yet added. Rows show which meals need each item ("Onion — 3 · Mon, Wed").
  - Confirming creates one `RecipeUse` for each meal. Each use keeps its own decisions.
- **Removing a planned meal** removes only that use's contributions. A recipe planned twice counts twice, and moving a meal to another day changes nothing.
- **Other:** "on list" and "shopped" are worked out from the uses, not stored as flags. Copy last week, clear a week.

## 4. Data model (Room, `:data`)

**MVP schema (v1):**
- `Item(id AUTOINCREMENT, mergeKey UNIQUE, catalogId?, normName, displayName, aisleCode, position, rev, lastWriter?, updatedAt)`
- `RecipeUse(id, recipeId FK SET NULL nullable, kind: RECIPE|MANUAL, servings, addedAt)`: one row each time something is added to the list.
- `Contribution(id, itemId FK CASCADE, useId FK CASCADE, qtyBase)`
- `Purchase(id, itemId FK CASCADE, qtyBase, at, source: PHONE|WATCH)`
- `ItemHave(id, itemId FK CASCADE, useId FK CASCADE, qtyBase)`
- `LineDecision(useId FK CASCADE, recipeLineId, status: ADDED|IN_STOCK|PARTIAL|EXCLUDED)`
- `ManualAdjustment(itemId, kind: DELTA|OVERRIDE, qty, baselineSum?)`
- `Recipe(id, url, title, servingsOriginal, unitLocale, importedAt, source: WEB|MANUAL, parsedBy: RULES|CLAUDE, claudePending)`
- `RecipeLine(id, recipeId FK CASCADE, section, raw, parsedJson)`
- `ImportJob(id, state, inputRefs, resultJson, error, createdAt)`
- `Aisle(code PK, name, hidden, defaultOrder)`, `StoreProfile(id, name)`, `AisleOrder(storeId, aisleCode, position)`
- `CategoryOverride(key, aisleCode)`, `Staple(key, source: SEED|USER)`, `StockStat(key, history bits, lastSeenAt, suppressedUntilReview?)`, `PackOverride(key, packSizes)`. Here `key` means `mergeKey`.
- `ClaudeUsage(date, model, inputTokens, outputTokens, cacheWrite, cacheRead)` (added with Run 3, migration v2)
- `DeletedItem(id, deletedAt)` (7 days), `WatchInstall(installId, lastSeq, clockOffsetMs)`
- **Meta (DataStore):** `listEpoch`, `listRev`, `catalogVersion`, `activeStoreId`

**v1.1 additions:**
- `Recipe.source` gains `INSTAGRAM|PHOTO`, plus `favourite`.
- `PlannedMeal(id, date, slot, recipeId FK RESTRICT, servings, useId?)`
- `TickEvent(…)` (§3.4), and `AisleOrder.pinned`.
- `UserCorrection(rawPattern, parsedJson)`

**Migrations:** the schema is exported to `android/data/schemas/`. `MigrationTestHelper` tests start from v2.

## 5. Repo layout and project constants

```
android/      :app, :core (pure JVM: model, catalog, parser, merge, sync codec — packages, not modules),
              :data (Room + repository), :extract (fetch + extractors), :claude (Run 3), :media (v1.1)
garmin/       Connect IQ project (manifest, source/, resources/, test/), README with device table
catalog/      ingredients.csv, synonyms.csv, SOURCES.md, REVIEW.md (generated)
testdata/     ingredients/ (golden, frozen), pages/ (trimmed fragments + synthetic HTML),
              sync/ (shared protocol test vectors), claude/ (recorded: synthetic|live), ocr/, video/ (v1.1)
tools/        verify.sh, eval-claude/, fetch-live-check/, contact-sheet (screenshots → one image)
docs/         decisions/ (ADRs), HANDOFF.md, KNOWN_ISSUES.md, parser-spec.md, screenshots/
.github/workflows/, .claude/ (SessionStart hook)
PROGRESS.md
```

| Constant | Value |
|---|---|
| Android app ID | `com.robert4lyf.hypershop` |
| Watch app UUID | generated in 0.4; separate UUIDs for beta and release, set as Android build flavours |
| Versioning | `versionCode` = CI run number, or `git rev-list --count HEAD` when built locally; `versionName` from the git tag, or `0.0.0-dev` |
| Signing | the debug keystore is committed; the release keystore and the Connect IQ developer key are CI secrets **[YOU]**; a throwaway developer key is generated in the container for compiling only |

## 6. Build plan

### Three runs, not one
A single run can't build the whole MVP reliably:
- **The watch app can't be compiled in the container.** The Connect IQ SDK downloads fine, but `monkeyc -d <device>` needs device files, which require a Garmin login. The simulator also needs graphics libraries this container doesn't have.
- **Claude can't be tested honestly without your key.**
- **The work is too big for one agent session.** It would fill several context windows.

| Run | Delivers | Needs from you before it starts |
|---|---|---|
| **Run 1** | Steps 0–4: phone app, parser, merge, review screen, list, sync codec in Kotlin | nothing |
| **Run 2** | Steps 5–6: watch app and phone ↔ watch sync | Hand-off A done, **plus device files** for 2–3 watches (see Step 5.0) |
| **Run 3** | Step 7: Claude for text | Hand-off B done, **plus an API key** set as `ANTHROPIC_API_KEY` in the environment, with a spend limit |
| Later | Steps 8–10 (v1.1) | as listed per step |

### Rules for the agent
1. **On start:** read `PROGRESS.md` and resume at the first unchecked item in **this run's** steps. Never redo checked items, and never start the next run's steps.
2. **Each sub-step:** implement it, run its check, then commit, updating `PROGRESS.md` in the same commit. Mark it `[x]`, `[~] stubbed` or `[!] blocked`, with the SHA, a note and the check command.
3. **Gates run locally:** `tools/verify.sh` must pass in the container. CI is best-effort; check its status via the API where possible.
4. **Branches:** work only on the session branch. Don't create other branches and don't merge. At the end of the run, open one PR if a base branch exists; otherwise note "create `main` and merge" in `HANDOFF.md`.
5. **Versions:** never upgrade the frozen versions mid-run. A problem goes in `KNOWN_ISSUES.md` with a workaround.
6. **Golden data and fixtures:** never edit to make a test pass. A real label error needs its own commit with a reason.
7. **Context running low:** commit, update `PROGRESS.md` and `HANDOFF.md`, and stop cleanly.
8. **Stop** at the end of the run's steps. No gold-plating.

### Things only you can provide
| Item | When |
|---|---|
| Install the APK (nightly release link) on an Android phone | Hand-offs A, B, C |
| Check the screenshot contact sheet, `catalog/REVIEW.md`, and 20 golden lines | Hand-off A |
| Create a `main` branch and merge each run's PR | After each run |
| Device files for 2–3 watches (SDK Manager on your PC, then upload as a private release asset or a URL in the environment; never commit them) | Before Run 2 |
| Garmin watch + Garmin Connect app; sideload the `.prg` | Hand-off B |
| Release keystore + Connect IQ developer key as CI secrets (keep offline backups) | Before any store build |
| Optional: a PC with a display, to run the simulator and the Monkey C unit tests | Run 2 (optional) |
| Anthropic API key with a spend limit | Before Run 3 |
| 10 test reels; 5 photographed cookbook pages | Steps 8, 9.2 |
| Play Console and Connect IQ Store accounts, privacy policy, Data Safety form | Release |

---

## Run 1

### Step 0 — Toolchain and scaffolding
- **0.1** Add `.gitignore`, `README.md`, `LICENSE`, `PROGRESS.md` (the checklist for every step), `docs/HANDOFF.md` and `docs/KNOWN_ISSUES.md`.
- **0.2** Install the Android tools with `cmdline-tools` plus `yes | sdkmanager --licenses`, pinning `platforms;android-36`, `build-tools;36.0.0` and `platform-tools`. Add an **idempotent** SessionStart hook that skips anything already installed and doesn't download the Connect IQ SDK.
  - **Check:** `sdkmanager --list_installed`.
- **0.3** Set up the Gradle project and modules (§5), `gradle.properties` (§2) and the debug keystore.
- **0.4** Write the version catalog (§2). Add one "hello" test of each kind: JUnit5, kotest-property, Robolectric `sdk=36` Compose with a semantics assertion, a Roborazzi recording, and Hilt. **Freeze the versions.**
- **0.5** ktlint and detekt plugins, `tools/verify.sh` (all tests, lint, `assembleDebug`, and the CAMERA manifest check from v1.1), and the CI workflow with the artifact and nightly release.
- **Check:** a cold `tools/verify.sh` passes in the container.

### Step 1 — Golden data first, then the core model
- **1.1** `docs/parser-spec.md`, covering the output format and every §3.2 rule.
- **1.2** **Golden lines, frozen before any parser code:**
  - 60 lines taken from the NYT snapshot. Each label is reconciled to the spec, and every change from NYT's label is written down with its reason.
  - About 300 more NYT lines with their original labels, for information only.
  - Commit them on their own, and store a checksum in `verify.sh`.
- **1.3** Model, in the `:core` model package:
  - `Rational`, `Dimension`, and `Unit` with locale factors.
  - `QtyBase`, a quantity per dimension.
  - `ParsedIngredient` and `RecipeImport`.
  - The `need` formula and the NEED/PARTIAL/DONE states.
  - Record this in ADR 002.
- **1.4** Catalogue:
  - **CSV columns:** `id, name, plural, synonyms, aisleCode, staple, shoppingUnit, displayPolicy, gramsPerCup, packSizes, src`.
  - **Contents:** about 400 rows, with packs for about 40 of them.
  - **Tooling:** a Gradle validation task, a generated resource file plus a lazy loader, `SOURCES.md`, and the `REVIEW.md` generator.
- **1.5** Sync codec in the `:core` sync package:
  - The message types (§3.6 v1, `ts` included).
  - A list/map codec, a size estimator, and the snapshot builder with the truncation order and `leftByAisle` / `hiddenCount`.
  - The op-apply rules as a **pure function**.
  - Shared vectors in `testdata/sync/*.json`.
- **Check:**
  - Tests for `Rational`, units and the catalogue checks.
  - Codec round-trips and the size cap.
  - Every sync vector, covering: tick → ack → untick; a stale tick → partial; deleted → rejected; a new `installId`; an epoch change; collapsing only unsent ops; truncation counts.

### Step 2 — Ingredient parser
- **2.1** Tokeniser.
- **2.2** Grammar, flags, headers, and/or handling.
- **2.3** Normaliser, synonyms, `mergeKey`.
- **2.4** Confidence.
- **Check:** on the **frozen 60**: name ≥97%, qty ≥98%, unit ≥97%. Report the score on the NYT-labelled set for information only.

### Step 3 — Categoriser, merge, packs, preview
- **3.1** Categoriser (§3.4).
- **3.2** Merger: sums per dimension, density, the `need` formula, display policy, rounding.
- **3.3** `PackPlanner` (§3.3 rule). Test cases:
  - 1.1 kg with sizes `1kg|5kg` → 2 × 1 kg
  - 1.6 kg
  - packs counted in items
  - no combination of 4 or fewer packs fits → rounded quantity
  - a compound quantity → no packs
  - turning packs off
- **3.4** Scaling per use, `noScale`, ManualAdjustment with `baselineSum`.
- **3.5** `Merger.preview`, an insert-only diff that includes haves and decisions.
- **3.6** Stock learning as a pure function, `StockLearner` (§3.1 rules).
- **3.7** Catalogue re-resolution migration logic.
- **Check:**
  - **Property tests:**
    - Adding a recipe use and then removing it gives back the original need.
    - Purchases are never lost when a recipe is removed or rescaled.
    - Applying a preview gives the same result as a direct merge.
    - The preview diff commutes with ticks made while the review is open.
  - **Must-not-merge pairs** (e.g. red onion and onion).
  - **StockLearner cases:** passive pre-ticks don't count; 3 of 4 triggers; suppression expires; decay after 60 days.
  - **Categorising:** at least 90% of the frozen names land in an aisle other than Other.

### Step 4 — Android app (phone only)
- **4.1** `:extract`: the fetcher plus the extractors, with a test for each quirk. About 15 trimmed real fragments plus synthetic HTML. `fetch-live-check` is manual only.
- **4.2** `:data`:
  - Schema v1 (§4), with migration 1 seeding the aisles, the store and the staples.
  - `ListRepository`, which:
    - applies previews
    - records ticks as `Purchase` rows
    - removes or rescales uses
    - handles overrides
    - handles "Clear ticked"
    - handles export/import, giving new IDs and a new epoch
    - runs the catalogue re-resolution
  - Every change bumps `rev` and `listRev`.
  - **Acceptance tests** for each of these.
- **4.3** App shell:
  - Hilt, `hilt-work` wiring, navigation, theme, edge-to-edge and predictive back.
  - `ClaudeExtractor` interface with a `Noop` implementation.
  - `WatchLink` interface with a fake.
  - The import worker as a plain OneTimeWorkRequest. Robolectric tests run it at `sdk=26` and `sdk=36` with `WorkManagerTestInitHelper`.
- **4.4–4.7:** one screen per sub-step. Each has a ViewModel test, a Robolectric Compose test with **semantics assertions** (aisle order, row text, counts), and a recorded Roborazzi snapshot.
  - **4.4** Review before adding (§3.1). Tests:
    - In stock stops the item being added.
    - "I have some" is counted once for each item.
    - The banner lists the assumed items.
    - Cancel changes nothing.
    - A `listRev` change while the screen is open triggers a recompute.
  - **4.5** List and item detail sheet (§3.5): tick/untick creates and removes purchases, PARTIAL display, swipe/accessibility delete, add by hand, clear, undo, share as text, the "Always put in…" aisle prompt.
  - **4.6** Recipes: uses, remove from list, "Add the in-stock items now", and the "add again?" prompt for a URL already imported.
  - **4.7** Settings: aisle order, rename and hide for the single store; staples; stock habits (forget all, or per item); packs on/off; display units; export/import; debug log. The Claude section is a placeholder.
- **4.8** Text share intent, paste chip, and error states (offline, blocked, not found → "Select lines manually"). Tests use real share payloads.
- **4.9** Debug "Load sample list" (3 recipes, about 40 items, partial purchases, packs) and "Fake watch". Backup rules (exclude key file).
- **4.10** `tools/contact-sheet` turns every screenshot into one image in `docs/screenshots/`.
- **Check:** `verify.sh` is green. The APK is built and on the nightly release (best-effort).
- **⏸ Hand-off A [YOU]** — a checklist in `HANDOFF.md`, at most 15 yes/no items:
  - Install the APK.
  - Share 5 recipes from Chrome, then use the review screen.
  - Check the contact sheet.
  - Check `catalog/REVIEW.md`.
  - Check the 20 golden lines.
  - Merge the PR into `main`.

## Run 2

### Step 5 — Garmin watch app
- **5.0 Prerequisite check:** the device files are available (§6 table). If they aren't, **stop the run here**, and record that in `HANDOFF.md`.
- **5.1** Download and cache SDK 9.2.0 when this step starts. Generate a throwaway developer key with openssl (RSA 4096, PKCS#8 DER).
  - **Check:** the SDK's `Comm` sample builds with `monkeyc -d <supplied device>`.
- **5.2** `manifest.xml`: minApiLevel 3.2, the supplied product IDs, Communications, and the UUID.
- **5.3** Data layer. Its logic mirrors the Kotlin sync package and is kept **data-driven** so the same vectors apply:
  - chunked storage
  - `meta` (epoch, installId)
  - the outbox (collapsing only unsent ops, keeping the first `ts`)
  - re-applying pending ops
  - handling acks with `newRev`
  - `glanceSummary`
- **5.4** UI: the aisle list with counts and "+N on phone", a `CheckboxMenu` per aisle (choose again to untick), the "aisle done" prompt, optional auto-advance (off by default), vibration behind `has :vibrate`, sub-labels, the glance, the empty state, "Last synced" and "Update watch app".
- **5.5** `:debug` build with embedded sample data.
- **5.6** Monkey C `(:test)` tests that replay `testdata/sync/*.json`. They are compiled in the container (`monkeyc --unit-test`). **Running** them needs the simulator, so that's [YOU] or optional.
- **Check:**
  - `monkeyc` builds for every supplied device, with no warnings treated as errors.
  - The test build compiles.
  - The memory table is filled in from the device files.

### Step 6 — Phone ↔ watch sync
- **6.1** `WatchSyncImpl`:
  - Initialise the SDK as `WIRELESS`, or `TETHERED` in debug builds.
  - Discovery, and re-registering on CONNECTED.
  - `<queries>`, the init-error prompts, and `openStore`.
- **6.2** Glue between the protocol and the repository:
  - Reply to `hello` with a snapshot.
  - Best-effort push after a 1 s pause.
  - Apply ops with the pure rules from 1.5.
  - Ack with result and `newRev`.
  - Handle `WatchInstall` and clock offset.
  - Send `resync`.
- **6.3** Tests against a fake `IQDevice` that replays every `testdata/sync` vector end to end through the repository.
- **Check:** `verify.sh` is green.
- **⏸ Hand-off B [YOU]:**
  - Sideload the `.prg`, install the APK, import a recipe, and open the watch app. The list should appear within 10 s.
  - Tick an item while out of range, add a recipe on the phone that also needs that item, then reconnect. The watch should show "need N more".
  - Undo a tick, and check the glance.

## Run 3

### Step 7 — Claude for text
- **7.1** ADR 001: OkHttp + kotlinx.serialization (no SDK).
- **7.2** Key storage (Keystore AES-GCM, recovery from "can't decrypt"). Settings: mode, key, "Test key", model, cost counter, spend cap, privacy dialog. Room migration to v2 (`ClaudeUsage`).
- **7.3** Request builder (§3.7): the fallbacks rule per model, explicit effort, the strict schema, untrusted-content wrapping, the input cap.
- **7.4** Response handling: `stop_reason`, validation, error mapping, usage, redaction.
- **7.5** Wiring: Fallback mode for lines below `CONFIDENCE_THRESHOLD` (or the whole page if extraction failed), "Claude pending" with retry, badges, caching results in `RecipeLine`.
- **7.6** **Live smoke test** with your key: one call per model. Record the real responses as `testdata/claude/live-*.json`. Synthetic fixtures are named `synthetic-*`.
  - `tools/eval-claude` runs the rule parser against each model on the frozen 60 plus 15 pages, reporting accuracy, cost and `output_tokens`, and suggests `CONFIDENCE_THRESHOLD` and a default model.
- **Check:** MockWebServer tests cover every `stop_reason` and error path. The live smoke test passes. The eval report is committed.
- **⏸ Hand-off C [YOU]:** using the eval report, choose the default model and mode. Try 5 hard recipes.

**— End of MVP. —**

## Later (v1.1)

### Step 8 — Instagram and media
- **8.1** Verify the oEmbed claim (ADR 003). Then build the `og:` fallback.
- **8.2** Caption routing.
- **8.3** Add the `:media` module and dependencies (ML Kit bundled, Photo Picker, image and video share intents). "Add media" screen with the how-to.
- **8.4** OCR screenshots, using the §3.9 post-processing.
- **8.5** Video frame sampling with OCR, and image blocks for Claude. Show the cost estimate.
- **8.6** Transcript experiment (ADR 004).
- **8.7** Delete temporary media after import. Update the privacy dialog.
- **Check:** unit tests for routing, the sampler and the OCR text merge.
- **[YOU]:** test the 10 reels.

### Step 9 — Smart shopping
- **9.1** Multiple store profiles in the UI, the "Shopping at" chip, `TickEvent` (migration), and `AisleOrderLearner`: pairwise ordering and trip filters (§3.4). Suggestion card, pinned aisles, "Sort like my last trip".
  - **Check:** tests on made-up trip logs: trips ticked at home are rejected, trips just after an auto-prompt are ignored, trips that only follow the displayed order are ignored, skipped aisles are handled, pinned aisles are respected.
- **9.2** Cookbook photos (§3.9): FileProvider, OCR line list, column clustering, joining wrapped lines, fixing fractions.
  - **Check:** at least 15 recorded OCR outputs, covering two columns, lines with no quantities, and fraction misreads.
  - **[YOU]:** scan 5 pages.
- **9.3** Meal planner (§3.10): migration (`PlannedMeal`, `Recipe.favourite`), library, week view, "Plan for…", combined review.
  - **Check:** repository tests (planned twice, removing a meal, deleting a recipe that's in use asks first, moving a meal changes nothing). Migration test from v2.
- **9.4** Personal corrections map. AU tablespoons.

### Step 10 — Polish
- **10.1** Icons, an accessibility pass, font scaling.
- **10.2** JVM benchmark (parsing and merging 20 recipes in under 200 ms). A list of 200 items.
- **10.3** Shopping-mode foreground service. A watch `CustomMenu` prototype.
- **10.4** Visible WebView fallback sheet (§3.1).
- **Release checklist [YOU]:**
  - Privacy policy and Data Safety: content is shared with Anthropic at your request, and the key stays on the device.
  - Store listings, with no third-party logos.
  - Play internal testing, and the Connect IQ beta (separate UUID, several days for review).

### Exit criteria for each run
- Every item in the run is `[x]` or `[~]`/`[!]` with a reason.
- `tools/verify.sh` is green locally.
- `HANDOFF.md` has the hand-off checklist and the next run's prerequisites.
- `KNOWN_ISSUES.md` lists every shortcut.

## 7. Risks
- **Agent grading its own work.** Mitigations: golden data is frozen first; changes to labels are recorded; screenshots are recorded but not gated; real Claude responses are recorded in Run 3; you review the contact sheet and the catalogue.
- **Catalogue and pack sizes are agent estimates.** They're tagged `src=agent-estimate`, you review them in REVIEW.md, and you can override them.
- **Sync edge cases.** The shared vectors are tested in Kotlin and replayed in Monkey C, but they only run on real devices or the simulator at Hand-off B.
- **Device files need a Garmin login.** Without them, Run 2 can't start (Step 5.0).
- **Blocked recipe sites.** "Select lines manually" until the WebView fallback arrives (v1.1).
- **Your own API key and cost.** Opus is the default, which costs the most. Mitigations: an estimate before sending, a spend cap, and Hand-off C picks the default. A backend proxy for other users is v2.
- **Instagram and Meta's terms may change.** Video quantities will often be estimates.
- **Speech recognition:** unavailable on API 26–32, and some phone makers' recognisers send audio to servers.
- **Stock learning can hide something you need.** Mitigations: only explicit taps count, the banner lists assumed items, rows stay visible, and learning decays.
- **Learned aisle order can be wrong.** Mitigations: trip filters, pairwise ordering, only suggestions, pinned aisles.
- **Watch memory:** CheckboxMenu size, the 500-op outbox and the glance. Measured in Step 5.
- **Clocks:** aisle learning uses UTC timestamps from one device per trip, corrected by the offset sent in `hello`.
- **Out of scope:** iPhone, and shared lists (v2; "Share as text" for now).
- **Copyright:** fixtures are trimmed to structured data only; golden lines come from Apache-2.0 data.

## 8. Later ideas (v2+)
- Shared lists via a sync backend, and a backend proxy for Claude.
- Full pantry tracking (stock levels, expiry dates).
- Wear OS tile and home-screen widget.
- whisper.cpp transcripts.
- Claude-assisted substitutions.
