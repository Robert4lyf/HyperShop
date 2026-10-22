#!/usr/bin/env bash
#
# Release A's performance probe on an emulator (CI's device job): runs
# PerformanceProbeTest, which is skipped in the ordinary suite, and prints
# its measurements (logcat tag HyperShopPerf). Fails if the probe fails,
# including a budget it asserts. Then Release B's weekly-shop effort: the two
# ways through scenario B again, their taps, typed entries and times
# (HyperShopEffort, docs/reports/release-b-effort.md).
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../android"

APP=com.robert4lyf.hypershop.debug
RUNNER=$APP.test/androidx.test.runner.AndroidJUnitRunner

./gradlew --max-workers=2 -q :app:installDebug :app:installDebugAndroidTest
adb logcat -c
out=$(adb shell am instrument -w -e perf true -e class com.robert4lyf.hypershop.device.PerformanceProbeTest "$RUNNER")
echo "== measurements"
adb logcat -d -s HyperShopPerf:I | sed -n 's/.*HyperShopPerf: //p'
if grep -q "^OK (" <<< "$out"; then
  echo "$out" | tail -3
else
  echo "$out"
  exit 1
fi

adb logcat -c
out=$(adb shell am instrument -w -e class \
  "com.robert4lyf.hypershop.device.WeeklyShopScenariosTest#b_returningWeeklyShopperTheQuickWay,com.robert4lyf.hypershop.device.WeeklyShopScenariosTest#b_returningWeeklyShopperItemByItem" \
  "$RUNNER")
echo "== weekly shop effort"
adb logcat -d -s HyperShopEffort:I | sed -n 's/.*HyperShopEffort: //p'
if grep -q "^OK (" <<< "$out"; then
  echo "$out" | tail -3
else
  echo "$out"
  exit 1
fi
