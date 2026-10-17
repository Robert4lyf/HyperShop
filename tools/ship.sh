#!/usr/bin/env bash
# verify.sh, then commit everything staged by the caller's paths, then push. Stops at the first failure.
# Usage: tools/ship.sh "<message>" <paths...>
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
msg="$1"; shift
LOG="${TMPDIR:-/tmp}/verify-$$.log"
if ! tools/verify.sh > "$LOG" 2>&1; then
  grep -E 'kt:|FAIL|What went wrong|SIGSEGV|  - ' -A2 "$LOG" | head -30
  echo "ship.sh: verify.sh failed; nothing committed" >&2
  exit 1
fi
git add -A "$@"
git commit -q -m "$msg

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01BoU4JwQb5JoTziFgXJwbZA"
git push -q 2>&1 | tail -1 || true
git log --oneline -1
