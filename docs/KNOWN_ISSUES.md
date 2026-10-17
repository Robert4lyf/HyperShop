# Known issues

| # | Area | Issue | Status |
|---|------|-------|--------|
| 1 | Env | `ANTHROPIC_API_KEY` is not set in the Run 1 cloud container (only `ANTHROPIC_BASE_URL`). Steps 4.5 (live smoke test) and 4.6 (eval) cannot call the API. | open |
| 2 | Build | Maven Central returns HTTP 429 to this container when Gradle resolves in parallel. Workaround: `--max-workers=1` and retry (verify.sh retries). | workaround |
| 3 | Build | ktlint-gradle 12.1.2, with Gradle's configuration/build caches, keeps reporting errors in deleted or renamed files. `verify.sh` runs `ktlintCheck --rerun-tasks --no-configuration-cache --no-build-cache` on its own. | workaround |
| 4 | Tests | Robolectric 4.16.1 native SQLite cannot open databases for two SDKs (34 and 36) in one test JVM (`UnsatisfiedLinkError` / `FileSystemAlreadyExistsException`). Tests that run `@Config(sdk = [34, 36])` use `@SQLiteMode(LEGACY)`. | workaround |
| 5 | Share | Step 5.9 asks for real payloads shared from Chrome, Firefox, Samsung Internet, Instagram and YouTube. There is no device here, so `SharedTextTest` uses payloads reconstructed in each app's known shape. Hand-off A checks real shares. | open |
| 6 | Tests | Room's background invalidation refresh could outlive a Robolectric test and fail the next one (`Illegal connection pointer`). Test databases run Room's query executor on the calling thread, and tests use a plain `TestApplication` (the real one opened the on-disk database and started work in every test); `AppContainerTest` opts back into the real one. | fixed |
| 7 | Keys | The Android Keystore isn't available under Robolectric, so `KeystoreApiKeyStoreTest` runs the same AES-GCM code with a plain JCA key. The real Keystore path is checked on a device (Hand-off A: enter your key). | open |
