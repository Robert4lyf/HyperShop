# ADR 000 — BangGym is the build template

**Status:** accepted (Step 0.3)

## Context
`robert4lyf/BangGym` is a working Android + Forerunner 245 app whose stack
already builds in CI. Picking fresh versions would repeat its compatibility
work.

## Decision
Copy BangGym's build setup at commit
`6a4b9d18e345f253c421a3ac9101bec371367dad` (main, "Merge pull request #59").

Copied:
- Gradle wrapper 9.7.1, root `build.gradle.kts`, `settings.gradle.kts`,
  `gradle.properties` (KSP2, `android.builtInKotlin=false`, `android.newDsl=false`),
  `gradle/libs.versions.toml` (AGP 9.4.0, Kotlin 2.2.10, KSP 2.3.6, Room 2.7.1,
  Compose BOM 2024.11.00, ktlint-gradle 12.1.2, Robolectric 4.14.1,
  ciq-companion-app-sdk 2.4.0). Health Connect removed.
- `.editorconfig` (ktlint Composable naming opt-out).
- Committed debug keystore and its signing config.
- Manual DI (`AppContainer` on the `Application`).
- Workflows `android.yml`, `connectiq.yml`, `release.yml`, renamed to HyperShop;
  `android.yml` runs `tools/verify.sh`.
- `tools/build_watch.sh`, `package_watch.sh`, `check_protocol_parity.py`
  (paths adapted to `WatchProtocol.kt`), `check_monkeyc_limits.py`.
- `garmin/` skeleton: `manifest.xml` layout (new app UUID
  `41c79afd24ac47168df768ef92d1bac0`, Communications only), `monkey.jungle`,
  `test.jungle`.
- `CLAUDE.md` conventions, adapted.

HyperShop differences: Gradle root is `android/` (multi-module), minSdk 34,
targetSdk 36, `buildToolsVersion = "36.0.0"`, versionName from
`git describe --tags --always`.

## Consequences
Version bumps follow BangGym's practice: deliberate and reviewed. Versions are
frozen after Step 0.4.

## Step 0.4 addendum — HyperShop libraries, frozen
Chosen as the newest that build with AGP 9.4.0 / Kotlin 2.2.10, each proven by a
hello test, then frozen:

| Library | Version | Hello test |
|---|---|---|
| OkHttp + mockwebserver | 4.12.0 | `:extract` HelloMockWebServerTest |
| Jsoup | 1.23.2 | (used from Step 5.1) |
| kotlinx-serialization-json | 1.9.0 (1.10+ targets Kotlin 2.3) | compiled in every JVM module |
| WorkManager (+ work-testing) | 2.11.2 | (worker tests in 5.3) |
| DataStore | 1.1.1 (BangGym's) | — |
| JUnit Jupiter | 5.13.4 | `:core` HelloTest |
| kotest-property | 6.0.7 | `:core` HelloTest |
| Roborazzi (+ compose, junit-rule, plugin) | 1.76.0 | `:app` HelloComposeTest (`@GraphicsMode(NATIVE)`, `roborazzi.test.record=true`) |
| Compose ui-test-junit4 | from BOM | `:app` HelloComposeTest (semantics) |
| Timber | 5.0.1 | — |

One BangGym version changed: **Robolectric 4.14.1 → 4.16.1**, because 4.14 has
no SDK 36 runtime and the plan's worker tests run `@Config(sdk = [34, 36])`
(`AppContainerTest` proves both).

The BangGym stack itself built in the container, so the §2 fallback was not
needed.
