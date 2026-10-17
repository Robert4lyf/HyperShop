# HyperShop — Plan (rev 4)

A **personal** Android app (never published). You share a recipe URL, an Instagram reel or a cookbook photo. The app parses the ingredients and shows them for review, where you tick what you already have in stock. Everything else is merged into an aisle-sorted shopping list, mirrored and tickable on a **Garmin Forerunner 245**. Optional Claude API parsing uses your own key.

## 0. Change log
- **rev 2 – rev 3:** nine reviews. Watch work comes before Claude. MVP cut line. Progress files. Three build runs. Per-use contributions. Strict Claude schema.
- **rev 4:** a second round of four reviews (quantity model, sync with a model checker, Run 1 dry run, UX and security). Plus your answers: personal use only, Forerunner 245, Android 14+.
  - **Simpler quantity model.**
    - The purchase ledger, the PARTIAL state and overrides are gone.
    - Ticking an item **covers** the contributions it had when ticked. A recipe added later shows only the new amount.
    - Nothing you bought carries over silently to later trips.
  - **Simpler and verified sync.**
    - A watch tick says "cover what I saw at `listRev` N". So ops never need a revision check, and the `lastWriter` rule is gone.
    - Ops use contiguous sequence numbers with cumulative acks (go-back-N). The phone checks the epoch and applies each op in one transaction.
    - A small model checker found the gaps in the rev 3 design. Its go-back-N variant passed with 0 violations, on 1 item and 1 watch. The new coverage and untick rules haven't been model-checked yet; Step 1.5b extends the checker to cover them.
  - **Personal use.** Removed: store release, Play policy and Data Safety, trademark checks, release signing, beta/release flavours, licence screens. minSdk 34. Only one watch model.
  - **Forerunner 245.**
    - It has buttons only, a 240×240 MIP screen and an older Connect IQ level.
    - Its memory limit, API level and glance support are read from its device file **before** any watch code is written.
  - **UX.**
    - The review screen is minimal by default.
    - A quick "Add item" field, Undo after an import, and an empty or first-run state.
    - Accessibility basics from the start.
    - Watch press debounce, with "Next aisle" as the default focus.
  - **Security.** URL fetch limits (https only, no private IPs, redirect checks). Parser size and depth limits. Hostile test fixtures. JSON import validation. Log redaction. Claude is called only from the review screen.
  - **Build details.** Verified pinned versions (§2). Step 1 reordered. A stratified, scripted golden set. A checksum and change log for golden data. Named fixture sources. Sub-steps split up.

## 1. Architecture

```
 Chrome / Instagram ─share URL──▶ ┌──────────────── Android app (Kotlin) ─────────────────┐
 Camera / gallery (v1.1) ───────▶ │ Import worker ─▶ Safe fetch ─▶ Extractor ─▶ Rule parser │
 "Add item" field / paste chip    │      │                    (WPRM/JSON-LD/  │           │
                                  │      │                     microdata)     │           │
                                  │      └─▶ OCR (v1.1) ─▶ Claude (opt-in, foreground) ─┤ │
                                  │                                              ▼        │
                                  │               Review before adding (in-stock ticks)    │
                                  │                                              ▼        │
                                  │        Merge ─▶ Categorise ─▶ Room (:data)             │
                                  │                                 ▲        │             │
                                  │              WatchSync (App-scoped) ◀────┘  ▼ UI       │
                                  └──────────────────────────┬─────────────────────────────┘
                                                             │ Connect IQ Mobile SDK
                                                 ┌───────────▼────────────┐
                                                 │ Forerunner 245 app     │ list, ticks,
                                                 │ (Monkey C, buttons)    │ outbox, offline
                                                 └────────────────────────┘
          api.anthropic.com ◀── HTTPS, only when you enable Claude, with your key
```

- **Local-first, no backend.** The network is used only to fetch pages, Meta oEmbed (v1.1), and Claude when it is turned on.
- **Native Android, not a web app.** A Garmin watch app can only talk to a phone companion app through the Connect IQ SDK, or to a server.

## 2. Tech stack (pinned — verified against Maven/Google Maven, Oct 2026)

**Version rules:**
- **Never upgrade during a run.** These versions are frozen.
- **A library not in the table** gets the newest version whose AAR metadata has minCompileSdk ≤ 36 and minAGP ≤ 8.13.
- **Many of the newest AndroidX releases need AGP 9.1 or compileSdk 37.** That is why some lines below are pinned below their latest version.

| Item | Version | Notes |
|---|---|---|
| Gradle wrapper | 8.14.3 | also at `/opt/gradle-8.14.3` |
| AGP | 8.13.2 | set `buildToolsVersion = "36.0.0"` explicitly |
| Kotlin + compose-compiler plugin | 2.2.21 | |
| KSP | 2.2.21-2.0.5 | |
| Hilt / Dagger | 2.58 | 2.59+ requires AGP 9 |
| androidx.hilt (hilt-work, hilt-compiler) | 1.3.0 | |
| Compose BOM | 2026.06.01 | ui 1.11.4, material3 1.4.0 |
| activity-compose / core-ktx | 1.12.4 / 1.18.0 | |
| lifecycle (runtime-compose, viewmodel-compose) | 2.10.0 | |
| navigation-compose | 2.9.8 | |
| Room (+ plugin, testing) | 2.7.2 | KSP |
| WorkManager (+ testing) | 2.11.2 | plain `OneTimeWorkRequest` |
| DataStore | 1.2.1 | |
| kotlinx-serialization / coroutines | 1.9.0 / 1.10.2 | |
| OkHttp + mockwebserver | 4.12.0 | |
| Jsoup | 1.23.2 | |
| Timber | 5.0.1 | |
| JUnit Jupiter / JUnit4 | 5.14.4 / 4.13.2 | Jupiter for `:core` only; Android modules use JUnit4 |
| androidx.test core / ext-junit | 1.7.0 / 1.3.0 | |
| kotest-property | 6.0.7 | |
| Robolectric | 4.16.1 | `sdk=36` (plus 34 for worker tests); JDK 21 |
| Roborazzi (core, compose, junit-rule, plugin) | 1.76.0 | record only, never a gate |
| ktlint-gradle | 14.2.0 (fallback 13.1.0) | |
| detekt | 1.23.8 | force `org.jetbrains.kotlin:*` to 2.0.21 on the `detekt` configuration |
| ciq-companion-app-sdk | 2.4.0 | Maven Central, Run 2 |
| Android SDK | `platforms;android-36`, `build-tools;36.0.0`, `platform-tools` | cmdline-tools `commandlinetools-linux-16111833_latest.zip` |
| Connect IQ SDK | 9.2.0 | Run 2; confirm it supports `fr245` (Step 5.0) |

- **App:** Kotlin, Compose + Material 3. compileSdk/targetSdk 36, **minSdk 34**. Edge-to-edge, predictive back. JDK 21 toolchain with `jvmTarget` 17.
- **Modules:**
  - `:core`: a pure JVM module (`kotlin("jvm")`) holding model, catalog, parser, merge, packs, stock and the sync codec as packages.
  - `:data`: Room plus the repository.
  - `:extract`: safe fetch plus extractors.
  - `:app`.
  - `:claude`: Run 3.
  - `:media`: v1.1.
- **Secrets:** the Claude key is encrypted with an AES-GCM key held in the Android Keystore (no user-auth binding). The encrypted value lives in a DataStore file excluded from backup.
- **Not in v1 dependencies:** ML Kit, Photo Picker, `SpeechRecognizer`, CameraX/FileProvider (all v1.1).
- **`gradle.properties`:** `org.gradle.jvmargs=-Xmx4g`, `kotlin.daemon.jvmargs=-Xmx2g`, `org.gradle.workers.max=3`. In the build scripts: test `maxHeapSize=2g`, `maxParallelForks=1` for `:app`.

