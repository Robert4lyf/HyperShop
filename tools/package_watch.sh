#!/usr/bin/env bash
#
# Builds the watch app for the Connect IQ Store, and for one watch by cable.
#
#   garmin/build/hypershop.iq        every device in the manifest; upload this
#   garmin/build/hypershop-fr245.prg the Forerunner 245 alone; copy to GARMIN/APPS
#
# Unlike tools/build_watch.sh this targets real devices, so it needs two things
# that one does without:
#
#   * The device definitions under ~/.Garmin/ConnectIQ/Devices. The SDK
#     Manager installs them; in CI the release workflow downloads them with
#     connect-iq-sdk-manager. Without them monkeyc fails with "Invalid device
#     id specified", which is also what a genuinely wrong id says.
#
#   * The lifter's own developer key, in CIQ_DEVELOPER_KEY (a path to the DER
#     file). Never a generated one: the store only accepts an update signed
#     with the key the app was first uploaded with, so a build signed with a
#     fresh key is one that can never be uploaded over the last.
#
# The SDK comes from tools/build_watch.sh, which this runs first. That is
# also the device-independent strict compile, so a package is never built
# from sources the check would have refused.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_VERSION="${CIQ_SDK_VERSION:-9.2.0}"
CACHE_DIR="${CIQ_CACHE_DIR:-${HOME}/.cache/connectiq}"
SDK_DIR="${CACHE_DIR}/sdk-${SDK_VERSION}"
DEVICES="${HOME}/.Garmin/ConnectIQ/Devices"
BUILD="${ROOT}/garmin/build"

KEY="${CIQ_DEVELOPER_KEY:-}"
if [[ -z "${KEY}" || ! -f "${KEY}" ]]; then
    echo "CIQ_DEVELOPER_KEY must name your developer key (.der)." >&2
    echo "A generated key would sign a build the store refuses as an update." >&2
    exit 1
fi

for device in $(grep -o '<iq:product id="[^"]*"' "${ROOT}/garmin/manifest.xml" | cut -d'"' -f2); do
    if [[ ! -f "${DEVICES}/${device}/compiler.json" ]]; then
        echo "No device definition for ${device} in ${DEVICES}." >&2
        echo "Install it with the SDK Manager, or connect-iq-sdk-manager device download." >&2
        exit 1
    fi
done

"${ROOT}/tools/build_watch.sh"

mkdir -p "${BUILD}"
cd "${ROOT}/garmin"

# Release builds, optimised: a debug build is several times larger, and the
# 245 gives a watch app 128 KB.
"${SDK_DIR}/bin/monkeyc" --jungles monkey.jungle --private-key "${KEY}" \
    --package-app --release --optimization 2 --warn \
    --output "${BUILD}/hypershop.iq"

"${SDK_DIR}/bin/monkeyc" --jungles monkey.jungle --private-key "${KEY}" \
    --device fr245 --release --optimization 2 --warn \
    --output "${BUILD}/hypershop-fr245.prg"

echo "Built ${BUILD}/hypershop.iq and ${BUILD}/hypershop-fr245.prg"
