# Known issues

| # | Area | Issue | Status |
|---|------|-------|--------|
| 1 | Env | `ANTHROPIC_API_KEY` is not set in the Run 1 cloud container (only `ANTHROPIC_BASE_URL`). Steps 4.5 (live smoke test) and 4.6 (eval) cannot call the API. | open |
| 2 | Build | Maven Central returns HTTP 429 to this container when Gradle resolves in parallel. Workaround: `--max-workers=1` and retry (verify.sh retries). | workaround |
| 3 | Build | ktlint-gradle 12.1.2, with Gradle's configuration/build caches, keeps reporting errors in deleted or renamed files. `verify.sh` runs `ktlintCheck --rerun-tasks --no-configuration-cache --no-build-cache` on its own. | workaround |
| 4 | Tests | Robolectric 4.16.1 native SQLite cannot open databases for two SDKs (34 and 36) in one test JVM (`UnsatisfiedLinkError` / `FileSystemAlreadyExistsException`). Tests that run `@Config(sdk = [34, 36])` use `@SQLiteMode(LEGACY)`. | workaround |