## 3. Core features

### 3.1 Import
- **Entry points (v1):**
  - **`ACTION_SEND` text.** URLs are extracted with `Patterns.WEB_URL`. The first https URL is used, with `utm_*` and `igsh` stripped. `EXTRA_SUBJECT` is the fallback title.
  - **"Paste link" chip.** It appears only when `ClipDescription` says the clipboard holds text, and the clipboard is read only when you tap it.
  - **"Add item" field** (§3.5).
  - `singleTop` + `onNewIntent`. State survives process death via SavedStateHandle and an `ImportJob` row.
- **Safe fetch (`:extract`):**
  - **URLs:** `https` only, re-checked after every redirect, at most 5 redirects.
  - **Addresses:** a custom OkHttp `Dns` refuses loopback, RFC1918, link-local, CGNAT, ULA and multicast addresses, as well as literal-IP hosts and non-443 ports. `file:`, `content:` and `intent:` URLs never reach the fetcher.
  - **Requests:** no cookies, a real browser user agent, 10 s to connect and 30 s for the whole call.
  - **Responses:** only `text/html` or `application/ld+json` is accepted, capped at **3 MB after decompression**.
- **Parser limits:**
  - Each JSON-LD script is capped at 512 KB, with nesting depth 64 or less (checked before decoding).
  - At most 10 Recipe objects, 300 lines per recipe and 500 characters per line.
  - The tokeniser scans linearly, with no regex backtracking.
  - An `OutOfMemoryError` or `StackOverflowError` sends you to "Select lines manually".
- **Extraction order:** WPRM/Tasty → JSON-LD `Recipe` → microdata → heading heuristic → "Select lines manually".
- **JSON-LD quirks, each with a test:** `@graph`, `@type` given as an array, several recipes on one page, `recipeIngredient` as a single string or under `ingredients`, nested arrays, HTML entities (including double-encoded), HTML tags inside strings, lenient JSON, and the different forms `recipeYield` takes.
- **Blocked or JavaScript-only pages** go to "Select lines manually". A visible WebView sheet for these comes in v1.1 (Step 10.3) and uses the same address filter.
- **Same URL imported again:** "Already on your list — add again?"
- **Background imports:** an import left running in the background shows a "1 recipe ready to review" card on the list. There are no notifications in v1.

#### Review before adding
Nothing is added until you confirm on this screen. It is used for every import (and in v1.1, for "Shop for this week").
- **Default view (minimal):**
  - **Header:** title, a servings stepper, and an **"Add N items"** button.
  - **Rows:** grouped by aisle. Each row has three elements only: the name, one quantity (the pack if packs are on), and an **"In stock"** checkbox.
  - **In-stock rows** stay in place, greyed out with a strikethrough.
- **Pre-ticked as in stock:**
  - **Staples** (the `Staple` table, seeded with salt, pepper, oil and water), plus optional and garnish lines.
  - **Learned items:** stock learning ticks these and labels them "usually in stock".
  - **Banner:** whenever learning has pre-ticked something, a banner above Add says "5 assumed in stock: Butter, Eggs… [Review]". It is an accessibility live region.
- **Row detail sheet** (tap a row):
  - Merge preview, e.g. "Milk: +200 ml → 700 ml on list".
  - The pack plan, and the source line(s), with live re-parse while you edit.
  - "A or B" swap, **"I have some"** (enter an amount), **"Always in stock"** (adds to `Staple`), move aisle.
- **Overflow menu:** mark this aisle in stock, clear in-stock.
- **Applying "I have some":** the amount is subtracted from the merged row when contributions are created, starting with the earliest line. It is **never stored as a separate "have"**, so it can't be counted twice or outlive the review.
- **Stock learning** (`StockStat`):
  - **What counts:** only reviews you **confirm**, at most once per item. Only **explicit taps** count. Rows pre-ticked by staples, flags or learning don't count unless you tap them.
  - **When it pre-ticks:** after 3 or more explicit in-stock taps in the last 4 confirmed reviews where the item appeared.
  - **Suppression:** two untick overrides in a row suppress it. Suppression expires after 5 reviews, or after 3 manual taps.
  - **Decay:** an item that hasn't appeared for 60 days is reset.
  - **Reset:** "Forget stock habits", or reset a single item.
- **Confirming:**
  - The app re-checks, in the same transaction, the `rev` of every item shown. If any changed, it shows "List changed — review updated" and recomputes.
  - Otherwise it inserts the contributions and decisions, then shows a **"Added 14 items · Undo"** snackbar for 10 s.
  - **Undo** deletes that `RecipeUse` (cascade), rolls back that review's `StockStat` updates, and bumps the affected items' revs.
- **Speed goal:** for a 12-line recipe, share → glance → Add should take under 5 s.

### 3.2 Ingredient parser
Example: `"2 ½ cups (300g) plain flour, sifted"` → `{qty 5/2, unit CUP(locale), alt 300 g, name "plain flour", note "sifted"}`.
- **Prep words vs identity words.** Prep words (chopped, sifted…) go to `note`. Identity words (ground, smoked, red, dried, tinned, frozen, powder, unsalted…) stay in the name.
- **Units (`QtyUnit` enum):**
  - **Mass:** g, kg, oz, lb.
  - **Volume:** ml, l, tsp, tbsp, cup, fl oz, pint, quart.
  - **Item-specific:** clove, bunch, tin/can, packet, sprig, slice, head, stalk, ear, fillet, piece, stick.
  - **Small amounts:** pinch, dash.
  - **Locale factors:** cup is 240 ml (US) or 250 ml (metric); pint is 568 ml (UK) or 473 ml (US).
  - **Case:** `T` means tbsp and `t` means tsp.
  - **Liquids:** "oz" next to a liquid lowers confidence, because it may mean fl oz.
- **Splitting lines:**
  - "X and Y" is split only when both halves are in the catalogue.
  - "A or B" becomes A, with B kept as an alternative.
  - "juice/zest of N lemons" becomes N lemons.
- **Flags:** `optional`, `garnish`, `noScale` ("to taste", "for frying", or no quantity).
- **Section headers:** lines ending in ":", lines starting "For the…", and WPRM group names become labels.
- **Singularising:** only the head noun, and only when the singular form is in the catalogue.
- **Synonyms:** each maps to one catalogue ID, which may depend on locale. "pepper" is resolved from context.
- **Confidence:** starts at 1.0. Deduct 0.4 for unparsed tokens, 0.3 for an unknown unit, 0.2 for a name not in the catalogue, and 0.2 for an ambiguous split. Lines that are meant to have no quantity score 1.0. `CONFIDENCE_THRESHOLD` defaults to 0.6.
- **Corrections map (v1.1):** a personal map of corrections you've made.

### 3.3 Quantities, merging and ticking (simplified in rev 4)
- **Item identity:**
  - Each item has a unique `mergeKey`: `cat:<catalogId>` or `name:<normName>`.
  - Head-noun matching only picks the aisle, never the merge.
  - **When the catalogue version changes**, `name:` items that now resolve to a `cat:` key are re-keyed. If two items collide:
    - The lower id survives.
    - Contributions move across.
    - The survivor's aisle and position win.
    - USER-sourced overrides beat SEED ones.
    - `StockStat` keeps the more recent entry.
    - The new rev is the higher of the two plus 1.
    - `ItemAlias(oldId → newId)` redirects watch ops.
