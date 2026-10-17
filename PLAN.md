# HyperShop — Plan (rev 5)

A **personal** Android app that won't be published. You share a recipe URL, an Instagram reel or a cookbook photo. **Claude reads the ingredients.** You review them and tick off what you already have. The rest merges into a shopping list sorted by aisle, which is mirrored on your **Garmin Forerunner 245** so you can tick items there.

## 0. Change log
- **rev 2–3:** nine reviews; watch before Claude; MVP cut line; progress files; three build runs.
- **rev 4:** four more reviews; simpler quantity model; model-checked sync; personal use only, Forerunner 245, Android 14+.
- **rev 4.1:** your BangGym repo is the template for the build, the workflows, the watch tooling and the protocol conventions.
- **rev 5 (your decisions):**
  - **Claude is the main parser, using Haiku 5.5 by default** (about $0.001 per recipe). It's built in Run 1, and the old Run 3 is gone.
    - A small rule parser stays for offline use and as a fallback.
    - The heavy rule-parser work (confidence calibration, strict accuracy gates on hand-checked lines) has been removed.
  - **Cut:**
    - The pack planner and pack overrides. Packs are now a short note on about 20 catalogue items.
    - Stock learning and its banner. Staples and "Always in stock" stay.
    - JSON export and import. Android backup plus "Share list as text" cover this.
    - The "apply old watch ticks?" prompt.

## 1. Architecture

```
 Chrome / Instagram ─share URL──▶ ┌──────────────── Android app (Kotlin) ─────────────────┐
 Camera / gallery (v1.1) ───────▶ │ Import worker ─▶ Safe fetch ─▶ Extractor (WPRM/JSON-LD/ │
 "Add item" field / paste chip    │                                microdata/headings)    │
                                  │                         │                             │
                                  │        ┌────────────────┴───────────────┐             │
                                  │   online + key: Claude (Haiku)   offline: rule parser │
                                  │        └────────────────┬───────────────┘             │
                                  │                         ▼                             │
                                  │        Review before adding (in-stock ticks)          │
                                  │                         ▼                             │
                                  │        Merge ─▶ Categorise ─▶ Room (:data)            │
                                  │                                 ▲        │            │
                                  │              WatchSync (App-scoped) ◀────┘  ▼ UI      │
                                  └──────────────────────────┬────────────────────────────┘
                                                             │ Connect IQ Mobile SDK
                                                 ┌───────────▼────────────┐
                                                 │ Forerunner 245 app     │ list, ticks,
                                                 │ (Monkey C, buttons)    │ outbox, offline
                                                 └────────────────────────┘
```

- **Local-first.** There is no backend. The phone only goes online to fetch recipe pages, to call Claude with your key, and (in v1.1) to call Meta oEmbed.
- **Why native Android:** Garmin watch apps can only talk to a companion phone app through Connect IQ, or to a server.

## 2. Tech stack

### Template: BangGym
`robert4lyf/BangGym` is your working Android + Forerunner 245 app, and its stack already builds in your CI. **Step 0 copies its build setup rather than picking new versions.**

| From BangGym | Used for |
|---|---|
| Gradle wrapper (9.7.1), root `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml` (AGP 9.4.0, Kotlin 2.2.10, KSP 2.3.6, Room 2.7.1, Compose BOM, ktlint 12.1.2, Robolectric 4.14.1, ciq-companion-app-sdk 2.4.0) | the phone build |
| Manual DI: an `AppContainer` on the `Application`, no Hilt | wiring, plus a WorkManager factory that reads from the container |
| `.github/workflows/android.yml`, `connectiq.yml`, `release.yml` | CI, the device-independent watch compile, and the rolling APK release with `build-<n>` tags (works with Obtainium) |
| `tools/build_watch.sh`, `package_watch.sh`, `check_protocol_parity.py`, `check_monkeyc_limits.py` | watch builds, `.prg` and `.iq` packaging, Kotlin↔Monkey C protocol parity, Monkey C's 9-argument limit |
| `garmin/` skeleton (`manifest.xml` layout, `monkey.jungle`, `test.jungle`) plus the `PhoneLink.mc` and `SetQueue.mc` patterns | the watch app (with a new app UUID) |
| `app/.../garmin/` patterns (`ConnectIqGarminSync`, `GarminCodec`, `GarminProtocol`, `GarminSyncCoordinator`, `FakeGarminSync`) | the phone side of sync |
| The `CLAUDE.md` and `.claude/` conventions, and the docs style | agent rules and documentation |

**Libraries HyperShop adds:** OkHttp + mockwebserver 4.12.0, Jsoup 1.23.2, kotlinx-serialization, WorkManager, DataStore, kotest-property, Roborazzi and Timber.
- Step 0.4 chooses the newest version of each that works with BangGym's AGP and Kotlin, proves it with a hello test, then **freezes** it.
- HyperShop uses **minSdk 34** and targetSdk 36.

**Fallback if the BangGym stack fails in the container:** AGP 8.13.2, Gradle 8.14.3, Kotlin 2.2.21, KSP 2.2.21-2.0.5, Compose BOM 2026.06.01, core-ktx 1.18.0, lifecycle 2.10.0, navigation 2.9.8, Room 2.7.2, WorkManager 2.11.2, DataStore 1.2.1, kotlinx-serialization 1.9.0, coroutines 1.10.2, Robolectric 4.16.1, Roborazzi 1.76.0, kotest-property 6.0.7, ktlint-gradle 14.2.0. All of these were checked against Maven in Oct 2026, with `buildToolsVersion = "36.0.0"`. Record the switch in an ADR.

- **Other choices:**
  - **Modules:**
    - `:core` — pure JVM: the model, catalogue, rule parser, merge and the sync codec, as packages.
    - `:data` — Room plus the repository.
    - `:extract` — safe fetch and the extractors.
    - `:claude` — the Claude client.
    - `:app` — the Android app.
    - `:media` — v1.1.
  - **Claude:** called directly at `/v1/messages` over OkHttp and kotlinx.serialization, with `anthropic-version: 2023-06-01`. No SDK is used.
  - **Secrets:** your Claude key is encrypted with an AES-GCM key in the Android Keystore (not tied to the screen lock). The encrypted value is kept in a DataStore file that is excluded from backup.
  - **Not in v1:** ML Kit, Photo Picker, `SpeechRecognizer`, CameraX and FileProvider.
  - **Memory:** `org.gradle.jvmargs=-Xmx4g`, `kotlin.daemon.jvmargs=-Xmx2g`, `org.gradle.workers.max=3`; test `maxHeapSize=2g`; `maxParallelForks=1` for `:app`.

## 3. Core features

### 3.1 Import
- **Ways in (v1):**
  - **Sharing text (`ACTION_SEND`):** the app takes the first https URL, strips `utm_*` and `igsh`, and uses `EXTRA_SUBJECT` as the title if the page has none.
  - **"Paste link" chip:** it appears only when the clipboard description says the clipboard holds text, and the clipboard is read only when you tap it.
  - **The "Add item" field** (§3.5).
  - **Surviving restarts:** `singleTop` + `onNewIntent`, with state in SavedStateHandle plus an `ImportJob` row.
