# ADR 001 — Claude over OkHttp and kotlinx.serialization, no SDK

**Status:** accepted (Step 4.1)

## Context
HyperShop makes one `POST /v1/messages` call per import (plus a free
`GET /v1/models` for "Test key"). It already ships OkHttp (safe fetch) and
kotlinx.serialization. The Anthropic Java SDK would add a large dependency
tree, its own HTTP stack and Jackson to an Android app whose whole Claude
surface is one request shape.

## Decision
`:claude` calls the Messages API directly:
- `POST {base}/v1/messages` with `x-api-key`, `anthropic-version: 2023-06-01`,
  `content-type: application/json`.
- `output_config.effort: "low"` always set explicitly (Claude Haiku 5.5 defaults
  to `medium`); `thinking` is never sent, so adaptive thinking stays on.
- `output_config.format: {type: "json_schema", schema}` with the strict schema
  built from `QtyUnit` and `AisleCode` (all fields required, nullable via
  `anyOf`, `additionalProperties: false`). Numeric ranges are checked in Kotlin:
  the schema language here does not support `minimum`/`maximum`.
- `fallbacks: "default"` + `anthropic-beta: server-side-fallback-2026-07-01`
  only for Claude Opus 5.5 and Claude Sonnet 5.5; Claude Haiku 5.5 has no
  server-side fallback.
- `stop_reason` is checked before `content`: `refusal` → rule-parser fallback,
  `max_tokens` → error.
- The base URL is injectable so MockWebServer tests drive every path.

## Consequences
No SDK retries: back-off, the single 5xx retry and offline detection are ours
(`ClaudeClient`). New API features need hand-written request fields.