- **What's on the list:** each item is the sum of its **contributions**. Every contribution belongs to a `RecipeUse` (a recipe import, a planned meal, or a manual add) and a recipe line. It stores `qtyBase`, worked out per dimension, already scaled, with "I have some" already subtracted.
  - **Quantity-less lines** ("salt to taste") are stored as a *presence* contribution (no amount).
  - **Mixing volume and mass:** these combine only for catalogue rows that have `gramsPerCup`. Otherwise the item shows a compound quantity, e.g. "1 onion + 200 g".
- **Ticking an item:**
  - **Tick = covered.** Ticking marks every contribution the item has *at that moment* as covered (`coveredBy = tickId`). The `Tick(id, itemId, at, source PHONE|WATCH, installId?, seq?)` row records it.
  - **The item is DONE** when every contribution is covered. Otherwise it shows the uncovered total only: "1 more", with a sub-line "2 already ticked".
  - **Contributions added after a tick** (new recipes) start uncovered, so a DONE item comes back showing just the new amount.
  - **Untick** uncovers exactly the contributions that its most recent `Tick` covered, then deletes that `Tick`.
  - **Removing or rescaling a recipe use** deletes or rescales its contributions, whether they're covered or not. **Ticks never create stock.** Leftovers and packs never silently cover a later recipe.
  - **Rounding and packs only affect what's displayed**, so a tick always fully covers. Nothing can be left with "⅕ onion more".
- **"Finish shopping"** (replaces "Clear ticked") deletes DONE items and any `RecipeUse` left with no contributions.
  - **Deleting items** is a soft delete (`deletedAt`), so it can be undone.
  - **Old uses:** uses whose contributions are all covered for 7 days are archived automatically.
- **Manual changes:**
  - A manual add, or editing an item's quantity, adds or changes a contribution in a `kind=MANUAL` `RecipeUse`. Negative amounts are allowed, but the item's total is clamped at 0.
  - There are no overrides.
- **Packs** (display only):
  - **Where sizes come from:** each catalogue row lists sizes per dimension, e.g. `500g|1kg` or `6ct|12ct`. You can change them with `PackOverride`.
  - **Choosing packs** (for the uncovered need in that dimension):
    - Among combinations of up to 4 packs, pick the least leftover.
    - Leftovers up to max(smallest pack, 15% of the need) count as equal. Among those, pick the fewest packs, then the larger single pack.
    - With no fit, or a compound or presence quantity, show the rounded amount instead.
  - **Display:** main line "1 × 500 g", sub-line "for 350 g".
  - **Trust:** pack sizes the agent estimated are shown in a muted style until you tap "Looks right" or edit them.
- **Rounding:** counts round up (produce to the nearest ½). Mass and volume use friendly steps. Fractions show as ½ ⅓ ¼. Display can be set to metric, imperial or as written.
- **Revisions:**
  - **What bumps `Item.rev`:** any change to its contributions or ticks, its aisle, or the inputs to its display (pack override, pack toggle, display units, a catalogue update). The bump happens in the same transaction.
  - `listRev` (stored in Room `Meta`) goes up with every list change.
  - Each contribution stores `createdListRev`.
- **Dry run:** `Merger.preview(list, incoming, decisions)` is pure. It returns the rows to insert plus the revs of the items it shows.

### 3.4 Aisles and categorisation
- **Aisles:** a table with stable codes (an `AisleCode` enum gives the seed codes and default order). You can rename, hide or add aisles.
  - **Default aisles:** Produce, Bakery, Meat & Fish, Dairy & Eggs, Chilled, Frozen, Tins & Jars, Pasta/Rice/Grains, World Foods, Baking, Nuts & Dried Fruit, Oils & Vinegars, Spices & Condiments, Free-from, Drinks, Household, Other.
- **Store:** `RoomDatabase.Callback.onCreate` seeds `StoreProfile(1, "My store")` and its `AisleOrder`. The list always reads `AisleOrder`. The v1 UI has one store.
- **Lookup order:** override → identity words → catalogue ID → head noun → keyword rules → Other.
- **Moving an item to another aisle** asks "Always put X in Y?". Yes writes a `CategoryOverride`; "Just this time" doesn't.
- **Catalogue:** about 400 rows.
  - **Names:** taken by script from the most frequent normalised names in the **NYT ingredient-phrase-tagger snapshot** (Apache-2.0; LICENSE and NOTICE copied to `catalog/`).
  - **Agent-written columns:** UK names, aisle, staple and pack sizes (about 40 items), tagged `src=agent-estimate`.
  - **Review:** `catalog/REVIEW.md` is generated for a 10-minute check.
- **Learned aisle order (v1.1, Step 9.1):**
  - **What's logged:** `TickEvent` rows, ordered by seq within a trip.
  - **What counts as a trip:** one device, gaps under 20 min, at least 5 ticks, at least 8 min long.
  - **Filters:** drop trips where 80% of ticks are within 5 s of each other, ticks from bulk actions, and ticks made straight after a next-aisle prompt.
  - **Learning:** pairwise precedence (≥70%) combined with a Borda count, using only trips whose order differs from the displayed order.
  - **Applying it:** after 2 qualifying trips the app *suggests* a reorder. Pinned aisles never move.
  - **Shortcut:** a "Sort like my last trip" option.
  - **Retention:** `TickEvent` is kept 90 days and excluded from backup and export.

### 3.5 List UI
- **"Add item" field**, always at the top:
  - Autocompletes from recent items and the catalogue.
  - **Enter** adds the item and keeps the keyboard open. Pasting several lines adds one item per line.
  - **No review screen.** If the item is already on the list, it shows "Milk — already on list · +1?".
- **Empty state:** "Share a recipe from Chrome" (two-step picture), plus the add field and "Try a sample list".
- **Rows:**
  - Grouped by aisle in store order.
  - The main line is what to put in the basket; the sub-line is context.
  - Partly ticked items show a half-filled tick icon. DONE items sink to the bottom of their aisle, dimmed and marked with a tick (not colour alone).
- **Actions:**
  - Tap to tick or untick, with undo.
  - Swipe to delete, with an accessibility action as an alternative.
  - "Finish shopping" and "Clear all", both with undo.
  - "Share list as text".
- **Item detail sheet:**
  - Totals per dimension, and the recipes it came from.
  - The pack plan, with "Looks right" and edit.
  - Change aisle, and edit quantity (which creates a manual contribution).
- **Recipes tab:**
  - Lists the uses currently on the list.
  - Each use has "Remove from list", which also rolls back stock learning if nothing from it has been ticked yet.
  - Each use also has "Add the in-stock items now", which reopens the review with full amounts and nothing pre-ticked.
- **Export and import:**
  - JSON through the Storage Access Framework, with a `formatVersion`.
  - Import rules:
    - The file is capped at 10 MB.
    - Decoding is strict, with limits on strings and counts. Rationals must fit in 64 bits.
    - Foreign keys are checked, and everything is written in one transaction.
    - You see a preview, "Replace current list (23 items) with file (41)?", and the app makes an automatic export before replacing.
    - Import **always assigns new ids and a new `listEpoch`**.
  - An optional weekly auto-export goes to a folder you choose.
- **Settings**, grouped:
  - **List:** staples, stock habits, packs, units.
  - **Aisles.**
  - **Watch.**
  - **Smart parsing:** Claude, collapsed by default.
  - **Advanced:** model, spend, debug log.