- **Safe fetch (`:extract`):**
  - **Which addresses:**
    - https only, re-checked after every redirect, with at most 5 redirects.
    - A custom DNS lookup refuses loopback, private (RFC1918), link-local, CGNAT, ULA and multicast addresses. Raw IP addresses and ports other than 443 are refused too.
    - `file:`, `content:` and `intent:` links never reach the fetcher.
  - **Requests:** no cookies, a browser user agent, 10 s to connect and 30 s for the whole call.
  - **Responses:** `text/html` or `application/ld+json` only, capped at **3 MB after decompression**.
- **Extraction limits:**
  - Each JSON-LD block: 512 KB or less, nesting depth 64 or less.
  - At most 10 recipes per page, 300 lines per recipe, 500 characters per line.
  - The rule tokeniser scans the line once, with no regex backtracking.
  - If the app runs out of memory or overflows the stack while parsing, it goes to "Select lines manually".
- **Extraction order:**
  1. WPRM or Tasty recipe-plugin markup
  2. JSON-LD `Recipe`
  3. Microdata
  4. The block under an "Ingredients" heading
  5. All of the page's text, sent to Claude
  6. "Select lines manually"
- **JSON-LD quirks**, each with a test:
  - `@graph`
  - `@type` given as an array
  - several recipes on one page
  - `recipeIngredient` as a single string, or under the older `ingredients` key
  - nested arrays
  - HTML entities, including double-encoded ones
  - HTML tags inside strings
  - lenient JSON
  - the different forms of `recipeYield`
- **Parsing the ingredient lines** (§3.7):
  - **Online, with a key:** the extracted lines, or the page text when no structured data was found, go to **Claude** in one call.
  - **Offline, with no key, or if Claude fails:** the **rule parser** (§3.2) runs, and the recipe is marked "Claude pending" with a "Retry with Claude" button.
- **Pages that are blocked or need JavaScript** go to "Select lines manually". A WebView fallback comes in v1.1 (Step 10.3).
- **Importing the same URL again** asks "Already on your list — add again?"
- **Imports left in the background** show a "1 recipe ready to review" card. There are no notifications in v1.

#### Review before adding
Nothing is added until you confirm it here.
- **Default view:**
  - The header shows the title, a servings stepper and an **"Add N items"** button.
  - Rows are grouped by aisle and show only three things: the name, one quantity, and an **"In stock"** checkbox.
  - In-stock rows stay where they are, greyed out with a strikethrough.
- **Pre-ticked as in stock:** staples (the `Staple` table, seeded with salt, pepper, oil and water), and lines marked optional or garnish.
- **Row detail sheet** (tap a row):
  - What merging will do (e.g. "Milk: +200 ml → 700 ml on list"), and the pack note if there is one.
  - The source line, with live re-parsing as you edit it.
  - "A or B" swap.
  - **"I have some"** (enter an amount), **"Always in stock"** (adds it to `Staple`), and move aisle.
- **Overflow menu:** mark this aisle in stock, clear in-stock.
- **"I have some"** is subtracted from the merged row when the contributions are created, earliest line first. It is never stored separately.
- **Confirming:**
  - In one transaction, the app re-checks the `rev` of every item shown. If anything changed, it shows "List changed — review updated" and recomputes.
  - Otherwise it inserts the rows and shows a snackbar for 10 s: **"Added 14 items · Undo"**.
  - **Undo** deletes that `RecipeUse` and everything that cascades from it, and bumps the revs of the items affected.
- **Speed target:** share → glance → Add in under 5 s for a 12-line recipe, plus the Claude call (about 1–3 s on Haiku).

### 3.2 Rule parser (offline fallback, kept small)
- **Purpose:** clean structured-data lines, such as `"2 ½ cups (300g) plain flour, sifted"` → `{qty 5/2, unit CUP, alt 300 g, name "plain flour", note "sifted"}`.
- **What it handles:**
  - Numbers, fractions (including unicode ones like ½) and ranges.
  - **The `QtyUnit` list:**
    - mass: g, kg, oz, lb
    - volume: ml, l, tsp, tbsp, cup, fl oz, pint, quart
    - per-item: clove, bunch, tin/can, packet, sprig, slice, head, stalk, fillet, piece, stick
    - small amounts: pinch, dash
  - **Unit sizes by locale:** a cup is 240 ml in the US and 250 ml in metric recipes; a pint is 568 ml in the UK and 473 ml in the US.
  - **Case-sensitive spoons:** `T` = tbsp, `t` = tsp.
  - **Parenthetical alternatives**, such as "(300g)".
- **Prep words vs identity words:** prep words (chopped, sifted…) go into `note`. Identity words (ground, smoked, red, dried, tinned, frozen, powder, unsalted…) stay in the name.
- **Flags:** `optional`, `garnish`, `noScale` ("to taste", "for frying", no quantity). **Section headers** are recognised.
- **Not handled:** "X and Y" splitting, "A or B" alternatives, and "juice of" phrases. Claude handles those. The rule parser marks such lines **"check"**, and they're highlighted in review.
- **Normalising:** the head noun is singularised when the singular is in the catalogue. Synonyms map to catalogue IDs.

### 3.3 Quantities, merging and ticking
- **Identity:** every item has a unique `mergeKey`, either `cat:<catalogId>` or `name:<normName>`. Matching on the head noun is used only to pick an aisle.
  - **When the catalogue version changes,** items keyed by name that now resolve to a catalogue ID are re-keyed. If two items clash:
    - The lower id survives, and contributions move to it.
    - The survivor's aisle and position win.
    - Overrides you set win over seeded ones.
    - The new `rev` is the higher of the two plus 1.
    - `ItemAlias(oldId → newId)` redirects watch ops that still use the old id.
- **Contributions:** an item's amount is the sum of its contributions. Each contribution belongs to a `RecipeUse` (a recipe import or a manual add) and stores `qtyBase` for each dimension. It is already scaled, and "I have some" has already been subtracted.
  - Lines with no quantity are stored as *presence* only.
  - Volume and mass combine only for catalogue rows that have `gramsPerCup`. Otherwise the item shows a compound amount, such as "1 onion + 200 g".
- **Ticking** covers every contribution the item has at that moment (`coveredBy = tickId`, from `Tick(id, itemId, at, source, installId?, seq?)`).
  - **DONE:** an item is DONE when all its contributions are covered. Otherwise it shows the uncovered amount, e.g. "1 more", with "2 already ticked" underneath.
  - **Recipes added later:** their contributions start uncovered.
  - **Unticking** uncovers exactly the contributions from that item's most recent `Tick`.
  - **Removing or rescaling a use** deletes or rescales its contributions, covered or not.
  - **Ticks never create stock.** Rounding only affects the display, so a tick always fully covers.
- **"Finish shopping":**
  - It deletes DONE items and any uses left empty. Deletes are soft (`deletedAt`) and can be undone.
  - Uses that have been fully covered for 7 days are archived.
