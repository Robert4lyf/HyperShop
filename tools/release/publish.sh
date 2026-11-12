#!/usr/bin/env bash
set -euo pipefail
sha=$(python3 tools/release/check_verified.py "$RUN_ID")
[ "$sha" = "$EXPECTED_SHA" ]
# Published tags are immutable. A rerun is a no-op, never a delete/re-upload.
if gh release view "$TAG" --json isDraft --jq '.isDraft' 2>/dev/null | grep -qx false; then
  echo "Already published $TAG"; exit 0
fi
{
  printf 'Version/build %s · commit `%s`\n\n' "$(git rev-list --count HEAD)" "$sha"
  printf 'Verified Android run: https://github.com/%s/actions/runs/%s\n\n' "$GITHUB_REPOSITORY" "$RUN_ID"
  printf 'Fixed debug signing: updates existing .debug installations without uninstalling.\n\n'
  if [ "$WATCH_READY" != true ]; then printf 'Watch package unavailable: Garmin credentials not configured. Existing companion remains compatible.\n\n'; fi
  printf 'Recent changes:\n'
  git log -5 --format='- %s (%h)'
} > notes.md
sha256sum hypershop-debug.apk > SHA256SUMS
assets=(hypershop-debug.apk SHA256SUMS)
if [ "$WATCH_READY" = true ]; then
  assets+=(garmin/build/hypershop.iq garmin/build/hypershop-fr245.prg garmin/build/hypershop-fr245-debug.prg)
fi
# Any failure leaves only a draft; the known-good latest release stays available.
if ! gh release view "$TAG" >/dev/null 2>&1; then
  gh release create "$TAG" --draft --target "$sha" --title "Build $(git rev-list --count HEAD)" --notes-file notes.md
fi
gh release upload "$TAG" "${assets[@]}" --clobber
sha=$(python3 tools/release/check_verified.py "$RUN_ID")
[ "$sha" = "$EXPECTED_SHA" ]
gh release edit "$TAG" --draft=false --latest --notes-file notes.md
# Keep historical release assets for rollback (at least five previous builds).
# These consume release storage, not the limited Actions artifact allowance.