- **Accessibility, required from Step 4, not left to polish:**
  - Every row is a single TalkBack node with `customActions` (I have some, always in stock, move aisle, delete).
  - Touch targets are at least 48 dp. Contrast is at least 4.5:1, with a dark-theme snapshot.
  - Layout works at 200% font scale.
  - Never rely on red or green alone.

### 3.6 Garmin Forerunner 245 app and sync
- **The device:**
  - **Controls:** 5 buttons and **no touchscreen**: Start/Stop = select, Back, Up, Down, Light. Every interaction must work with buttons alone.
  - **Screen:** 240×240 round, with the MIP display.
  - **Checked from `fr245/compiler.json` in Step 5.0:** API level, app memory limit, glance support, and `Attention` vibrate.
  - **Sizing:** `minApiLevel` is set to the device's level. The snapshot size cap, the menu size and the outbox cap are taken from the measured memory limit.
- **Watch UI:**
  - **Aisle list:** shows `leftCount` from the phone, plus "+N on phone" only when N > 0.
  - **Inside an aisle:** a `CheckboxMenu`.
    - Select ticks an item, and selecting it again unticks it.
    - A second press on the same row within 700 ms is ignored.
    - Ticked rows sink, so the next item is always at the top.
  - **When an aisle is done:** a prompt "Produce done ✓ — [Next: Bakery ▸] [Undo <item>]", with **Next as the default focus**. It moves on using `switchToView`. Auto-advance is optional and off by default.
  - **Vibration:** only `if (Attention has :vibrate)`, and it can be turned off.
  - **Rows:** the label is the name; the sub-label (18 characters or fewer) is the quantity or pack.
  - **Other screens:** "Last synced", "N unsynced", an empty state, and "Update watch app".
  - **Glance:** "14 left · Produce next", only if the 245 supports glances, otherwise skipped.
- **Envelope:** `{v, t, epoch, installId, ...}` as native Monkey C objects, not JSON.
  - `hello` carries `minV`/`maxV`, `listRev`, `watchNow` and `ackedThrough`.
  - **Versioning:** unknown keys are ignored. `v` only changes for breaking changes, and both sides use the highest version they both support.
- **Ops (watch → phone):**
  - **Format:** compact arrays `[seq, itemId, done, seenListRev, ts]`.
  - **Sequence numbers:** `nextSeq` is stored in the same Storage key as the outbox. It is written **before** sending and never reset. A seq is assigned when an op is **first sent**; collapsing unsent ops keeps the earliest seq, so there are never gaps.
  - **Sending:** batches of up to 32 ops, one batch in flight at a time, with retry and back-off (5 s / 15 s / 60 s) while the app is open. The watch also tries to flush in `onStop`.
  - **Outbox full:** at the measured cap, ticking is blocked with "Sync with phone".
- **Phone applies an op** in **one Room transaction**, covering the op's effect, `WatchInstall.lastSeq` and the stored result. It goes through these checks in order:
  1. **Wrong epoch** → result `epoch`. The watch drops those ops and shows "N watch ticks discarded (list replaced)".
  2. **Out of sequence:** only `seq == lastSeq + 1` is applied. Others are ignored, and the watch resends from `ackedThrough + 1` (go-back-N).
  3. **Item id** is resolved through `ItemAlias`. Unknown or deleted → `rejected`, and the watch shows "Changed on phone".
  4. **`done=true`** covers the uncovered contributions with `createdListRev ≤ seenListRev`:
     - nothing to cover → `noop`
     - some contributions created later stay uncovered → `partial`, and the watch shows "1 more"
  5. **`done=false`** undoes the most recent `Tick` made by **this install** for that item. If there's none, the result is `noop`. If the item stays DONE because of another tick, the result is `still_done`.
  6. **Old ops:** an op with `ts` more than 6 hours old (after clock-offset correction) is queued for confirmation on the phone ("Apply 3 ticks from yesterday?").
- **Snapshots (phone → watch):**
  - **Format:** `{epoch, listRev, ackedThrough, ackExceptions:[[seq,result]], items:[[id, name, sub, aisleIdx, done, rev]], aisles:[[code, name, leftCount, hiddenCount]], more}`.
  - **When the phone replies:** it *always* answers `hello` and every op batch, even when nothing changed. An ack-only reply is fine.
  - **Ack bookkeeping:** the phone keeps the last 64 results for each install, so duplicates are re-acked correctly.
  - **Truncation:** ack data is reserved first. Then, in this order:
    1. Drop DONE items, except those ticked in the last 30 minutes or in the aisle you're in.
    2. Shorten names to 16 characters.
    3. Drop unticked items from the end of the store order.
  - **Watch handling:**
    - The watch ignores item state from snapshots with a lower `listRev` (same epoch), but always processes acks.
    - Pending ops are re-applied on top optimistically.
- **Epoch:**
  - `listEpoch` is stored in Room `Meta`, next to a marker file in `noBackupFilesDir`. If the marker is missing or doesn't match on startup (after a restore), a new epoch is generated.
  - A JSON import also creates a new epoch.
  - The watch clears its cache when the epoch changes, but keeps `nextSeq`.
- **Watch storage:** chunks of 32 KB or less. Keys: `meta` (epoch, installId, listRev), `outbox` + `nextSeq`, and `glanceSummary`. All access is wrapped in try/catch.
- **Android side:**
  - The receiver is registered in `Application.onCreate`, with app-scoped `WatchSync` and no permanent service.
  - Add `<queries>` for Garmin Connect, handle init-error prompts, and use `openStore` only if needed.
  - **Treat watch input as untrusted:** check types and ranges, cap message size and op rate, and never let watch data reach SQL strings or file paths.

### 3.7 Claude API integration (optional, opt-in)
- **Settings:**
  - Mode: Off, Fallback or Always.
  - The key field uses `textPassword`, no autofill save, and `FLAG_SECURE`. "Test key" calls `GET /v1/models`, which costs nothing.
  - Model: Opus 5.5 by default, or Sonnet 5.5 or Haiku 5.5.
  - Limits: a monthly spend cap, plus a daily call cap.
  - A cost counter, which includes cache tokens.
- **When Claude is called:**
  - **Only from the review screen while it's in the foreground.** A share intent alone never spends money.
  - In Fallback mode, only lines below `CONFIDENCE_THRESHOLD` are sent, or the whole page if extraction failed.
- **Request:**
  - `output_config.effort:"low"` is set explicitly, `thinking` is never disabled, and `max_tokens` is 8000.
  - **Refusal fallback:** for Opus 5.5 and Sonnet 5.5 only, send `fallbacks:"default"` with the `anthropic-beta: server-side-fallback-2026-07-01` header.
- **Strict schema** (`output_config.format`):
  - `additionalProperties:false` everywhere, all fields required, and optional ones nullable through `anyOf`.
  - **Top level:** `status` (`ok | no_ingredients | not_a_recipe`), `title`, `servings`, `ingredients[]` and `source_urls[]`.
  - **Each ingredient:** `raw`, `name`, `quantity`, `quantity_max`, `quantity_text`, a `unit` enum (plus `other`), `alt_quantity`, `alt_unit`, `note`, `section`, `optional`, `estimated`, and an `aisle_suggestion` enum.
  - Number ranges aren't in the schema; they're checked in Kotlin.
- **Response handling:**
  - Check `stop_reason` first. On `refusal`, show "Couldn't parse — edit manually"; `max_tokens` is an error.
  - Then validate against the schema.
  - Errors: 401/403 means a bad key, 429/529 back off, and a 5xx is retried once.