- **Manual changes:** adding an item or editing its quantity creates or changes a contribution in a `kind=MANUAL` use. The total is clamped at 0.
- **Pack note:** the catalogue column `packNote` (e.g. "1 × 500 g bag") is filled in for about 20 items. It is shown as the row's sub-line and in the item sheet. It's display only, with no maths.
- **Rounding:** counts round up (produce to the nearest ½). Mass and volume use friendly steps. Fractions are shown as ½, ⅓ and ¼. The units setting chooses metric, imperial or as written.
- **Revisions:**
  - **`Item.rev`** goes up, in the same transaction, whenever any of these change: the item's contributions or ticks, its aisle, the display units, or the catalogue.
  - **`listRev`** lives in Room `Meta`, and every contribution stores the `createdListRev` it was created at.
- **Dry run:** `Merger.preview(list, incoming, decisions)` is pure. It returns the inserts plus the revs of the items shown.

### 3.4 Aisles and categorisation
- **Aisles** have stable codes (the `AisleCode` enum gives the seeds and default order). You can rename, hide or add them.
  - Seeded aisles: Produce, Bakery, Meat & Fish, Dairy & Eggs, Chilled, Frozen, Tins & Jars, Pasta/Rice/Grains, World Foods, Baking, Nuts & Dried Fruit, Oils & Vinegars, Spices & Condiments, Free-from, Drinks, Household, Other.
- **Store:** `Callback.onCreate` seeds `StoreProfile(1, "My store")` and its `AisleOrder`. The list always reads `AisleOrder`. v1 has one store.
- **Lookup order:**
  1. your override
  2. identity words
  3. catalogue ID
  4. Claude's `aisle_suggestion`
  5. head noun
  6. keyword rules
  7. Other
- **Moving an item to another aisle** asks "Always put X in Y?". Answering yes writes a `CategoryOverride`; "Just this time" doesn't.
- **Catalogue (about 400 rows):**
  - Names are taken by script from the most frequent names in the NYT ingredient-phrase-tagger snapshot (Apache-2.0; its LICENSE and NOTICE files are in `catalog/`).
  - The agent writes the UK names, aisle, staple flag and pack notes (about 20 items), tagged `src=agent-estimate`.
  - `catalog/REVIEW.md` is generated so you can check it.
- **Learned aisle order (v1.1, Step 9.1):**
  - **Data:** ticks are logged as `TickEvent` rows.
  - **What counts as a trip:** ticks from one device, with gaps under 20 min, at least 5 ticks and at least 8 min long.
  - **Trips ignored:** ones ticked at home, and ones from bulk actions.
  - **How the order is learned:** for each pair of aisles, which one comes first in at least 70% of trips, combined into one order by a Borda count. Only trips whose order differs from the display count.
  - **Applying it:** the app only *suggests* a new order, and pinned aisles never move. There's also "Sort like my last trip".

### 3.5 List UI
- **"Add item" field** at the top:
  - Autocompletes from recent items and the catalogue.
  - **Enter** adds the item and keeps the keyboard open. Pasting several lines adds one item per line.
  - If the item is already on the list, it offers "+1?".
  - These items are rule-parsed (no Claude) and skip the review screen.
- **Empty state:** "Share a recipe from Chrome" (a two-step picture), the add field, and "Try a sample list".
- **Rows:**
  - **Grouping:** by aisle, in store order.
  - **Text:** the main line is what to put in the basket. The sub-line shows context or the pack note.
  - **Partly ticked:** a half-filled tick.
  - **DONE:** sinks within its aisle, dimmed, with a tick mark (not colour alone).
- **Actions:** tap to tick or untick (with undo), swipe to delete (with an accessibility action too), "Finish shopping" and "Clear all" (both with undo), and "Share list as text".
- **Item sheet:**
  - The totals for each dimension, and the recipes that contributed.
  - The pack note.
  - Change aisle, and edit quantity (which creates a manual contribution).
- **Recipes tab:**
  - Shows the uses currently on the list.
  - **"Remove from list"** for each use.
  - **"Add the in-stock items now":** reopens the review screen with full amounts and nothing pre-ticked.
- **Backup:**
  - **Android Auto Backup** covers the database. It excludes the key file and `TickEvent`, and a restore creates a new list epoch (§3.6).
  - There is no JSON export or import.
- **Settings**, grouped:
  - List: staples, units
  - Aisles
  - Watch
  - Claude: key, model, caps
  - Advanced: debug log
- **Accessibility from Step 5:**
  - Each row is a single TalkBack node with custom actions.
  - Touch targets are at least 48 dp, and contrast is at least 4.5:1, with a dark-theme snapshot to check it.
  - Layouts work at 200% font size.
  - Never red or green alone.

### 3.6 Garmin Forerunner 245 app and sync
- **The device:**
  - **Buttons:** 5, and **no touchscreen**: Start/Stop = select, Back, Up, Down, Light. Everything must work with buttons alone.
  - **Screen:** 240×240 round, MIP.
  - **Known from BangGym:**
    - Device IDs `fr245` and `fr245m`; `minApiLevel="3.2.0"` works.
    - **A watch app gets 128 KB.** A release build is about 15 KB; a debug build is about 7× bigger, so always sideload release builds.
    - Check `--build-stats 0` after each watch step.
  - **Still to check (Step 6.0):** whether glances are supported, and their memory limit.
  - **Payload size:** BangGym's notes say messages on older devices are capped "in the low hundreds of bytes", and anything larger is **dropped silently**. This isn't verified for the 245, so the design assumes it's true:
    - Wire keys are 1–2 characters.
    - **Snapshots are paged:** one message per chunk, each carrying `listRev`, `page` and `pages`. The watch treats a snapshot as complete only once every page for that `listRev` has arrived.
    - Ops are sent in batches; the batch size starts at 8 and is set from the measured limit.
    - **A debug payload probe** measures the real limit at Hand-off B.
- **Watch UI:**
  - **Aisle list:** shows `leftCount` from the phone, plus "+N on phone" when N > 0.
  - **Inside an aisle:** a `CheckboxMenu`.
    - Select ticks an item; selecting it again unticks it.
    - A second press within 700 ms is ignored.
    - Ticked rows sink to the bottom.
  - **"Aisle done" prompt:** "Produce done ✓ — [Next: Bakery ▸] [Undo <item>]", with **Next focused**, using `switchToView`. Auto-advance is optional and off by default.
  - **Feedback:** vibrates `if (Attention has :vibrate)`, and you can turn it off.
  - **Rows:** the label is the name; the sub-label is the quantity or pack note, 18 characters or fewer.
  - **Status screens:** "Last synced", "N unsynced", the empty state, and "Update HyperShop on the phone and watch".
  - **Glance:** "14 left · Produce next", only if the watch supports it.
