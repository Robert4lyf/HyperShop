#!/usr/bin/env bash
#
# Run on your own computer (macOS or Linux), not in CI. Signs in to Garmin in
# the browser, two-step code and all, and saves that sign-in as the
# GARMIN_TOKEN secret the watch builds use (tools/garmin_devices.sh). Run it
# again whenever a build warns that GARMIN_TOKEN is about to run out.
#
# With the GitHub CLI (gh) signed in, the secret is set for you; otherwise it
# prints the value to paste into GitHub › Settings › Secrets and variables ›
# Actions › GARMIN_TOKEN.

set -euo pipefail

repo="Robert4lyf/HyperShop"
token="$HOME/.Garmin/ConnectIQ/token.json"

if ! command -v connect-iq-sdk-manager > /dev/null; then
  mkdir -p "$HOME/.local/bin"
  curl -sSfL https://raw.githubusercontent.com/lindell/connect-iq-sdk-manager-cli/master/install.sh \
    | sh -s -- -b "$HOME/.local/bin"
  export PATH="$HOME/.local/bin:$PATH"
fi

# Without a username and password the CLI signs in through Garmin's own page in
# the browser, which is the one place a two-step code can be typed.
env -u GARMIN_USERNAME -u GARMIN_PASSWORD connect-iq-sdk-manager login

python3 - "$token" <<'EOF'
import json, sys
print("Signed in. Garmin lets this sign-in be renewed until", json.load(open(sys.argv[1])).get("refreshTokenExpiresIn", "?"))
EOF

value="$(base64 < "$token" | tr -d '\n')"
if command -v gh > /dev/null && gh auth status > /dev/null 2>&1; then
  printf '%s' "$value" | gh secret set GARMIN_TOKEN --repo "$repo"
  echo "GARMIN_TOKEN is set on $repo."
else
  echo
  echo "Paste this as the GARMIN_TOKEN secret (GitHub › Settings › Secrets and variables › Actions):"
  echo
  echo "$value"
fi