- **Prompt injection:** page text and captions go inside `<untrusted_content>`, with no tools. A URL Claude returns is accepted only if it appears verbatim in the source, is https, and you tap it. Input length is capped.
- **Key failures:**
  - Missing after a restore → "Re-enter key".
  - `KeyPermanentlyInvalidatedException`, `AEADBadTagException` or `ProviderException` → wipe the key, set Claude to Off, and show a one-time notice.
  - "Remove key" clears it from memory.
- **Offline:** use the rule-parser result, marked "Claude pending", with a manual "Retry with Claude".
- **Logging:**
  - Release builds use an in-memory ring buffer of 500 lines. Request and response bodies never go to Logcat.
  - URLs are logged without their query strings, and `sk-ant-…` and `x-api-key` values are redacted.
  - `verify.sh` scans for `sk-ant-` strings.
- **Cost on Opus 5.5:** about $0.04 per text import, or about $0.05 for a reel with OCR plus 3 frames. Sonnet 5.5 costs half that, and Haiku 5.5 about $0.001–0.003. Media imports show an estimate first.

### 3.8 Instagram import (v1.1)
Sharing from Instagram sends only the URL.
1. **Caption:** try Meta oEmbed (`graph.facebook.com/v25.0/instagram_oembed`).
   - A reviewer reported it needs no token since 2026-06. **Check this in 8.1.**
   - Take the caption from the returned `html`, then try one plain `og:` fetch.
   - One call per share, and no WebView on instagram.com.
2. **Routing:**
   - The caption has a recipe link → web import.
   - The caption looks like an ingredient list → parser, then Claude if confidence is low.
   - Otherwise → "Add media".
3. **"Add media" options:** a how-to (Download, or screen-record with audio, or screenshot the caption), plus:
   - **Screenshots:** OCR (§3.9 post-processing), then the parser.
   - **Video:** OCR at about 1 fps, keeping frames whose text changed. Add 2–3 frames at 1092 px when confidence is low.
   - **Transcript experiment (ADR 004):** decode the audio to PCM, then use the on-device recogniser with `EXTRA_AUDIO_SOURCE` (API 33+, which your phone has), if it's available.
   - **Paste caption.**
4. Everything then goes to the review screen. Anything taken from video or speech is marked "estimated".

### 3.9 Cookbook photo import (v1.1)
- **Entry points:**
  - `TakePicture` with a `FileProvider` cache file, which is deleted after use. `verify.sh` checks that the merged manifest contains no `CAMERA` permission.
  - The Photo Picker, or an image share.
- **No crop step:** OCR reads the whole page, then you get a tappable list of lines with the ingredient block preselected. This reuses the "Select lines manually" screen.
- **OCR post-processing:**
  - **Columns:** found by x-range overlap, sorted top to bottom within each column, then read left to right.
  - **Wrapped lines:** joined using the indent and a line gap under 1.3× the line height.
  - **Fraction fixes:**
    - `%`, `Y2`, `V2` and `Vz` next to a number become ½.
    - Inside numbers, `O` becomes 0 and `l` becomes 1.
    - An implausible "N1/2" becomes "N ½" with lower confidence.
  - OCR quantities are flagged "check". Low-confidence lines go to Claude with the image.

### 3.10 Meal planner (v1.1)
- **Library:** every imported recipe is saved, with search and favourites.
  - **Deleting a recipe that's in use:** the app confirms first, then turns its contributions into manual ones. `PlannedMeal` uses FK RESTRICT.
- **Week view:**
  - Slots: Breakfast, Lunch, Dinner and Other.
  - Each meal has its own servings.
  - "Plan for…" on the review screen.
- **"Shop for this week":**
  - One combined review screen, with rows like "Onion — 3 · Mon, Wed".
  - Confirming creates one `RecipeUse` per meal.
  - "I have some" is subtracted across those meals, earliest first.
- **Rules:**
  - Removing a meal removes only its use.
  - A recipe planned twice counts twice.
  - Moving a meal to another day changes nothing.
  - "On list" and "shopped" are derived from the meal's use, not stored.

## 4. Data model (Room, `:data`)

**MVP schema (v1):**
- `Meta(id=1, listEpoch, listRev, catalogVersion, epochMarker)`
- `Item(id AUTOINCREMENT, mergeKey UNIQUE, catalogId?, normName, displayName, aisleCode, position, rev, deletedAt?, updatedAt)`
- `ItemAlias(oldId PK, newId)`
- `RecipeUse(id, recipeId FK RESTRICT nullable, kind: RECIPE|MANUAL, servings, addedAt, archivedAt?)`, with CHECK (kind='MANUAL' OR recipeId NOT NULL)
- `Contribution(id, useId FK CASCADE, recipeLineId FK nullable, itemId FK CASCADE, qtyBase?, noScale, createdListRev, coveredBy FK→Tick SET NULL)`
- `Tick(id, itemId FK CASCADE, at, source: PHONE|WATCH, installId?, seq?)`
- `LineDecision(useId FK CASCADE, recipeLineId FK CASCADE, status: ADDED|IN_STOCK|PARTIAL|EXCLUDED, PK(useId, recipeLineId))`
- `Recipe(id, url, title, servingsOriginal, unitLocale, importedAt, source: WEB|MANUAL, parsedBy: RULES|CLAUDE, claudePending)`
- `RecipeLine(id, recipeId FK CASCADE, section, raw, parsedJson)`. Lines are immutable; importing again creates a new `Recipe`.
- `ImportJob(id, state, inputRefs, resultJson, error, createdAt)`
- `Aisle(code PK, name, hidden, defaultOrder)`, `StoreProfile(id, name)`, `AisleOrder(storeId, aisleCode, position)`
- `CategoryOverride(key PK, aisleCode, source)`, `Staple(key PK, source: SEED|USER)`
- `StockStat(key PK, historyBits, lastSeenAt, suppressedUntilReview?)`, `StockStatDelta(useId, key, before)` (lets Undo roll back stock learning)
- `PackOverride(key PK, packSizes, confirmed)`. Throughout, `key` means the item's `mergeKey`.
- `DeletedItem(id, deletedAt)` (7 days)
- `WatchInstall(installId PK, lastSeq, clockOffsetMs)`, `WatchOpResult(installId, seq, result, PK(installId, seq))` (the last 64 per install)

**Added later:**
- **Run 3:** `ClaudeUsage(date, model, inputTokens, outputTokens, cacheWrite, cacheRead)` (schema v2).
- **v1.1:**
  - `Recipe.source` gains `INSTAGRAM` and `PHOTO`, and `Recipe` gains `favourite`.
  - `PlannedMeal(id, date, slot, recipeId FK RESTRICT, servings, useId?)`, `TickEvent`, `AisleOrder.pinned`, `UserCorrection`.

**Schema exports and migrations:** schemas are exported to `android/data/schemas/`. `MigrationTestHelper` tests start from v2.

## 5. Repo layout and constants

```
android/      :app, :core, :data, :extract, :claude (Run 3), :media (v1.1)
garmin/       Connect IQ project (manifest, source/, resources/, test/), README (fr245 facts)
catalog/      ingredients.csv, synonyms.csv, SOURCES.md, REVIEW.md (generated), LICENSE-NYT, NOTICE-NYT
testdata/     ingredients/ (golden: frozen.json, CHECKSUM, CHANGES.md, info set, synthetic-uk.json),
              pages/ (trimmed fragments + SOURCES.md, synthetic/, hostile/), sync/ (protocol vectors),
              claude/ (synthetic-*, live-*), ocr/, video/ (v1.1)
tools/        verify.sh, pick-golden (seeded), nyt-to-golden, contact-sheet, eval-claude/, fetch-live-check/
docs/         decisions/ (ADRs), HANDOFF.md, KNOWN_ISSUES.md, parser-spec.md, screenshots/ (contact sheet only)
.github/workflows/, .claude/ (settings.json + hooks/session-start.sh)
PROGRESS.md
```

