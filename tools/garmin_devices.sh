#!/usr/bin/env bash
#
# Downloads the Connect IQ device definitions for garmin/manifest.xml into
# ~/.Garmin/ConnectIQ/Devices, for the watch builds in CI (release.yml and
# connectiq.yml). They are the one part of the toolchain behind a Garmin
# login. connect-iq-sdk-manager is a community CLI that drives the same
# downloads as the SDK Manager's Devices tab; its installer checks the
# release's sha256.
#
# How it signs in, in order:
#
#   GARMIN_TOKEN   A saved sign-in: base64 of the token.json that
#                  tools/garmin_token.sh makes on your own computer, where a
#                  two-step code can be typed. Works with two-step verification
#                  on. It runs out after a time Garmin sets; the build warns
#                  three weeks ahead, and running tools/garmin_token.sh again
#                  renews it.
#   GARMIN_USERNAME and GARMIN_PASSWORD
#                  A password sign-in. Fails on an account with two-step
#                  verification ("unknown reason for login failure"): CI has
#                  no way to type the code.
#
# The agreement accepted here is the one the SDK Manager already had you
# accept on your own machine.

set -euo pipefail

root="$HOME/.Garmin/ConnectIQ"
warn_days=21

# When the saved sign-in runs out: a warning ahead of it, a plain failure after.
check_token() {
  mkdir -p "$root"
  (umask 077 && printf '%s' "$GARMIN_TOKEN" | base64 -d > "$root/token.json")
  python3 - "$root/token.json" "$warn_days" <<'EOF'
import json, re, sys
from datetime import datetime, timezone

with open(sys.argv[1]) as f:
    raw = json.load(f).get("refreshTokenExpiresIn", "")
# Go writes nanoseconds; Python reads at most microseconds.
stamp = re.sub(r"(\.\d{6})\d+", r"\1", raw).replace("Z", "+00:00")
try:
    expires = datetime.fromisoformat(stamp)
except ValueError:
    print(f"::warning::GARMIN_TOKEN: cannot read its expiry ({raw!r}); trying it anyway.")
    sys.exit(0)
days = (expires - datetime.now(timezone.utc)).days
when = expires.strftime("%d %b %Y")
if days < 0:
    print(f"::error::GARMIN_TOKEN ran out on {when}. Run tools/garmin_token.sh on your computer to renew it.")
    sys.exit(1)
if days < int(sys.argv[2]):
    print(f"::warning::GARMIN_TOKEN runs out on {when} ({days} days). Run tools/garmin_token.sh on your computer to renew it.")
else:
    print(f"GARMIN_TOKEN is good until {when}.")
EOF
}

# --check: only say how long GARMIN_TOKEN has left. Every watch build runs it,
# so the warning shows even when the cached definitions mean no sign-in, and
# an expired token is flagged without failing a build that does not need it.
if [[ "${1:-}" == "--check" ]]; then
  if [[ -n "${GARMIN_TOKEN:-}" ]]; then check_token || true; fi
  exit 0
fi

curl -sSfL https://raw.githubusercontent.com/lindell/connect-iq-sdk-manager-cli/master/install.sh \
  | sh -s -- -b "$HOME/.local/bin"
export PATH="$HOME/.local/bin:$PATH"
connect-iq-sdk-manager agreement view > /dev/null
connect-iq-sdk-manager agreement accept

if [[ -n "${GARMIN_TOKEN:-}" ]]; then
  check_token
elif [[ -n "${GARMIN_USERNAME:-}" && -n "${GARMIN_PASSWORD:-}" ]]; then
  connect-iq-sdk-manager login
else
  echo "::error::Neither GARMIN_TOKEN nor GARMIN_USERNAME and GARMIN_PASSWORD is set."
  exit 1
fi

connect-iq-sdk-manager device download --manifest garmin/manifest.xml