- **Envelope:** `{v, t, ep(epoch), ii(installId), …}`, as native Monkey C objects.
  - **`hello`** carries `listRev`, `watchNow` and `ackedThrough`.
  - **Versioning follows BangGym's rule:** a message whose `v` doesn't match is dropped. New keys or message types don't need a version bump; only a change in the shape or meaning of an existing message does. The two apps are installed as a pair.
  - **One protocol, two declarations:** it's declared in `WatchProtocol.kt` and in `Protocol.mc`, and `check_protocol_parity.py` fails CI if they drift.
  - **Battery:** the phone sends only when the list changes (`distinctUntilChanged`). It re-sends the last snapshot on `hello` and whenever the link reaches `APP_READY`.
- **Ops from the watch** are arrays: `[seq, itemId, done, seenListRev, ts]`.
  - **Sequence numbers:** `nextSeq` is kept in the outbox's Storage key and written **before** sending. It is never reset.
    - A seq is assigned when an op is first sent.
    - Collapsing ops that haven't been sent yet keeps the earliest seq, so there are no gaps.
  - **Sending:** the outbox is persisted before the UI updates, and only one batch is in flight at a time.
    - **Retries:** back off 5 s → 15 s → 60 s while the app is open.
    - **Closing the app:** the watch tries to flush in `onStop`.
    - **When the outbox is full:** ticking is blocked with "Sync with phone".
- **How the phone applies an op**, all in **one Room transaction**:
  1. **Wrong epoch:** the result is `epoch`. The watch drops the op and shows "N watch ticks discarded (list replaced)".
  2. **Sequence:** only `seq == lastSeq + 1` is applied. Anything else is ignored, and the watch resends from `ackedThrough + 1` (go-back-N).
  3. **Unknown item:** the id is resolved through `ItemAlias`. If it's still unknown or deleted, the result is `rejected`, and the watch shows "Changed on phone".
  4. **Ticking (`done=true`):** covers the uncovered contributions with `createdListRev ≤ seenListRev`.
     - Nothing to cover → `noop`.
     - Contributions created later stay uncovered → `partial`, and the watch shows "1 more".
  5. **Unticking (`done=false`):** undoes this install's most recent `Tick` on that item. There isn't one → `noop`. Another tick still keeps the item DONE → `still_done`.
- **Snapshot pages from the phone:**
  - **Contents:** `{ep, lr(listRev), at(ackedThrough), ax(ack exceptions), pg, pgs, it:[[id, name, sub, aisleIdx, done, rev]], ai:[[code, name, left, hidden]], more}`.
  - **When:** the phone **always** replies to `hello` and to every op batch, even with an ack-only reply. It keeps the last 64 results per install, so duplicates are re-acked correctly.
  - **What gets dropped when it doesn't fit:** ack data is reserved first. Then, in order:
    1. DONE items, except those ticked in the last 30 minutes and those in the current aisle
    2. names shortened to 16 characters
    3. unticked items, from the end of the store order
  - **On the watch:** pages with a lower `listRev` are ignored, but their acks are still processed. Pending ops are re-applied on top of the snapshot.
- **Epoch:**
  - `listEpoch` is stored in Room `Meta`, alongside a marker file in `noBackupFilesDir`. If the marker is missing or doesn't match (after a restore), a new epoch is generated.
  - The watch clears its cache when the epoch changes, but keeps `nextSeq`.
- **Watch storage:** chunks of 32 KB or less. `meta` holds the epoch, installId and listRev; the outbox is stored with `nextSeq`; `glanceSummary` is separate. Every access is wrapped in try/catch.
- **Android side:**
  - The receiver is registered in `Application.onCreate`; `WatchSync` is app-scoped, and there's no permanent service.
  - Manifest `<queries>` for Garmin Connect, and prompts for SDK init errors.
  - **Watch input is untrusted:** types and ranges are checked, size and rate are capped, and its data never goes into SQL strings or paths.

### 3.7 Claude parsing (the main parser)
- **Settings:**
  - **Key:** entered as `textPassword`, with no autofill save and `FLAG_SECURE`. "Test key" calls `GET /v1/models`, which costs nothing.
  - **Model:** **Haiku 5.5 by default** (you chose it). Sonnet 5.5 or Opus 5.5 are available.
  - **Limits:** a monthly spend cap (default $2) and a daily cap of 50 calls.
  - **Cost counter:** shows what's been spent, including cache tokens.
- **When Claude is called:**
  - Only while the review screen is loading in the foreground. A share intent on its own never spends money.
  - **One call per import.** It sends the extracted lines, or the trimmed page text when there was no structured data.
- **Request:**
  - `output_config.effort:"low"` is always set explicitly. `thinking` is never disabled; Haiku 5.5's default effort is medium, so "low" has to be set.
  - `max_tokens` is 4000.
  - `fallbacks:"default"` with `anthropic-beta: server-side-fallback-2026-07-01` is sent **only for Opus 5.5 and Sonnet 5.5**. Haiku has no server-side fallback.
- **Strict schema** (`output_config.format`):
  - `additionalProperties:false` everywhere, every field required, and optional ones nullable with `anyOf`.
  - **Top level:** `status` (`ok | no_ingredients | not_a_recipe`), `title`, `servings`, `ingredients[]`, `source_urls[]`.
  - **Each ingredient:** `raw`, `name`, `quantity`, `quantity_max`, `quantity_text`, `unit` (an enum matching `QtyUnit`, plus `other`), `alt_quantity`, `alt_unit`, `note`, `section`, `optional`, `estimated`, and `aisle_suggestion` (an `AisleCode` enum).
  - The prompt tells Claude to split "X and Y", to keep "A or B" as one ingredient with the alternative in `note`, to keep identity words in `name`, and to put prep words in `note`.
  - Numeric ranges are checked in Kotlin, not in the schema.
- **After the response:**
  - **Results are post-processed:** Claude's `name` goes through the same normaliser and synonym map as the rule parser to get the `mergeKey`, and its units are converted to `QtyBase`.
  - **Handling it:**
    - `stop_reason` is checked first. A `refusal` falls back to the rule parser; `max_tokens` is treated as an error.
    - Then the response is validated against the schema.
    - 401/403 means a bad key; on 429 or 529 it backs off; a 5xx is retried once; network errors switch to offline mode.
- **Prompt injection:** page text goes inside `<untrusted_content>` tags, and Claude is given no tools.
  - A URL Claude returns is accepted only if it appears verbatim in the source, uses https, and you tap it.
  - Input is capped at about 30k characters.
- **Key failures:**
  - **Missing after a restore:** asks you to re-enter the key.
  - **The Keystore can't decrypt it** (`KeyPermanentlyInvalidatedException`, `AEADBadTagException`, or a `ProviderException`): the key is wiped, Claude switches off, and you see a one-time notice.
  - **"Remove key"** also clears it from memory.
- **Logging:**
  - Release builds keep logs in a 500-line in-memory ring buffer. Request and response bodies never go to Logcat.
  - URLs are logged without query strings, and `sk-ant-…` and `x-api-key` are redacted.
  - `verify.sh` scans for `sk-ant-`.
