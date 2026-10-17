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
