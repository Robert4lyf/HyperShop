# HyperShop

Personal Android shopping-list app (Kotlin/Compose/Room) with a Forerunner 245
companion (Monkey C, Run 2). Share a recipe; Claude parses the ingredients;
review, tick what is in stock, merge into an aisle-sorted list. Local-first:
no backend. The only network calls are fetching recipe pages (and a saved
recipe's own photo, through the same `SafeFetcher` checks) and calling Claude
with your own key.

Design: `PLAN.md`. Where the build is: `PROGRESS.md`. Conventions copied from
`robert4lyf/BangGym` (see `docs/decisions/000-template.md`).

## Layout

| Path        | What |
| ----------- | ---- |
| `android/`  | Gradle build. `:core` (pure JVM model, catalogue, rule parser, merge, sync codec), `:data` (Room), `:extract` (safe fetch + extractors), `:claude` (Claude client), `:app` |
| `garmin/`   | Connect IQ watch app (Run 2) |
| `catalog/`  | Ingredient catalogue CSVs + sources |
| `testdata/` | Golden data (frozen), page fixtures, sync vectors, Claude responses |
| `tools/`    | `verify.sh` (the gate) and helpers |
| `docs/`     | ADRs, specs, hand-off |

## Commands

```bash
tools/verify.sh                          # the gate; must be green before every commit
cd android && ./gradlew test             # unit tests (all modules)
cd android && ./gradlew :app:assembleDebug
python3 tools/check_protocol_parity.py   # once garmin/source/Protocol.mc exists
```

Maven Central rate-limits this container (HTTP 429). Use `--max-workers=1`
and retry; `verify.sh` already does.

## Agent rules (PLAN.md §6)

- Resume from `PROGRESS.md` at the first unchecked item of the current run.
  Never redo checked items, never start the next run.
- One commit per sub-step, titled `[<step id>] …`, with `PROGRESS.md` updated
  in the same commit.
- Versions are frozen (`android/gradle/libs.versions.toml`). Problems go in
  `docs/KNOWN_ISSUES.md`, not in a bump.
- **Golden data is never edited to make a test pass.** A real fix is its own
  `golden-fix:` commit touching nothing under `android/`, with `CHECKSUM` and
  `testdata/ingredients/CHANGES.md` updated. At most 3 per run.
- Never print `HYPERSHOP_ANTHROPIC_API_KEY`. `verify.sh` scans for `sk-ant-`.

## Rules that are easy to get wrong

- **Every write goes through the repository** in `:data`. ViewModels never
  touch a DAO.
- **Nothing touches the list before start-up is `Ready`** (`AppContainer.startup`).
  A restore the process died in is finished first, then the watch epoch and
  the catalogue re-key. The UI shows `StartupScreen`, the watch coordinator
  holds its inbox, and shares and import workers `awaitReady()`. New
  background entry points must wait the same way.
- **A planned meal is not a recipe use.** `PlannedMeal` is the plan; its
  shopping is the use it is linked to (`useId`). Only `Planner` puts a meal on
  the list, through the review's own `apply`, linking it in the same
  transaction. A finished trip leaves the plan alone. An import plans itself
  for this week, and an edit relinks: keep `apply`'s `plan` and `replacing`
  paths doing that.
- **Nothing in Release B undoes a purchase.** Removing a meal, Undo of a week's
  add, Buy again's Undo and "Have it" take back only unbought shares
  (`ShoppingList.withdraw`). Never call `removeUse` on a use with a covered
  contribution to "remove a meal".
- **A review line is known by its occurrence, never its text.** A recipe can say
  "100 g sugar" twice. Corrections and removals are filed by `LineOrigin`
  (source text + occurrence) and laid over Claude by `LineIdentity.reconcile`,
  which never moves a correction it cannot line up. `rawLines` is what Claude
  reads and stays as the recipe wrote it.
- **A saved recipe's rating, notes and photo belong to its library entry**
  (`RecipeNote`, by `LibraryRecipe.key`), not to a copy: Edit and Add again
  make new copies. A photo is a bare file name in `RecipePhotos`' folder,
  re-encoded by the app, never a path and never the bytes as fetched.
- **Room enums are stored by name**, never ordinal.
- **An item's amount is the sum of its contributions.** Never store a total.
  A row marked in stock on review adds nothing.
- **Ticks never create stock.** A tick covers exactly the contributions that
  existed at that moment; later ones start uncovered.
- **`Item.rev` is bumped in the same transaction** as any change to the
  item's contributions, ticks, aisle, the units setting, or the catalogue.
- **The wire protocol is declared twice** — `WatchProtocol.kt` (in `:core`)
  and `garmin/source/Protocol.mc`. Change both. The watch app id lives in
  `WatchProtocol.WATCH_APP_ID` and `garmin/manifest.xml`.
- **Do not bump the protocol version for an additive key or message type**
  (BangGym's rule). Both sides drop a payload whose `v` differs.
- **Watch input is untrusted:** check types and ranges, cap sizes, and never
  put it into SQL strings or paths.
- **Claude is only called while the review screen is in the foreground.** A
  share intent on its own never spends money.
- **No `CAMERA` permission**, and only the launcher and share activities are
  exported. `verify.sh` checks the merged manifest.
- **No Bluetooth permissions.** The Connect IQ SDK binds to Garmin Connect.
- **`RECORD_AUDIO` is for voice only** (Settings › Advanced › Voice test in
  debug builds, voice mode later): a non-exported `microphone` foreground
  service, started from the screen, never storing audio.