- **Cost:**
  - **Haiku 5.5:** about $0.001 per text import.
  - Sonnet 5.5 costs about 20× more, and Opus 5.5 about 40× more.
  - Media imports (v1.1) show an estimate first.
- **Offline:** the rule parser's result is shown, marked "Claude pending", with a "Retry with Claude" button. It never retries on its own.

### 3.8 Instagram import (v1.1)
1. **Caption:**
   - Try Meta oEmbed first, `graph.facebook.com/v25.0/instagram_oembed`. Reportedly no token has been needed since 2026-06; **check this in Step 8.1.**
   - If that fails, make one `og:` fetch.
   - No WebView on instagram.com.
2. **Routing:** a recipe link in the caption → web import. A caption that holds ingredients → Claude. Anything else → "Add media".
3. **"Add media":** a how-to screen, then one of:
   - **Screenshots** → OCR (§3.9) → Claude.
   - **Video** → OCR at about 1 frame per second, keeping only frames where the text changed. Add 2–3 frames when OCR confidence is low.
   - **A transcript experiment** using the on-device recogniser with `EXTRA_AUDIO_SOURCE` (needs API 33+, which your phone has).
   - **Paste caption.**
4. **Review:** everything goes to the review screen. Amounts that came from video or speech are marked "estimated".

### 3.9 Cookbook photo import (v1.1)
- **Taking the photo:** `TakePicture` writes to a FileProvider cache file, which is deleted afterwards. `verify.sh` checks that no `CAMERA` permission is declared. Photo Picker and image shares are also accepted.
- **No crop screen:** OCR reads the whole page and you tap the lines you want, with the ingredient block preselected.
- **OCR clean-up:**
  - Columns are found from where the text blocks overlap horizontally.
  - Wrapped lines are rejoined when they are indented and the gap is under 1.3× the line height.
  - Misread fractions are fixed: `%`, `Y2` and `V2` become ½; `O` becomes 0 and `l` becomes 1 inside numbers.
  - The OCR text then goes to Claude together with the image.

### 3.10 Meal planner (v1.1)
- **Library:** every imported recipe is saved, with search and favourites.
  - Deleting a recipe that's in use asks first, then turns its contributions into manual ones. `PlannedMeal` uses FK RESTRICT, so a planned recipe can't be deleted silently.
- **Week view:** Breakfast, Lunch, Dinner and Other slots. Each meal has its own servings. The review screen gets a "Plan for…" option.
- **"Shop for this week":**
  - It opens one combined review, with rows like "Onion — 3 · Mon, Wed".
  - It creates one `RecipeUse` per meal.
  - "I have some" is subtracted earliest meal first.
- **Rules:**
  - Removing a meal removes only its use.
  - A recipe planned twice counts twice.
  - Moving a meal to another day changes nothing on the list.
  - "On list" and "shopped" are derived, never stored.

## 4. Data model (Room, `:data`)

**MVP schema (v1):**
- `Meta(id=1, listEpoch, listRev, catalogVersion, epochMarker)`
- `Item(id AUTOINCREMENT, mergeKey UNIQUE, catalogId?, normName, displayName, aisleCode, position, rev, deletedAt?, updatedAt)`
- `ItemAlias(oldId PK, newId)`
- `RecipeUse(id, recipeId FK RESTRICT nullable, kind: RECIPE|MANUAL, servings, addedAt, archivedAt?)`, with CHECK (kind='MANUAL' OR recipeId NOT NULL)
- `Contribution(id, useId FK CASCADE, recipeLineId FK nullable, itemId FK CASCADE, qtyBase?, noScale, createdListRev, coveredBy FK→Tick SET NULL)`
- `Tick(id, itemId FK CASCADE, at, source: PHONE|WATCH, installId?, seq?)`
- `LineDecision(useId FK CASCADE, recipeLineId FK CASCADE, status: ADDED|IN_STOCK|PARTIAL|EXCLUDED, PK(useId, recipeLineId))`
- `Recipe(id, url, title, servingsOriginal, unitLocale, importedAt, source: WEB|MANUAL, parsedBy: RULES|CLAUDE, model?, claudePending)`
- `RecipeLine(id, recipeId FK CASCADE, section, raw, parsedJson)`. Lines never change; re-importing creates a new `Recipe`.
- `ImportJob(id, state, inputRefs, resultJson, error, createdAt)`
- `Aisle(code PK, name, hidden, defaultOrder)`, `StoreProfile(id, name)`, `AisleOrder(storeId, aisleCode, position)`
- `CategoryOverride(key PK, aisleCode, source)`, `Staple(key PK, source: SEED|USER)`. Here `key` means the item's `mergeKey`.
- `ClaudeUsage(date, model, inputTokens, outputTokens, cacheWrite, cacheRead)`
- `DeletedItem(id, deletedAt)` (kept 7 days)
- `WatchInstall(installId PK, lastSeq, clockOffsetMs)`, `WatchOpResult(installId, seq, result, PK(installId, seq))` (last 64 per install)

**Added in v1.1:**
- `Recipe.source` gains `INSTAGRAM` and `PHOTO`; `Recipe` gains `favourite`.
- `PlannedMeal(id, date, slot, recipeId FK RESTRICT, servings, useId?)`
- `TickEvent`
- `AisleOrder.pinned`

Schemas are exported to `android/data/schemas/`, and `MigrationTestHelper` tests start from v2.

## 5. Repo layout and constants

```
android/      :app, :core, :data, :extract, :claude, :media (v1.1)
garmin/       Connect IQ project (from the BangGym skeleton), README (fr245 facts)
catalog/      ingredients.csv, synonyms.csv, SOURCES.md, REVIEW.md (generated), LICENSE-NYT, NOTICE-NYT
testdata/     ingredients/ (golden: frozen.json, CHECKSUM, CHANGES.md, synthetic-uk.json),
              pages/ (trimmed fragments + SOURCES.md, synthetic/, hostile/), sync/ (protocol vectors),
              claude/ (synthetic-*, live-*), ocr/, video/ (v1.1)
tools/        verify.sh, pick-golden, nyt-to-golden, contact-sheet, eval-claude/, sync-model/,
              build_watch.sh, package_watch.sh, check_protocol_parity.py, check_monkeyc_limits.py (from BangGym)
docs/         decisions/ (ADRs), HANDOFF.md, KNOWN_ISSUES.md, parser-spec.md, garmin-protocol.md, screenshots/
.github/workflows/ (from BangGym), .claude/ (settings.json + hooks/session-start.sh), CLAUDE.md
PROGRESS.md
```

