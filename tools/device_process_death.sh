#!/usr/bin/env bash
#
# A restore killed by real process death, on an emulator (CI's device job):
# stage 1 starts a restore and halts the process half-way; stage 2 runs in a
# new process and checks start-up finished the restore before anything else.
# Uses adb directly, because Gradle's connected task uninstalls the app (and
# the half-finished restore with it) between runs. Emulator only: it replaces
# the list of whatever HyperShop debug build is installed.
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../android"

APP=com.robert4lyf.hypershop.debug
RUNNER=$APP.test/androidx.test.runner.AndroidJUnitRunner
CLASS=com.robert4lyf.hypershop.device.ProcessDeathTest

./gradlew --max-workers=2 -q :app:installDebug :app:installDebugAndroidTest

echo "== stage 1: restore, then the process dies half-way (a crash report here is expected)"
adb shell am instrument -w -e processDeath true -e class "$CLASS#stage1_theProcessDiesHalfwayThroughARestore" "$RUNNER" || true

echo "== between the stages: the journal on disk"
adb shell run-as "$APP" ls no_backup/restore || true
if ! adb shell run-as "$APP" ls no_backup/restore | grep -q journal; then
  echo "FAIL: stage 1 left no restore journal: the process did not die mid-restore" >&2
  exit 1
fi

echo "== stage 2: a new process finishes the restore before anything else"
out=$(adb shell am instrument -w -e processDeath true -e class "$CLASS#stage2_theNextProcessFinishesItBeforeAnythingElse" "$RUNNER")
echo "$out"
if ! grep -q "OK (1 test)" <<< "$out"; then
  echo "FAIL: the next process did not finish the interrupted restore" >&2
  exit 1
fi
echo "process-death recovery: passed"
