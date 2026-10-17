#!/usr/bin/env bash
# SessionStart hook: installs just enough Android SDK for ./gradlew to build
# and test HyperShop in a Claude Code cloud container.
#
# - Runs only when CLAUDE_CODE_REMOTE is set (a developer machine already has
#   Android Studio's SDK).
# - Idempotent: with the SDK present it only refreshes local.properties and
#   the environment.
# - Never fails the session: a network outage leaves a message, not an error.
# - Does NOT download the Connect IQ SDK (tools/build_watch.sh does, in Run 2).
set -uo pipefail

if [ -z "${CLAUDE_CODE_REMOTE:-}" ]; then
  exit 0
fi

SDK_ROOT="${ANDROID_HOME:-$HOME/android-sdk}"
PLATFORM="platforms;android-36"
BUILD_TOOLS="build-tools;36.0.0"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

export_env() {
  printf 'sdk.dir=%s\n' "$SDK_ROOT" > "$REPO_ROOT/android/local.properties" 2> /dev/null || true
  if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    {
      echo "export ANDROID_HOME=\"$SDK_ROOT\""
      echo "export ANDROID_SDK_ROOT=\"$SDK_ROOT\""
    } >> "$CLAUDE_ENV_FILE"
  fi
}

if [ -d "$SDK_ROOT/platforms/android-36" ] && [ -d "$SDK_ROOT/build-tools/36.0.0" ]; then
  echo "Android SDK already present at $SDK_ROOT"
  export_env
  exit 0
fi

if ! command -v java > /dev/null 2>&1; then
  echo "No JDK on PATH; skipping the Android SDK install." >&2
  exit 0
fi

echo "Installing the Android SDK into $SDK_ROOT ..."
mkdir -p "$SDK_ROOT/cmdline-tools"
if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  workdir="$(mktemp -d)"
  trap 'rm -rf "$workdir"' EXIT
  if ! curl -sSfL --retry 3 -o "$workdir/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"; then
    echo "Could not download cmdline-tools (offline?); skipping." >&2
    exit 0
  fi
  unzip -q "$workdir/cmdline-tools.zip" -d "$workdir/extract"
  mv "$workdir/extract/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true
if ! "$SDKMANAGER" "platform-tools" "$PLATFORM" "$BUILD_TOOLS" > /dev/null; then
  echo "sdkmanager failed (offline?); the build will not work until it succeeds." >&2
  exit 0
fi
echo "Android SDK ready."
export_env
exit 0