| Constant | Value |
|---|---|
| App ID | `com.robert4lyf.hypershop` |
| Watch app UUID | generated in 0.3 and stored in `gradle.properties` and `garmin/manifest.xml` |
| versionCode | `git rev-list --count HEAD` (via `providers.exec`; CI uses `fetch-depth: 0`) |
| versionName | `git describe --tags --always`, falling back to `0.0.0-dev` |
| Signing | the committed debug keystore only (personal use) |
| Developer key | generated locally with openssl, git-ignored, CI secret optional |

## 6. Build plan

### Three runs
| Run | Delivers | Needs from you first |
|---|---|---|
| **Run 1** | Steps 0–4: phone app, parser, merge, review, list, sync codec | nothing |
| **Run 2** | Steps 5–6: Forerunner 245 app and sync | Hand-off A done, plus the **`fr245` device folder** from your PC's SDK Manager (`~/.Garmin/ConnectIQ/Devices/fr245`), uploaded as a private release asset or via a URL in the environment, never committed |
| **Run 3** | Step 7: Claude for text | Hand-off B done, plus `ANTHROPIC_API_KEY` set in the environment (a dedicated key with a spend limit) |
| Later | Steps 8–10 (v1.1) | per step |

### Rules for the agent
1. **On start:** read `PROGRESS.md` and resume at the first unchecked item **of this run**. Never redo checked items, and never start the next run.
2. **Each sub-step:** implement it, check it, then commit with the subject `[<step id>] …`, updating `PROGRESS.md` in the same commit (`[x]`, `[~] stubbed` or `[!] blocked`, plus a note and the check command). Find SHAs with `git log --grep`.
3. **Gates run locally:** `tools/verify.sh` must pass in the container. CI is best-effort.
4. **Branches:** work only on the session branch. Never create branches and never merge. At the end of each run, open a PR if `main` exists; otherwise note the PR in `HANDOFF.md`.
5. **Versions are frozen** (§2). Problems go in `KNOWN_ISSUES.md`.
6. **Golden data:**
   - Never edit it to make a test pass.
   - A fix gets its own commit with the subject `golden-fix: …`, touches nothing under `android/`, updates `CHECKSUM`, and adds an entry to `CHANGES.md`.
   - At most 3 fixes per run, all listed in the hand-off.
7. **Running low on context:** commit, update `PROGRESS.md` and `HANDOFF.md`, and stop cleanly.
8. **Stop** at the end of the run. No gold-plating.

### Things only you can provide
| Item | When |
|---|---|
| Create `main` and merge each run's PR (do this first at each hand-off) | after each run |
| Install the APK (from the PR or CI artifact) | Hand-offs A, B, C |
| Check the contact sheet, `catalog/REVIEW.md` and the 20 held-out golden lines | Hand-off A |
| The `fr245` device folder | before Run 2 |
| Sideload the `.prg` to the Forerunner 245 (USB → `GARMIN/APPS`) | Hand-off B |
| Optional: run the simulator and the Monkey C unit tests on your PC | Run 2 |
| Anthropic API key | before Run 3 |
| 10 reels; 5 cookbook pages | Steps 8, 9.2 |

---

## Run 1

### Step 0 — Toolchain and scaffolding
- **0.1** Add `.gitignore`, `README.md`, `PROGRESS.md` (Run 1 items only, about 50 lines), `docs/HANDOFF.md` and `docs/KNOWN_ISSUES.md`.
- **0.2** Android SDK:
  - Download `cmdline-tools` and run `yes | sdkmanager --licenses`.
  - Install the §2 packages to `$HOME/android-sdk`.
  - Write a git-ignored `local.properties`.
  - **SessionStart hook** in `.claude/settings.json`, `matcher: "startup"`, running `.claude/hooks/session-start.sh`. The script:
    - runs only when `CLAUDE_CODE_REMOTE` is set;
    - is idempotent, and survives network failure;
    - exports `ANDROID_HOME` via `$CLAUDE_ENV_FILE`;
    - does **not** download the Connect IQ SDK.
  - **Check:** `java -version`, `echo $ANDROID_HOME`, and `sdkmanager --list_installed`.
- **0.3** Gradle project and modules (§2), `gradle.properties`, the debug keystore, and the watch UUID.
- **0.4** Version catalog (§2), plus one hello test for each of: JUnit5, kotest-property, a Robolectric `sdk=36` Compose test with a semantics assertion, a Roborazzi recording (`@GraphicsMode(NATIVE)`, `roborazzi.test.record=true`), and Hilt.
- **0.5** ktlint plugin, and detekt with the Kotlin force.
- **0.6** `tools/verify.sh`:
  - lint, then all tests;
  - `assembleDebug`, then lint the merged manifest: no `CAMERA`, and only the launcher and share activity exported;
  - the `sk-ant-` scan;
  - the golden `CHECKSUM` check.

  Then the CI workflow: tests, plus the APK artifact. If pushing the workflow is refused, mark it `[!]`.
- **Check:** a cold `tools/verify.sh` passes.

### Step 1 — Spec, enums and golden data (before any parser code)
- **1.1** `docs/parser-spec.md`:
  - Numbered rules from §3.2, plus the golden JSON schema. `name` is a normalised string; there are no catalogue IDs.
  - The `QtyUnit` list (§3.2).
  - The `AisleCode` enum (codes and default order).
  - A mapping from NYT labels to the spec: NYT keeps identity words in its `comment` field, treats "salt and pepper" as one item, uses qty 0 for "no quantity", and has noisy unit plurals.
- **1.2** Golden data:
  - **Frozen set:** `tools/pick-golden` with a committed seed selects **80** NYT lines, stratified as 25 simple, 20 with a comment or parentheses, 15 fractions or ranges, and 20 with no quantity or "to taste".
    - `tools/nyt-to-golden` converts the NYT labels by script.
    - Every manual change cites a spec rule number.
    - **20 of the 80 are held out** for you to check at Hand-off A.
  - **UK set:** add 25 hand-written UK and metric lines (`synthetic-uk.json`), covering T/t, pints, "plain flour", tins and "x 400g".
  - **Info set:** about 300 NYT lines.
  - **Freeze:** commit all of this on its own, with `CHECKSUM` and `CHANGES.md`.
- **1.3** Model in `:core`:
  - `Rational`, `Dimension`, `QtyUnit` with locale factors, and `QtyBase` (per dimension, plus presence).
  - The `AisleCode` enum.
  - `ParsedIngredient` and `RecipeImport`.
  - The covered/DONE rules (ADR 002).
- **1.4a** `tools/` script that builds the NYT top-400 normalised names. It merges plural duplicates and "kosher salt"→"salt", and splits compound names.
- **1.4b** Catalogue CSV with columns `id, name, plural, synonyms, aisleCode, staple, shoppingUnit, displayPolicy, gramsPerCup, packSizes, src`. Agent-written columns are tagged.
- **1.4c** A Gradle validator, a generated resource file plus a lazy loader, `SOURCES.md`, the NYT LICENSE/NOTICE files, and the `REVIEW.md` generator.
- **1.5a** Sync codec:
  - The envelope, ops and snapshot formats (§3.6).
  - A size estimator.
  - A snapshot builder with ack reservation and the truncation order.
