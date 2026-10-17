# Known issues

| # | Area | Issue | Status |
|---|------|-------|--------|
| 1 | Env | `ANTHROPIC_API_KEY` is not set in the Run 1 cloud container (only `ANTHROPIC_BASE_URL`). Steps 4.5 (live smoke test) and 4.6 (eval) cannot call the API. | open |
| 2 | Build | Maven Central returns HTTP 429 to this container when Gradle resolves in parallel. Workaround: `--max-workers=1` and retry (verify.sh retries). | workaround |
