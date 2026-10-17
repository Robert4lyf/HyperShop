# HyperShop — Plan

Recipe URL → parsed ingredients → merged, aisle-sorted shopping list on Android, mirrored and tickable on a Garmin watch.

## 1. Architecture

```
 Chrome / any app ──share URL──▶ ┌──────────────── Android app (Kotlin) ────────────────┐
 or paste URL                    │ Import ─▶ Recipe extractor ─▶ Ingredient parser       │
                                 │            (JSON-LD/microdata)   (qty/unit/name)      │
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
```

- **Local-first, no backend.** Everything runs on the phone. There are no accounts or hosting costs, and it works offline once a recipe is imported.
- **Why not a web app:** a watch app can only reach a phone app through the Connect IQ SDK, or reach a server over the internet. A web app would need a server, accounts and sync, which adds cost for no user benefit in v1.

## 2. Tech stack

| Area | Choice |
|---|---|
| Android | Kotlin, Jetpack Compose, Material 3, min SDK 26 |
| Storage | Room (SQLite) + DataStore for settings |
| Networking / HTML | OkHttp + Jsoup |
| Serialization | kotlinx.serialization |
| DI | Hilt |
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

## 4. Data model (Room)

- `Item(id, name, normName, aisleId, qtyJson, ticked, updatedAt, manual)`
- `Recipe(id, url, title, servings, importedAt)`
- `RecipeContribution(itemId, recipeId, qtyJson)`. The item's total is derived from these plus any manual quantity.
- `Aisle(id, name, defaultOrder)`, `StoreProfile(id, name)`, `AisleOrder(storeId, aisleId, position)`
- `CategoryOverride(normName, aisleId)`

## 5. Repo layout

```
android/        Gradle project (app module + :core:parser, :core:merge, :core:catalog pure-Kotlin modules)
garmin/         Connect IQ project (manifest.xml, source/, resources/)
catalog/        ingredients.csv (name, synonyms, aisle) → generated into Kotlin at build time
testdata/       saved recipe HTML pages + ingredient-line golden files
.github/        CI workflows
```

The parser, merge and catalog code lives in pure Kotlin/JVM modules with no Android imports. That keeps them fast to test, and they can be reused later in a server or KMP iOS build.

## 6. Milestones

1. **Core logic.** Parser, unit conversion, merge and categoriser, with golden tests. This is the hardest part and should be built first.
2. **Recipe extraction.** JSON-LD/microdata/heuristics, tested against about 30 saved pages from popular sites.
3. **Android app.** Room, list UI, import + preview, share intent, aisle editing and store profiles.
4. **Garmin app.** List view and ticking on the simulator, using hard-coded data.
5. **Sync.** Connect IQ SDK integration, the event queue and conflict handling; test on a real watch.
6. **Polish.** Dark mode, empty states, error messages for blocked sites, and app icons. Then Play Store internal testing and Connect IQ Store submission.

## 7. Risks and honest caveats

- **Sites that block fetching.** Cloudflare, paywalls and JavaScript-only pages defeat a plain HTTP fetch. Mitigation: a WebView fallback that renders the page, then extracts from it.
- **Ingredient parsing is never 100% right.** The preview screen exists so the user can catch errors. LLM parsing could be an opt-in later, but it adds an API key and cost, and isn't needed for v1.
- **Watch memory.** Older or cheaper Garmins give apps very little memory (around 64–128 KB). Keep the payload small, and limit supported devices at first.
- **Connect IQ phone messaging is flaky.** It needs the Garmin Connect app running, and delivery is sometimes delayed. The retry queue and the watch's local cache are required, not optional.
- **iPhone users are out of scope.** Keeping the core in pure Kotlin leaves a path to KMP later.
- **Sharing a list with a partner** needs a backend. That is v2.

## 8. Later ideas (v2+)
- Shared lists via a sync backend (e.g. Supabase/Firebase).
- Meal planner, and "Have it already" pantry tracking.
- Wear OS tile, and home-screen widgets.
- Optional LLM fallback parser for messy pages.