- **1.5b** Pure protocol rules:
  - **Phone side:** epoch check, contiguous seq, alias resolution, cover by `seenListRev`, the untick decision table, re-acking duplicates.
  - **Watch side:** outbox model (seq assigned on first send, collapsing only unsent ops while keeping the earliest seq), go-back-N resend, ignoring older snapshots, re-applying pending ops.
  - Write the vectors to `testdata/sync/*.json`.
  - **Also port the model checker** (`tools/sync-model`) and run it in `verify.sh` with small bounds.
- **Check:**
  - Unit tests for `Rational`, units and the catalogue validator.
  - Codec round-trip and the size cap.
  - **All sync vectors pass:**
    - acks: a lost ack followed by a resend; tick → ack → untick; a duplicate after the ack;
    - sequence: a seq gap or reordered ops; collapsing without leaving a gap;
    - epoch: an epoch mismatch; a restore with the same epoch caught by the marker;
    - covering: a stale tick that becomes `partial`; a "need decreased" tick that becomes `noop`; a tick made on both phone and watch;
    - lifecycle: an op on a merged id redirected by `ItemAlias`; a full outbox; truncation keeping recent DONE items;
    - snapshots: an older snapshot arriving after a newer one.
  - The model checker finds 0 violations.

### Step 2 — Ingredient parser
- **2.1** Tokeniser, linear scanning only. It must handle a 10 KB pathological line in under 50 ms.
- **2.2** Grammar, flags, section headers, "and"/"or" lines.
- **2.3** Normaliser, synonyms and `mergeKey`.
- **2.4** Confidence score.
- **Check:**
  - **Frozen 60 (not held out):** name ≥95%, qty ≥97%, unit ≥95%.
  - **UK set:** name ≥90%.
  - **Info set:** reported for information only.
  - These are **self-consistency** gates; the 20 held-out lines are the real test.

### Step 3 — Categoriser, merge, packs, stock
- **3.1** Categoriser (§3.4).
- **3.2a** Contribution building:
  - Scaling, noScale handling, presence lines.
  - "I have some" subtracted, earliest line first.
- **3.2b** Covered and DONE derivation; tick and untick; "Finish shopping".
- **3.2c** Display policy, rounding, units setting.
- **3.3** `PackPlanner`, with these cases:
  - 1.1 kg against packs `1kg|5kg` → 2 × 1 kg.
  - 1.6 kg.
  - Count-based packs.
  - No fit within 4 packs → the rounded amount.
  - A compound quantity → no packs.
  - Packs turned off.
- **3.4** `Merger.preview`, plus the shown-rev check.
- **3.5** `StockLearner` (§3.1).
- **3.6** Catalogue re-key and merge logic, with `ItemAlias`.
- **Check:**
  - **Property tests:**
    - Adding then removing a use returns the same list.
    - A tick always makes the item DONE.
    - A contribution added after a tick is uncovered.
    - Untick restores the exact state from before the tick.
    - Apply-preview gives the same result as a direct merge.
  - **Must-not-merge pairs** stay separate.
  - **`StockLearner` cases:** passive pre-ticks don't count; 3 of 4 pre-ticks; suppression expires; decay after 60 days.
  - **Categoriser:** at least 85% of **info-set** names land somewhere other than Other.

### Step 4 — Android app (phone only)
- **4.1** `:extract`:
  - Safe fetch (§3.1), with MockWebServer tests for these: a redirect to a private IP, `file:`, a literal IP, non-443 ports, a non-HTML response, a gzip bomb.
  - Extractors, with a test for each quirk.
  - **Fixtures:**
    - The agent fetches about 15 pages **once**: BBC Good Food, NYT Cooking, a WPRM blog, a Tasty blog, a Yoast `@graph` site, a microdata site, and others.
    - Each page is trimmed to its structured block and recorded in `testdata/pages/SOURCES.md` as `src=live-YYYY-MM-DD`.
    - A blocked site gets a synthetic page instead.
    - `hostile/` holds a 10k-deep array, a 50k-entry ingredient list and an oversized script.
- **4.2a** `:data` schema v1 (§4), with `Callback.onCreate` seeding aisles, store and staples, plus the `Meta` and epoch marker.
- **4.2b** Repository: apply the preview; tick and untick; manual adds; edit quantity.
- **4.2c** Repository: remove a use or Undo an import (including the `StockStatDelta` rollback), rescale, "Finish shopping", soft delete with undo.
- **4.2d** Repository: export and import (validation, preview, automatic pre-export, new ids and epoch); catalogue re-key; applying watch ops in a single transaction.
- **Check (4.2):** an acceptance test for each path. Import followed by Undo must leave the database equal apart from revs.
- **4.3** App shell:
  - Hilt.
  - `hilt-work`:
    - KSP for both `com.google.dagger:hilt-compiler` and `androidx.hilt:hilt-compiler`.
    - `Application : Configuration.Provider` with `HiltWorkerFactory`.
    - In the manifest, `InitializationProvider` with `tools:node="merge"` and the `WorkManagerInitializer` meta-data set to `tools:node="remove"`.
  - Navigation, theme (with dynamic colour turned off in tests), edge-to-edge and predictive back.
  - A `ClaudeExtractor` Noop and a `WatchLink` fake.
  - The import worker.
  - **Worker tests:** `@HiltAndroidTest` + `@Config(application=HiltTestApplication::class, sdk=[34,36])`, with `WorkManagerTestInitHelper` + `SynchronousExecutor`.
- **4.4–4.8:** one screen per sub-step. Each has a ViewModel test, a Robolectric Compose test with **semantics assertions** (order, text, counts, a single TalkBack node per row, custom actions), and Roborazzi light and dark snapshots, recorded only.
  - **4.4** Review before adding, default view: a row exposes at most 3 elements; the banner; in stock; Cancel; Undo snackbar; the rev-change recompute.
  - **4.5** Review row detail sheet: I have some, always in stock, swap, move aisle, edit line.
  - **4.6a** List: grouping, tick/untick with undo, the half-tick icon, swipe and accessibility delete, Finish shopping, Clear all.
  - **4.6b** List: the "Add item" field (autocomplete, Enter keeps the keyboard open, multi-line paste, "already on list +1?"). The empty state with sample list. The "ready to review" card. Share as text.
  - **4.7** Item detail sheet and Recipes tab (remove from list with rollback, "Add the in-stock items now", the "add again?" prompt).
  - **4.8** Settings, grouped as in §3.5. The Claude section is a placeholder.
- **4.9** Share intent (text only), the paste chip, error states. Tests use real share payloads from Chrome, Firefox, Samsung Internet, Instagram and YouTube.
- **4.10** Debug-only (debug source set): "Load sample list", "Fake watch", and the debug log viewer. Backup rules exclude the key file and `TickEvent`.
- **4.11** `tools/contact-sheet`: `montage`, falling back to Pillow. It puts every Roborazzi PNG into `docs/screenshots/contact-sheet.png`.
- **Check:** `verify.sh` is green, and `HANDOFF.md` lists the APK path and SHA-256.
- **⏸ Hand-off A [YOU]** (`HANDOFF.md`, at most 15 yes/no items):
  - Create `main` and merge the PR.
  - Install the APK.
  - Share 5 recipes from Chrome.
  - Add 5 items by typing.
  - Look at the contact sheet and `REVIEW.md`.
  - Check the 20 held-out lines.

## Run 2

