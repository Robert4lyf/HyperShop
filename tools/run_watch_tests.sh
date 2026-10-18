#!/usr/bin/env bash
#
# Runs the Monkey C unit tests (garmin/test) in the Connect IQ simulator,
# headless under Xvfb. Needs the SDK (tools/build_watch.sh fetches it), the
# fr245 device definition and the simulator's libraries; CI's simulator job
# in connectiq.yml sets all three up. Fails unless every test passed.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_VERSION="${CIQ_SDK_VERSION:-9.2.0}"
CACHE_DIR="${CIQ_CACHE_DIR:-${HOME}/.cache/connectiq}"
SDK_DIR="${CACHE_DIR}/sdk-${SDK_VERSION}"
DEVICE="${WATCH_TEST_DEVICE:-fr245}"
OUT="${ROOT}/garmin/build/hypershop-test-${DEVICE}.prg"
LOG="${ROOT}/garmin/build/watch-tests.log"

# The strict device-independent compile first, which also fetches the SDK.
"${ROOT}/tools/build_watch.sh" --test

KEY="${CIQ_DEVELOPER_KEY:-${CACHE_DIR}/developer_key.der}"
(cd "${ROOT}/garmin" && "${SDK_DIR}/bin/monkeyc" --jungles test.jungle \
    --private-key "${KEY}" --device "${DEVICE}" --unit-test --output "${OUT}")

if [[ -z "${DISPLAY:-}" ]]; then
    Xvfb :99 -screen 0 1024x768x24 > /dev/null 2>&1 &
    export DISPLAY=:99
    sleep 2
fi
"${SDK_DIR}/bin/connectiq" > /dev/null 2>&1 &
sim=$!
trap 'kill ${sim} 2>/dev/null || true' EXIT
sleep 10

timeout 600 "${SDK_DIR}/bin/monkeydo" "${OUT}" "${DEVICE}" -t 2>&1 | tee "${LOG}"

# monkeydo's exit code does not say whether the tests passed; its report does.
if grep -qE '^(FAIL|ERROR)|FAILED' "${LOG}"; then
    echo "Watch tests failed" >&2
    exit 1
fi
if ! grep -qE 'PASSED' "${LOG}"; then
    echo "No test report from the simulator" >&2
    exit 1
fi
echo "Watch tests passed"
