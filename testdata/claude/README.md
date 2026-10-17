# Claude responses

- `synthetic-*.json` — hand-written Messages API responses used by tests.
- `live-*.json` — redacted results of the Step 4.5 live smoke test, one per model.
  Not yet recorded: `ANTHROPIC_API_KEY` was not available in the Run 1 container
  (docs/KNOWN_ISSUES.md #1). Run `tools/eval-claude/run.sh` with a key to record them.
