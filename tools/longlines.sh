#!/usr/bin/env bash
# Lists Kotlin lines over ktlint's 140-character limit (a fast pre-check before verify.sh).
cd "$(dirname "$0")/../android" && grep -rn --include='*.kt' --include='*.kts' '.\{141,\}' . | grep -v '/build/' || true
