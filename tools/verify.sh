#!/usr/bin/env bash
# The gate (PLAN.md §6 rule 3). Must pass before every commit.
#
#   1. ktlint, every unit test, :app:assembleDebug
#   2. the merged manifest: no CAMERA; only the launcher and share activities exported
#   3. no Anthropic key committed (sk-ant- scan)
#   4. the golden CHECKSUM
#   5. phone/watch protocol parity, once both protocol files exist
#   6. the sync model checker, once it exists
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/android-sdk" ]; then
  export ANDROID_HOME="$HOME/android-sdk"
fi
if [ ! -f android/local.properties ] && [ -n "${ANDROID_HOME:-}" ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties
fi

step() { printf '\n== %s\n' "$*"; }

step "Gradle: ktlint, unit tests, assembleDebug"
tools/gradle.sh \
  ktlintCheck \
  :core:test :extract:test :claude:test \
  :data:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug

step "Merged manifest"
python3 tools/check_manifest.py

step "Secrets scan"
# A real key is sk-ant- followed by a long token; the bare prefix appears in
# docs and in the redaction code on purpose.
if git ls-files -z --cached --others --exclude-standard \
  | xargs -0 grep -IlE 'sk-ant-[A-Za-z0-9_-]{20,}' 2> /dev/null; then
  echo "FAIL: something that looks like an Anthropic key is in the tree" >&2
  exit 1
fi
echo "no keys found"

step "Golden checksum"
if [ -f testdata/ingredients/CHECKSUM ]; then
  (cd testdata/ingredients && sha256sum --quiet -c CHECKSUM)
  echo "golden data matches CHECKSUM"
else
  echo "no golden data yet"
fi

step "Protocol parity"
if [ -f garmin/source/Protocol.mc ] && [ -f android/core/src/main/kotlin/com/robert4lyf/hypershop/core/sync/WatchProtocol.kt ]; then
  python3 tools/check_protocol_parity.py
else
  echo "skipped: garmin/source/Protocol.mc arrives in Run 2"
fi

step "Sync model checker"
if [ -f tools/sync-model/model.py ]; then
  python3 tools/sync-model/model.py --small
else
  echo "skipped: not written yet"
fi

printf '\nverify.sh: all checks passed\n'
