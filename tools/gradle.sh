#!/usr/bin/env bash
# Runs ./gradlew in android/ with the container workarounds: one worker while
# dependencies resolve (Maven Central answers parallel fetches with HTTP 429),
# and up to seven retries when a failure is a download failure.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/android"
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT
for attempt in 1 2 3 4 5 6 7 8; do
  ./gradlew --max-workers="${GRADLE_WORKERS:-1}" "$@" 2>&1 | tee "$LOG"
  status=${PIPESTATUS[0]}
  if [ "$status" -eq 0 ]; then exit 0; fi
  if grep -qE 'HTTP 429|Could not resolve|Could not GET|Could not download|Read timed out|Connection reset' "$LOG"; then
    echo "gradle.sh: download failure, retry $attempt after $((attempt * 15))s" >&2
    sleep $((attempt * 15))
    continue
  fi
  exit "$status"
done
exit 1