| Constant | Value |
|---|---|
| App ID | `com.robert4lyf.hypershop` |
| Watch app UUID | new, generated in 0.3 |
| versionCode / versionName | commit count / `git describe --tags --always` (as in BangGym's release workflow) |
| Signing | committed debug keystore (personal use), and the rolling release APK |
| Watch key | throwaway in the container; your `CIQ_DEVELOPER_KEY` (shared with BangGym) in CI |

## 6. Build plan

### Two runs
| Run | Delivers | What you need to provide first |
|---|---|---|
| **Run 1** | Steps 0–5: the phone app with Claude parsing, merging, the review screen, the list, and the sync codec | **`ANTHROPIC_API_KEY`** as an environment secret on the cloud session: a dedicated key with a spend limit, used for the live smoke test and the eval |
| **Run 2** | Steps 6–7: the Forerunner 245 app and sync | Hand-off A done, and your BangGym secrets (`CIQ_DEVELOPER_KEY`, `GARMIN_USERNAME`, `GARMIN_PASSWORD`) copied to the HyperShop repo |
| Later | Steps 8–10 (v1.1) | depends on the step |

### Rules for the agent
1. **On start:** read `PROGRESS.md` and resume at the first unchecked item **in this run**. Never redo checked items, and never start the next run.
2. **Each sub-step:** implement, check, then commit as `[<step id>] …`, updating `PROGRESS.md` in the same commit. Mark it `[x]`, `[~] stubbed` or `[!] blocked`, with a note and the check command. Find SHAs with `git log --grep`.
3. **Gates:** `tools/verify.sh` must pass locally. CI is best-effort.
4. **Branches:** work only on the session branch, and never create branches or merge. At the end of the run, open a PR if `main` exists; otherwise note that in `HANDOFF.md`.
5. **Versions are frozen** (§2). Problems go in `KNOWN_ISSUES.md`.
6. **Golden data:**
   - Never edit it to make a test pass.
   - A real fix needs its own commit (`golden-fix: …`) that touches nothing under `android/`, updates `CHECKSUM`, and is logged in `CHANGES.md`.
   - At most 3 fixes per run.
7. **Low on context:** commit, update `PROGRESS.md` and `HANDOFF.md`, and stop cleanly.
8. **Stop** at the end of the run. No gold-plating.

### Things only you can provide
| Item | When |
|---|---|
| Set `ANTHROPIC_API_KEY` in the cloud environment (a dedicated key with a spend limit) | before Run 1 |
| Create `main` and merge each run's PR (do this first at each hand-off) | after each run |
| Install the APK from the rolling release link | Hand-offs A, B |
| Check the contact sheet, `catalog/REVIEW.md` and the 20 held-out golden lines | Hand-off A |
| Copy the 3 Garmin secrets from BangGym into HyperShop's repo settings | before Run 2 |
| Sideload the `.prg` (or install the beta app) on the Forerunner 245 | Hand-off B |
| 10 reels and 5 cookbook pages | Steps 8 and 9.2 |

---

## Run 1

### Step 0 — Toolchain and scaffolding
- **0.1** Create `.gitignore`, `README.md`, `PROGRESS.md` (Run 1 items only), `docs/HANDOFF.md` and `docs/KNOWN_ISSUES.md`.
- **0.2** Android SDK:
  - Download `cmdline-tools`, run `yes | sdkmanager --licenses`, and install `platforms;android-36`, `build-tools;36.0.0` and `platform-tools` into `$HOME/android-sdk`. Write a git-ignored `local.properties`.
  - **SessionStart hook:** `.claude/settings.json` (`matcher: "startup"`) runs `.claude/hooks/session-start.sh`.
    - It runs only when `CLAUDE_CODE_REMOTE` is set.
    - It's idempotent, and doesn't fail when the network is down.
    - It exports `ANDROID_HOME` through `$CLAUDE_ENV_FILE`.
    - It does not download the Connect IQ SDK.
  - **Check:** `java -version`, `$ANDROID_HOME`, `sdkmanager --list_installed`, and that `ANTHROPIC_API_KEY` is present (without printing it).
- **0.3** **Copy BangGym's build setup** (§2): the wrapper, root build files, version catalog, `.editorconfig`, ktlint, the workflows (renamed), and the `CLAUDE.md` conventions adapted to HyperShop.
  - Add the HyperShop modules, minSdk 34, the debug keystore, and a new watch UUID.
  - Record the BangGym commit in `docs/decisions/000-template.md`.
- **0.4** Add the HyperShop libraries, with one hello test each for:
  - JUnit5 and kotest-property
  - a Robolectric Compose test with a semantics assertion
  - a Roborazzi recording (`@GraphicsMode(NATIVE)`, `roborazzi.test.record=true`)
  - `AppContainer`
  - MockWebServer

  Then freeze the versions. If the BangGym stack fails, switch to the fallback (§2) and record it in an ADR.
- **0.5** `tools/verify.sh`:
  - ktlint and all tests
  - `assembleDebug`
  - the merged manifest: no `CAMERA`, and only the launcher and share activities exported
  - an `sk-ant-` scan
  - the golden `CHECKSUM`
  - `check_protocol_parity.py`, once both protocol files exist

  The CI workflows are copied from BangGym. If the push of a workflow is refused, mark it `[!]`.
- **Check:** a cold `tools/verify.sh` passes.

### Step 1 — Spec, enums, golden data, model, catalogue, sync codec
- **1.1** `docs/parser-spec.md`. It defines:
  - the output schema, which is shared by the rule parser and Claude's schema
  - `QtyUnit` and `AisleCode`
  - the rules for prep words and identity words
  - how NYT labels map onto this spec
- **1.2** Golden data, frozen before any parser code:
  - `tools/pick-golden`, with a committed seed, picks 80 NYT lines, stratified as:
    - 25 simple lines
    - 20 with comments or parentheses
    - 15 with fractions or ranges
    - 20 with no quantity, or "to taste"
  - `tools/nyt-to-golden` converts the labels, and every manual change cites a spec rule.
  - 20 of the 80 lines are held out for you to check.
  - Add 25 hand-written UK and metric lines (`synthetic-uk.json`).
  - Commit it all on its own, with `CHECKSUM` and `CHANGES.md`.
- **1.3** The model in `:core`:
  - `Rational`, `Dimension` and `QtyUnit`, with locale factors.
  - `QtyBase` per dimension, plus a presence value.
  - `ParsedIngredient` and `RecipeImport`.
  - The covered/DONE rules (ADR 002).
- **1.4a** A script that builds the 400 most common normalised NYT names: it merges plurals, folds "kosher salt" into "salt", and splits compounds.
- **1.4b** The catalogue CSV, with columns `id, name, plural, synonyms, aisleCode, staple, shoppingUnit, displayPolicy, gramsPerCup, packNote, src`.
- **1.4c** A validator, a generated resource file plus a lazy loader, `SOURCES.md`, the NYT LICENSE and NOTICE, and the `REVIEW.md` generator.
- **1.5a** The sync codec:
  - The envelope, ops and paged snapshot formats (§3.6).
  - A size estimator with a configurable payload cap.
  - The page builder, with ack data reserved first and the truncation order applied.
  - `WatchProtocol.kt`, with short keys.
- **1.5b** The pure protocol rules, for both phone and watch, from §3.6.
  - Vectors go in `testdata/sync/*.json`.
  - `tools/sync-model`, a model checker extended to cover the coverage and untick rules, runs in `verify.sh` with small bounds.
- **Check:**
  - Unit tests for the model and the catalogue validator.
  - Codec round-trips, with every page under the cap.
  - Sync vectors:
    - acks: a lost ack followed by a resend; tick → ack → untick; a duplicate arriving after its ack
    - sequence: a gap or reordering in seq; collapsing ops without leaving a gap
    - epoch: an epoch mismatch; a restore caught by the epoch marker
    - coverage: a stale tick → `partial`; need decreased → `noop`; the same item ticked on both phone and watch
    - ids: an op redirected through `ItemAlias`
    - outbox and pages: a full outbox; truncation keeping recent DONE items; a page with an older `listRev` arriving late; missing pages
  - The model checker finds 0 violations.

### Step 2 — Rule parser (small)
- **2.1** Tokeniser, one pass, no regex backtracking. A 10 KB pathological line parses in under 50 ms.
- **2.2** Grammar for qty, unit, alt, name and note, plus flags and section headers. Unhandled patterns are marked `check`.
- **2.3** Normaliser, synonyms and `mergeKey`, shared with Claude's output.
- **Check:** on the **simple + parentheses** strata of the frozen set (lines not held out), name ≥90% and qty ≥95%. The other strata are reported for information only.

### Step 3 — Categoriser and merge
- **3.1** Categoriser (§3.4), including Claude's `aisle_suggestion`.
- **3.2a** Building contributions: scaling, `noScale`, presence lines, and subtracting "I have some".
- **3.2b** Covered and DONE, tick, untick, "Finish shopping".
- **3.2c** Display rounding, the units setting, and the pack note.
- **3.3** `Merger.preview` and the check of the revs shown.
- **3.4** Catalogue re-key and merge, with `ItemAlias`.
- **Check:**
  - **Property tests:**
    - adding then removing a recipe use returns the same list
    - a tick always makes the item DONE
    - a recipe added after a tick is uncovered
    - an untick restores exactly the state before the tick
    - applying the preview equals merging directly
  - **Must-not-merge pairs** stay separate.
  - At least 85% of names in the info set get an aisle other than "Other".

### Step 4 — Claude client (`:claude`)
- **4.1** ADR 001: OkHttp and kotlinx.serialization, no SDK.
- **4.2** Request builder (§3.7): model and effort; the fallbacks rule for each model; the strict schema built from `QtyUnit` and `AisleCode`; the prompt rules; wrapping input as untrusted content; the input cap.
- **4.3** Response handling: `stop_reason`, schema validation, error mapping, usage logged to `ClaudeUsage`, and redaction. Post-processing through the shared normaliser.
- **4.4** Spend control: the monthly and daily caps, and the cost table for each model.
- **4.5** **Live smoke test** using `ANTHROPIC_API_KEY`: one call per model. Responses are redacted and saved as `testdata/claude/live-*.json`.
- **4.6** `tools/eval-claude`: compares Haiku 5.5, Sonnet 5.5, Opus 5.5 and the rule parser on the frozen 80, the 25 UK lines and 15 page fixtures. It reports per-field accuracy, cost and `output_tokens`, and is run once. Synthetic fixtures are named `synthetic-*`.
- **Check:** MockWebServer tests cover every `stop_reason` and every error; the live smoke test passes; the eval report is committed.

### Step 5 — Android app (phone only)
- **5.1** `:extract`:
  - **Safe fetch** (§3.1), with MockWebServer tests for:
    - a redirect to a private IP
    - `file:` URLs
    - a raw IP address
    - a port other than 443
    - a non-HTML response
    - a gzip bomb
  - **Extractors**, with a test for each quirk, plus a page-text fallback for Claude.
  - **Fixtures:**
    - About 15 pages are fetched once, from BBC Good Food, NYT Cooking, a WPRM blog, a Tasty blog, a Yoast `@graph` site and a microdata site.
    - Each is trimmed to its structured block, and its source is recorded with `src=live-YYYY-MM-DD`.
    - A blocked site gets a synthetic fixture instead.
    - `hostile/` holds a 10k-deep array, a 50k-entry ingredient list and an oversized block.
- **5.2** `:data` repository:
  - **5.2a** Schema v1 (§4), with `Callback.onCreate` seeding the aisles, store and staples, plus `Meta` and the epoch marker.
  - **5.2b** The repository: apply preview, tick and untick, manual add, edit quantity.
  - **5.2c** Removing a use, Undo of an import, rescaling, "Finish shopping", and soft delete with undo.
  - **5.2d** Catalogue re-key, and applying watch ops in one transaction.
  - **Check:** an acceptance test for each path. An import followed by Undo leaves the database equal, apart from revs.
- **5.3** App shell:
  - `AppContainer`.
  - WorkManager: `Configuration.Provider` with a factory built from the container. In the manifest, `InitializationProvider` uses `tools:node="merge"` and `WorkManagerInitializer` uses `tools:node="remove"`.
  - Navigation and theme (dynamic colour off in tests), edge-to-edge and predictive back.
  - The import worker, which calls Claude **only while the review screen is in the foreground**.
  - A `WatchLink` fake.
  - **Worker tests:** Robolectric with `@Config(sdk=[34,36])`, `WorkManagerTestInitHelper` with `SynchronousExecutor`, and a test container.
- **5.4–5.8:** one screen per sub-step. Each has a ViewModel test, a Robolectric Compose test with **semantics assertions**, and Roborazzi light and dark recordings.
  - **5.4** Review before adding, default view:
    - each row exposes at most 3 elements
    - in stock, Cancel, and the Undo snackbar
    - recompute when a rev changes
    - the "Parsed by Claude" / "Claude pending" badge
  - **5.5** Review row sheet: I have some, always in stock, swap, move aisle, edit line.
  - **5.6a** The list: grouping; tick and untick with undo; the half-ticked icon; swipe delete (plus the accessibility action); "Finish shopping"; "Clear all".
  - **5.6b** The list, continued:
    - the "Add item" field (autocomplete; Enter keeps the keyboard open; multi-line paste; "+1?")
    - the empty state with the sample list
    - the "ready to review" card
    - share as text
  - **5.7** Item sheet and Recipes tab ("Remove from list", "Add the in-stock items now", "add again?").
  - **5.8** Settings (§3.5 groups), including Claude:
    - the key, "Test key" and the model
    - the caps and the cost counter
    - the privacy note: content goes to Anthropic under your account
    - the key failure modes (§3.7)
- **5.9** Share intent (text only), the paste chip, and the error states. Tested with real payloads shared from Chrome, Firefox, Samsung Internet, Instagram and YouTube.
- **5.10** Debug-only (in the debug source set): "Load sample list", "Fake watch", the debug log viewer, and a **payload probe** placeholder (wired up in Run 2). Backup rules exclude the key file.
- **5.11** `tools/contact-sheet` puts every Roborazzi PNG into one contact-sheet image, using `montage` or Pillow.
- **Check:** `verify.sh` is green; the rolling-release APK link is in `HANDOFF.md`.
- **⏸ Hand-off A [YOU]** (at most 15 yes/no checks in `HANDOFF.md`):
  - Create `main` and merge.
  - Install the APK and enter your key.
  - Share 5 recipes from Chrome and use the review screen.
  - Add 5 items by typing.
  - Turn on airplane mode and import one recipe (the rule-parser fallback).
  - Look at the contact sheet and `REVIEW.md`.
  - Check the 20 held-out lines.
  - Read the eval report and confirm the default model.

## Run 2

### Step 6 — Forerunner 245 app
- **6.0** Prerequisites:
  - `tools/build_watch.sh` fetches SDK 9.2.0 and a throwaway key, and a device-independent strict compile passes.
  - With the secrets set, CI downloads the device definitions (`connect-iq-sdk-manager`) and runs `package_watch.sh`.
  - Record the 245's facts in `garmin/README.md`, including glance support.
- **6.1** `manifest.xml`: `fr245` and `fr245m`, minApiLevel 3.2.0, Communications, and the new UUID. `check_monkeyc_limits.py` passes.
- **6.2** Data layer: a port of the 1.5b watch rules, modelled on `PhoneLink.mc` and `SetQueue.mc`.
  - It covers `nextSeq`, the outbox, back-off, acks, and assembling and ignoring pages.
  - `Protocol.mc` holds the short keys.
- **6.3** UI, buttons only:
  - the aisle list and the `CheckboxMenu` for each aisle
  - debounce, and ticked items sinking
  - the "aisle done" prompt
  - vibration and sub-labels
  - the status screens
  - a glance, if supported
- **6.4** A debug build with an embedded sample of 60 items in 12 aisles, plus the **payload probe** (it sends messages of increasing size and reports the largest that arrived).
- **6.5** Monkey C `(:test)` tests that replay `testdata/sync`, compiled with `build_watch.sh --test`. Running them needs the simulator: [YOU], optional.
- **Check:**
  - The strict compile and the test compile both pass in the container.
  - The parity check passes.
  - CI produces `hypershop-fr245.prg`.
  - `--build-stats 0` stays well under 128 KB.
- **Optional:** a Connect IQ **beta app**, as with BangGym, for cable-free updates.

### Step 7 — Phone ↔ watch sync
- **7.1** `WatchSyncImpl`, modelled on `ConnectIqGarminSync`:
  - Registered in `Application.onCreate`.
  - Uses `WIRELESS` (`TETHERED` in debug), and re-registers when the connection reaches APP_READY or CONNECTED.
  - Handles `<queries>` and the init prompts.
  - Validates every input.
- **7.2** Glue, modelled on `GarminSyncCoordinator`:
  - Always answers `hello` and op batches.
  - Builds pages for each install.
  - Applies ops in a single transaction, using `WatchOpResult`.
  - Applies the clock offset from `watchNow`.
  - Re-sends the last snapshot on `hello` and on `APP_READY`.
- **7.3** A fake `IQDevice` that replays every sync vector end to end through the repository.
- **Check:** `verify.sh` is green.
- **⏸ Hand-off B [YOU]:**
  - **Setup:**
    - Install the watch app and the APK.
    - Run the payload probe and note the limit.
  - **Basic sync:**
    - Import a recipe and open the watch app. The list should appear within 10 s.
    - Tick 3 items using buttons only.
  - **Offline and undo:**
    - Out of range, tick 2 more items. Add a recipe on the phone that also uses one of them, then reconnect. The watch should show "1 more".
    - Undo from the aisle prompt.
  - **Memory:** load the 60-item sample and check memory and speed.

**— End of MVP. —**

## Later (v1.1) — after 2–3 weeks of real use

### Step 8 — Instagram and media
- **8.1** Check the oEmbed claim (ADR 003), then the `og:` fallback.
- **8.2** Caption routing.
- **8.3** `:media`: bundled ML Kit, Photo Picker, image and video share intents, and the "Add media" screen.
- **8.4** OCR for screenshots, using the §3.9 clean-up.
- **8.5** Video sampling with OCR, sent to Claude with images, with a cost estimate first.
- **8.6** The transcript experiment (ADR 004).
- **8.7** Delete temporary media after import.
- **Check:** unit tests for routing, the sampler and the OCR clean-up.
- **[YOU]:** try 10 reels.

### Step 9 — Smart shopping
- **9.1** Store profiles UI, the "Shopping at" chip, `TickEvent`, and `AisleOrderLearner`, plus "Sort like my last trip".
  - **Check:** synthetic trip tests.
- **9.2** Cookbook photos (§3.9).
  - **Check:** 15 or more recorded OCR outputs.
  - **[YOU]:** scan 5 pages.
- **9.3** Meal planner (§3.10): migration, recipe library, week view, combined review.
  - **Check:** repository and migration tests.

### Step 10 — Polish
- **10.1** Icons, and a further accessibility pass.
- **10.2** Benchmarks: parsing and merging 20 recipes takes under 200 ms; a list of 200 items.
- **10.3** A visible WebView fallback, using the safe-fetch address filter, `allowContentAccess=false` and no JavaScript bridges.
- **10.4** A `CustomMenu` prototype on the watch; a "Shopping mode" foreground service if needed.
- **10.5** Voice add: a microphone button on the "Add item" field, using Android's speech recognition.

### Exit criteria for each run
- Every item is either `[x]`, or `[~]`/`[!]` with a reason.
- `verify.sh` is green.
- `HANDOFF.md` has the checklist and the prerequisites for the next run.
- `KNOWN_ISSUES.md` is up to date.

## 7. Risks
- **The agent grading its own work.**
  - The golden set is chosen by script and frozen, with 20 lines held out.
  - Live Claude responses are used, and the eval compares models.
  - Screenshots are recorded but never used as a gate.
  - You review the contact sheet and the catalogue.
- **Relying on Claude:** it needs a network connection and a key.
  - The rule parser is the offline fallback, and anything it can't handle is marked "check".
  - The caps limit cost: at Haiku's prices, a $2 monthly cap covers about 2,000 imports.
- **Haiku accuracy:** this is unmeasured until Step 4.6. If it's not good enough, switch the default to Sonnet 5.5 (about $0.02 per import).
- **Message size on the Forerunner 245** is unknown. Paged snapshots and small batches handle it, and the payload probe measures it at Hand-off B.
- **Glance support** is unknown, so the glance is skipped if the watch doesn't support it.
- **The sync design** has been model-checked on small bounds only. Real devices are tested at Hand-off B.
- **Catalogue entries estimated by the agent** are tagged and listed in REVIEW.md.
- **Blocked recipe sites** go to "Select lines manually" until the v1.1 WebView.
- **Instagram and Meta's terms may change,** and quantities from video will often be estimates.
- **Copyright:** fixtures are trimmed to structured data, and the golden lines come from the Apache-2.0 NYT dataset.

## 8. Later ideas (v2+)
- Shared lists via a sync backend.
- Pantry tracking.
- Home-screen widget.
- whisper.cpp transcripts.
- Claude-assisted substitutions.
- Stock learning, if you find yourself ticking the same items as in stock every time.
