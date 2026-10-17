#!/usr/bin/env bash
# Step 4.6: runs the Claude eval once and writes docs/reports/eval-claude.md.
# Needs ANTHROPIC_API_KEY (a dedicated key with a spend limit). Costs about $0.10.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
  echo "ANTHROPIC_API_KEY is not set" >&2
  exit 1
fi
cd "$ROOT"
tools/gradle.sh :claude:test --tests '*ClaudeEvalTest*' --tests '*LiveSmokeTest*' -Deval=true --rerun
echo "Report: docs/reports/eval-claude.md; live responses: testdata/claude/live-*.json"
