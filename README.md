# HyperShop

A personal Android shopping assistant. Share a recipe link, or type, paste or
photograph one. HyperShop reads text ingredients on the phone and can optionally
ask Claude with your own key. Review and add the ingredients, check what you
already have for this shop, then buy the remainder in an aisle-sorted list.
Prepare meals on **This week**, or add items directly on **Shopping**; either can
start **Shop** mode. Saved recipes and personal cooking notes remain available
offline. Settings › Data exports a manual backup to a file you choose. A
Forerunner 245 companion mirrors the list for offline wrist ticking too.

See [PLAN.md](PLAN.md) for the design and [PROGRESS.md](PROGRESS.md) for where
the build is.

## Layout

| Path       | What |
| ---------- | ---- |
| `android/` | Gradle build: `:app`, `:core`, `:data`, `:extract`, `:claude` |
| `garmin/`  | Connect IQ watch app (Run 2) |
| `catalog/` | Ingredient catalogue and its sources |
| `testdata/`| Golden lines, page fixtures, sync vectors, Claude responses |
| `tools/`   | `verify.sh` and helper scripts |
| `docs/`    | ADRs, specs, hand-off notes |

## Commands

```bash
tools/verify.sh                      # the gate: ktlint, tests, assembleDebug, repo checks
cd android && ./gradlew test         # all unit tests
cd android && ./gradlew :app:assembleDebug
```

The Android SDK (API 36) must be on `ANDROID_HOME` or in `android/local.properties`.
In a Claude Code cloud container, `.claude/hooks/session-start.sh` installs it.

## Everyday shortcuts

- Correct an item from its details: name and total requirement first, Save at the bottom; expand aisle/category preferences only when needed.
- In This week, check cupboard ingredients or include fridge, freezer and fresh food. Partial home amounts reduce recipe requirements; manual extras remain separate.
- Configure store names and walking order in Settings. Both shopping entry points use the same store chooser, with independent shelf arrangements and optional usual pack sizes.
- Add the HyperShop home-screen widget for a short list preview and quick add. Shopping's menu can share an aisle-grouped plain-text list.
- Saved recipes offer Cooking view for saved ingredients and personal notes. Backup settings show the last successful manual export and photo coverage.

[Verified releases and signing](docs/releases/safe-builds.md) · [Implementation coverage and limitations](docs/reports/2026-11-improvements.md) · [Real-device acceptance](docs/reports/2026-11-acceptance.md)
