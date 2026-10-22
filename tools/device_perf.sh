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

# Each way through on its own instrumentation run: a fresh process, as a person opening the app.
echo "== weekly shop effort"
for t in b_returningWeeklyShopperTheQuickWay b_returningWeeklyShopperItemByItem; do
  adb logcat -c
  out=$(adb shell am instrument -w -e class "com.robert4lyf.hypershop.device.WeeklyShopScenariosTest#$t" "$RUNNER")
  adb logcat -d -s HyperShopEffort:I | sed -n 's/.*HyperShopEffort: //p'
  if ! grep -q "^OK (" <<< "$out"; then
    echo "$out"
    exit 1
  fi
done