### Step 5 — Forerunner 245 app
- **5.0** Prerequisites. If any fails, **stop the run** and write it in `HANDOFF.md`.
  - The `fr245` device folder is available.
  - Download and cache SDK 9.2.0, and confirm it compiles for `fr245`. If it doesn't, find the newest SDK that does (from `sdks.json`) and record it in an ADR.
  - Read `compiler.json` and write `garmin/README.md`: API level, the memory limit for each app type, glance support, `Attention` vibrate, screen size and shape, buttons.
  - Derive `minApiLevel`, the snapshot size cap and the outbox cap from those numbers.
- **5.1** Generate a developer key with openssl (RSA 4096, PKCS#8 DER; git-ignored).
  - **Check:** the SDK `Comm` sample builds for `fr245`.
- **5.2** `manifest.xml`: `fr245` only, the minApiLevel from 5.0, Communications, and the watch UUID.
- **5.3** Data layer: a data-driven port of the 1.5b watch rules, with `nextSeq`, the outbox, retry back-off, acks and ignoring older snapshots.
- **5.4** UI, buttons only:
  - The aisle list and per-aisle `CheckboxMenu`.
  - Debounce, and ticked items sinking to the bottom.
  - The "aisle done" prompt with Next focused.
  - Vibration, sub-labels, and the "N unsynced", "Last synced" and "Update" screens.
  - The glance, only if it's supported.
- **5.5** A `:debug` build with an embedded 60-item, 12-aisle sample.
- **5.6** Monkey C `(:test)` tests that replay `testdata/sync/*.json`, compiled with `monkeyc --unit-test`. Running them needs the simulator, which is a [YOU] or optional step.
- **Check:** `monkeyc -d fr245` builds; the test build compiles; the peak memory estimate is within the 5.0 limit.

### Step 6 — Phone ↔ watch sync
- **6.1** `WatchSyncImpl`:
  - Register in `Application.onCreate`.
  - `WIRELESS` mode (or `TETHERED` in debug), and re-register on CONNECTED.
  - Add `<queries>` and the init-error prompts.
  - Validate all input from the watch.
- **6.2** Glue code:
  - Always answer `hello` and op batches.
  - Build each snapshot for this install.
  - Apply ops in one transaction, keeping `WatchOpResult`.
  - Track the clock offset, using `watchNow` from each batch.
  - Show the "Apply old ticks?" prompt.
  - Send `resync` on connect.
- **6.3** A fake `IQDevice` replays every sync vector, end to end through the repository.
- **Check:** `verify.sh` is green.
- **⏸ Hand-off B [YOU]:**
  - Sideload the app and install the APK.
  - Import a recipe and open the watch app. The list should appear within 10 s.
  - Tick 3 items using buttons only.
  - Go out of range, tick 2 more, add a recipe on the phone that also uses one of them, then reconnect. You should see "1 more".
  - Undo from the aisle prompt.
  - Test a 60-item sample for memory and speed.

## Run 3

### Step 7 — Claude for text
- **7.1** ADR 001: use OkHttp + kotlinx.serialization (no SDK).
- **7.2** Key storage and its failure modes (§3.7). Settings for the key, model, caps, cost and privacy note. Migration to schema v2 (`ClaudeUsage`).
- **7.3** Request builder: the per-model fallbacks rule, effort, schema, untrusted-content wrapping and the input cap.
- **7.4** Response handling (`stop_reason`, validation, errors, usage, redaction).
- **7.5** Wiring:
  - Claude is called **only from the review screen while it's in the foreground**.
  - Fallback mode below the confidence threshold.
  - "Claude pending" + Retry.
  - Badges, and caching results in `RecipeLine`.
- **7.6** **Live smoke test** with your key: one call per model.
  - Redact the real responses and save them as `testdata/claude/live-*.json`.
  - `tools/eval-claude` compares the rule parser with each model on the frozen 80 + UK 25 + 15 pages, reporting accuracy, cost and `output_tokens`. It suggests `CONFIDENCE_THRESHOLD` and a default model.
- **Check:** MockWebServer tests cover every `stop_reason` and error; the live smoke test passes; the eval report is committed.
- **⏸ Hand-off C [YOU]:** choose the default model and mode from the eval report, then try 5 hard recipes.

**— End of MVP. —**

## Later (v1.1)

### Step 8 — Instagram and media
- **8.1** Check the oEmbed claim (ADR 003), then add the `og:` fallback.
- **8.2** Caption routing (§3.8).
- **8.3** `:media` module: ML Kit (bundled), Photo Picker, image and video share intents, and the "Add media" screen.
- **8.4** OCR for screenshots, with the §3.9 post-processing.
- **8.5** Video sampling, OCR and Claude image blocks, with a cost estimate first.
- **8.6** Transcript experiment (ADR 004).
- **8.7** Delete temporary media after use.
- **Check:** unit tests for routing, the sampler and the OCR text merge.
- **[YOU]:** 10 reels.

### Step 9 — Smart shopping
- **9.1** Store profiles UI, the "Shopping at" chip, `TickEvent`, `AisleOrderLearner`, "Sort like my last trip".
  - **Check:** made-up trips (home-ticked, after a prompt, following the displayed order, skipped aisles, pinned aisles).
- **9.2** Cookbook photos (§3.9).
  - **Check:** at least 15 recorded OCR outputs.
  - **[YOU]:** 5 pages.
- **9.3** Meal planner (§3.10): migration, library, week view, combined review.
  - **Check:** repository tests and the migration test.
- **9.4** Corrections map; the Australian tablespoon.

### Step 10 — Polish
- **10.1** Icons, plus a further accessibility pass.
- **10.2** Benchmarks: parse and merge 20 recipes in under 200 ms; a 200-item list.
- **10.3** Visible WebView fallback (with the safe-fetch address filter, `allowContentAccess=false`, no JavaScript bridges).
- **10.4** Watch `CustomMenu` prototype; a "Shopping mode" foreground service, if needed.

### Exit criteria for each run
- Every item is either `[x]` or `[~]`/`[!]` with a reason.
- `verify.sh` is green locally.
- `HANDOFF.md` has the checklist and the next run's prerequisites.
- `KNOWN_ISSUES.md` is up to date.

## 7. Risks
- **The agent grading its own work.** Mitigations:
  - The golden set is picked by script and frozen with a checksum, with 20 lines held out for you.
  - Screenshots are recorded but never gated.
  - Live Claude responses in Run 3.
  - You review the contact sheet and the catalogue.
- **The Forerunner 245 may be limited:** an older API level, little memory, possibly no glance, and possibly dropped from SDK 9.x. Step 5.0 measures this before any code is written; the fallbacks are an older SDK and a smaller snapshot.
- **The sync design has been checked by a model checker,** but only on small bounds. The real devices are tested at Hand-off B.
- **Agent-estimated catalogue and pack sizes.** They're tagged, shown muted until you confirm them, can be overridden, and are in REVIEW.md.
- **Blocked recipe sites** fall back to "Select lines manually" until the v1.1 WebView.
- **Claude cost:** Opus is the default and costs the most. Mitigations: estimates, a monthly cap and a daily call cap. Hand-off C chooses the default.
- **Instagram and Meta's terms** may change, and video quantities are often estimates.
- **Stock learning hiding something you need.** Mitigations: only explicit taps count, the banner, rows stay visible, decay, Undo.
- **Learned aisle order** may be wrong. It only suggests changes, and pinned aisles never move.
- **Copyright:** fixtures are trimmed to structured data; the golden lines come from the Apache-2.0 NYT dataset.

## 8. Later ideas (v2+)
- Shared lists via a sync backend.
- Pantry tracking (stock levels, expiry dates).
- Home-screen widget.
- whisper.cpp transcripts.
- Claude-assisted substitutions.
