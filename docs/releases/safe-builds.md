# Verified builds and signing

The rolling download remains `releases/latest/download/hypershop-debug.apk`. It remains the `.debug` package signed with the existing committed debug key; existing installations keep their data and update normally. Neither package nor signing identity has been silently changed.

Android CI uploads one temporary APK artifact, only for main pushes, retained for two days. Release consumes those bytes by run ID, checks the current main SHA, the latest push runs and current attempts of Android and Connect IQ, and requires the named build, emulator and watch compile jobs to succeed. A missing, failed, cancelled, skipped, superseded or expired input stops promotion. Manual release requires the same successful main-push Android run ID; it cannot build an unverified ref.

Watch packaging checks out that same SHA and completes before publication. If Garmin credentials are configured but packaging fails, no release is promoted. Without credentials, notes explicitly state that no new watch package is included. Existing protocol/app IDs are unchanged. All assets are uploaded to a draft; only a final verification can make it latest. A failed attempt leaves the known-good latest release intact. Published tags are immutable on reruns. Historical successful releases are retained, including at least the previous five once they exist; earlier releases deleted by the old workflow cannot be recovered by this change.

To retry after both checks finish, run **Release APK** with the Android push run's numeric ID. Expired APK artifact: rerun **the original push run** in Actions to repeat verification and recreate it. The Android manual verification workflow is not eligible for publication. Do not delete the known-good release as a workaround.

## Private release setup

Privately signed builds are prepared but do not replace the debug download. Set these only in a secure local environment or appropriately protected CI secrets:

- `HYPERSHOP_RELEASE_KEYSTORE`: absolute keystore path, outside the repository.
- `HYPERSHOP_RELEASE_STORE_PASSWORD`
- `HYPERSHOP_RELEASE_KEY_ALIAS`
- `HYPERSHOP_RELEASE_KEY_PASSWORD`

Run `cd android && ./gradlew :app:assembleRelease`. Without private signing configuration the output is unsigned, never signed with the public debug key. Partial configuration fails rather than silently choosing another signing identity. Retain a secure offline copy of the private key; loss prevents future updates with that identity. Never commit it, its passwords, or a `local.properties` file containing them.

The private release package is `com.robert4lyf.hypershop`; the existing debug app is `com.robert4lyf.hypershop.debug`. Android cannot update one package/signature with the other. Safe transition: export a manual backup from debug, keep debug installed, install private release alongside it, restore the backup, check recipes/photos/list/history/settings, re-enter the optional Claude key, and verify Garmin connectivity. Keep the old installation until those checks succeed. Do not try to sign the debug package with the private key or uninstall it to resolve a signature error; uninstalling loses its private app data. Android-managed restore is not a replacement for this explicit transition.

Voice-test service, microphone permissions and harness code are confined to debug sources. Release has a navigation facade that immediately exits an accidentally restored voice-test route. The debug application's harness and signing identity are retained.
