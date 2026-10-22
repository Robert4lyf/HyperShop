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
SIM_LOG="${ROOT}/garmin/build/simulator.log"
"${SDK_DIR}/bin/simulator" > "${SIM_LOG}" 2>&1 &
sim=$!
trap 'kill ${sim} 2>/dev/null || true' EXIT

# The simulator takes a while to open its port on a cold runner; monkeydo
# says "Unable to connect" until it has.
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
    sleep 10
    if ! kill -0 "${sim}" 2>/dev/null; then
        echo "The simulator exited:" >&2
        cat "${SIM_LOG}" >&2
        exit 1
    fi
    # Ten minutes is far longer than the suite takes; past it, monkeydo has hung
    # after connecting (seen in CI: connected on attempt 3, then silence), and
    # the simulator's own log is the only account of why.
    status=0
    timeout 600 "${SDK_DIR}/bin/monkeydo" "${OUT}" "${DEVICE}" -t > "${LOG}" 2>&1 || status=$?
    if [[ ${status} -eq 124 ]]; then
        echo "monkeydo connected but never finished (10 min). Its output:" >&2
        cat "${LOG}" >&2
        echo "The simulator's log:" >&2
        cat "${SIM_LOG}" >&2
        # A dialog nobody can click under Xvfb would hold the run here; name what is on screen.
        if command -v xdotool > /dev/null; then
            echo "Windows on the virtual display:" >&2
            for w in $(xdotool search --onlyvisible --name '.' 2> /dev/null); do
                echo "  $(xdotool getwindowname "$w" 2> /dev/null) ($(xdotool getwindowgeometry "$w" 2> /dev/null | tr '\n' ' '))" >&2
            done
        fi
        # The dialog's words, read off the screen (its text is not reachable through X).
        if command -v import > /dev/null && command -v tesseract > /dev/null; then
            import -window root /tmp/simulator-screen.png 2> /dev/null &&
                { echo "Text on the virtual display:" >&2; tesseract /tmp/simulator-screen.png - 2> /dev/null | grep -v '^\s*$' >&2; } || true
        fi
        echo "Connect IQ files:" >&2
        ls -la "${HOME}/.Garmin/ConnectIQ" "${HOME}/.Garmin/ConnectIQ/Devices" "${HOME}/.Garmin/ConnectIQ/Fonts" 2> /dev/null | head -30 >&2 || true
        cat "${HOME}/.Garmin/ConnectIQ/simulator.ini" >&2 2> /dev/null || true
        exit 1
    fi
    if [[ ${status} -eq 0 ]]; then
        break
    fi
    if ! grep -q "Unable to connect" "${LOG}"; then
        break
    fi
    echo "Simulator not ready (attempt ${attempt})"
done
cat "${LOG}"

# monkeydo's exit code does not say whether the tests passed; its report does.
if grep -q "Unable to connect" "${LOG}"; then
    echo "The simulator never accepted a connection. Its log:" >&2
    cat "${SIM_LOG}" >&2
    exit 1
fi
if grep -qE '^(FAIL|ERROR)|FAILED' "${LOG}"; then
    echo "Watch tests failed" >&2
    exit 1
fi
if ! grep -qE 'PASSED' "${LOG}"; then
    echo "No test report from the simulator" >&2
    exit 1
fi
echo "Watch tests passed"
