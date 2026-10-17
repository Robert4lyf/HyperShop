# HyperShop

A personal Android shopping-list app. Share a recipe URL; Claude reads the
ingredients; you review them, tick what you already have, and the rest merges
into a list sorted by aisle. A Forerunner 245 companion app (Run 2) mirrors the
list so it can be ticked from the wrist.

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
