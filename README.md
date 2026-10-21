# HyperShop

A personal Android shopping-list app. Share a recipe link (or type, paste or
photograph one); HyperShop reads the ingredients on the phone and asks Claude,
with your own key, only for a line it isn't sure of or a photo. Review them,
mark what you already have at home, and the rest merges into one list sorted by
aisle. Plan on the List tab, then tap **Shop** for a one-handed screen that
walks the aisles. Saved recipes become the week's meals in two taps. Everything
stays on the phone; Settings › Data backs it up to a file you choose. A
Forerunner 245 companion app mirrors the list so it can be ticked from the
wrist, offline too.

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
