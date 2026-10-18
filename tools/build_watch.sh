#!/usr/bin/env bash
#
# Compiles the Connect IQ app, so the Monkey C half of this repo is built
# rather than only read.
#
# It had never been compiled anywhere — not in CI, not in the dev container —
# and the first session on real hardware found five bugs. This does not catch
# the kind of bug that needs a wrist, but it catches every undefined symbol,
# bad type and unreachable statement, in seconds, which is the class of thing
# that should never have reached a watch in the first place.
#
# No Garmin account is needed, which is what the old "requires accepting
# Garmin's licence and a developer key" note assumed:
#
#   * the SDK zip is a plain unauthenticated download;
#   * a developer key is an ordinary RSA key, generated here with openssl. It
#     signs the build for sideloading, and nothing checks who made it. A real
#     key belongs in a secret only when we start publishing to the store.
#
# What this does not have is the device *definitions*. They are a separate
# download from the SDK zip — no login, but through the SDK Manager's Devices
# tab, which is a desktop GUI rather than anything a runner can call — and they
# install under ~/.Garmin/ConnectIQ/Devices. Without them monkeyc compiles
# device-independently: it type checks everything and warns that the manifest's
# device ids are unknown. Those two warnings are expected, and the only ones
# tolerated. Pass -d fr245 without the definitions and the build fails outright
# with "Invalid device id specified".
#
# So the .prg this leaves behind is a compile artifact, not a build for a
# watch: nothing here has resolved a screen shape or a layout. Sideloading
# still means -d fr245 on a machine with the definitions installed. See
# docs/garmin-setup.md.
#
# Pass --test to build the unit tests as well (test.jungle, monkeyc -t). That
# compiles and type checks every assertion against the code it describes, which
# is what stops a test drifting away from a rename. It does NOT run them:
# executing Monkey C needs the simulator, and the simulator needs the device
# definitions this environment does not have. To actually run them, on a
# machine where the SDK Manager has installed a device:
#
#   monkeyc -f garmin/test.jungle -o /tmp/test.prg -y "$KEY" -d fr245 -t
#   connectiq &
#   monkeydo /tmp/test.prg fr245 -t
#
set -euo pipefail

SDK_VERSION="${CIQ_SDK_VERSION:-9.2.0}"
CACHE_DIR="${CIQ_CACHE_DIR:-${HOME}/.cache/connectiq}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${CACHE_DIR}/sdk-${SDK_VERSION}"
KEY="${CIQ_DEVELOPER_KEY:-${CACHE_DIR}/developer_key.der}"
OUT="${ROOT}/garmin/build/hypershop.prg"

# The app, or the app plus its tests.
JUNGLE="monkey.jungle"
UNIT_TEST=()
if [[ "${1:-}" == "--test" ]]; then
    JUNGLE="test.jungle"
    UNIT_TEST=(--unit-test)
    OUT="${ROOT}/garmin/build/hypershop-test.prg"
fi

mkdir -p "${CACHE_DIR}"

if [[ ! -x "${SDK_DIR}/bin/monkeyc" ]]; then
    echo "Fetching Connect IQ SDK ${SDK_VERSION}"
    manifest="${CACHE_DIR}/sdks.json"
    curl -sSfL --max-time 120 -o "${manifest}" \
        "https://developer.garmin.com/downloads/connect-iq/sdks/sdks.json"
    archive="$(python3 -c "
import json, sys
wanted = sys.argv[1]
for entry in json.load(open(sys.argv[2])):
    if entry['version'] == wanted:
        print(entry['linux'])
        break
else:
    sys.exit(f'no Linux SDK for {wanted}')
" "${SDK_VERSION}" "${manifest}")"
    curl -sSfL --max-time 900 -o "${CACHE_DIR}/${archive}" \
        "https://developer.garmin.com/downloads/connect-iq/sdks/${archive}"
    rm -rf "${SDK_DIR}"
    mkdir -p "${SDK_DIR}"
    unzip -q "${CACHE_DIR}/${archive}" -d "${SDK_DIR}"
    chmod +x "${SDK_DIR}/bin/"* || true
    rm -f "${CACHE_DIR}/${archive}"
fi

if [[ ! -f "${KEY}" ]]; then
    echo "Generating a developer key"
    openssl genrsa -out "${CACHE_DIR}/developer_key.pem" 4096 2>/dev/null
    openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt \
        -in "${CACHE_DIR}/developer_key.pem" -out "${KEY}"
fi

mkdir -p "$(dirname "${OUT}")"
log="$(mktemp)"
trap 'rm -f "${log}"' EXIT

# -l 3 is the strictest type check the compiler offers; -w turns on warnings.
# Run from garmin/ because the jungle's paths are relative to it.
set +e
(cd "${ROOT}/garmin" && "${SDK_DIR}/bin/monkeyc" \
    --output "${OUT}" \
    --jungles "${JUNGLE}" \
    "${UNIT_TEST[@]+"${UNIT_TEST[@]}"}" \
    --private-key "${KEY}" \
    --typecheck 3 \
    --warn) >"${log}" 2>&1
status=$?
set -e

# JAVA_TOOL_OPTIONS announces itself on stderr and is not ours.
grep -v '^Picked up JAVA_TOOL_OPTIONS' "${log}" || true

if [[ ${status} -ne 0 ]]; then
    echo "monkeyc failed (${status})" >&2
    exit "${status}"
fi

# Everything except the two device-id warnings, which cannot be resolved
# without the device definitions, and those are not installed here.
unexpected="$(grep '^WARNING' "${log}" | grep -v 'Invalid device id found in the application manifest' || true)"
if [[ -n "${unexpected}" ]]; then
    echo "" >&2
    echo "Unexpected warnings:" >&2
    echo "${unexpected}" >&2
    exit 1
fi

# The app is also compiled --release: (:debug) and (:release) code differ
# (garmin/source/Debug.mc), and each half must type check on its own.
if [[ ${#UNIT_TEST[@]} -eq 0 ]]; then
    set +e
    (cd "${ROOT}/garmin" && "${SDK_DIR}/bin/monkeyc" \
        --output "${ROOT}/garmin/build/hypershop-release.prg" \
        --jungles "${JUNGLE}" \
        --private-key "${KEY}" \
        --release \
        --typecheck 3 \
        --warn) >"${log}" 2>&1
    status=$?
    set -e
    grep -v '^Picked up JAVA_TOOL_OPTIONS' "${log}" | grep -v 'Invalid device id found' || true
    if [[ ${status} -ne 0 ]]; then
        echo "monkeyc --release failed (${status})" >&2
        exit "${status}"
    fi
    unexpected="$(grep '^WARNING' "${log}" | grep -v 'Invalid device id found in the application manifest' || true)"
    if [[ -n "${unexpected}" ]]; then
        echo "Unexpected warnings (release):" >&2
        echo "${unexpected}" >&2
        exit 1
    fi
fi

echo "Built ${OUT}"
