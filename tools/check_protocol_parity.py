#!/usr/bin/env python3
"""Fails if the Android and Connect IQ copies of the wire protocol drift apart.

The two sides of the BLE link each declare the protocol in their own language,
and a mismatch shows up as a set that silently never arrives. Comparing the
literal values catches that at review time instead of in the shop.

Known limitation: the values are compared as *sets*, so two constants sharing a
value collapse into one and a value declared twice on one side is invisible. A
name-by-name comparison would need to know that KEY_WEIGHT_KG and WEIGHT_KG are
the same thing; that mapping has not been worth writing yet.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "android/core/src/main/kotlin/com/robert4lyf/hypershop/core/sync/WatchProtocol.kt"
MONKEY_C = ROOT / "garmin/source/Protocol.mc"
# HyperShop keeps WATCH_APP_ID in WatchProtocol.kt itself.
SYNC = KOTLIN
MANIFEST = ROOT / "garmin/manifest.xml"

KOTLIN_CONST = re.compile(r'const val [A-Z_]+ = "([^"]+)"')
KOTLIN_VERSION = re.compile(r"const val VERSION = (\d+)")
MONKEY_CONST = re.compile(r'const [A-Z_]+ = "([^"]+)";')
MONKEY_VERSION = re.compile(r"const VERSION = (\d+);")
WATCH_APP_ID = re.compile(r'const val WATCH_APP_ID = "([0-9a-fA-F]+)"')
MANIFEST_APP_ID = re.compile(r'<iq:application\b[^>]*?\bid="([0-9a-fA-F]+)"', re.DOTALL)


def main() -> int:
    for path in (KOTLIN, MONKEY_C, SYNC, MANIFEST):
        if not path.exists():
            print(f"missing {path.relative_to(ROOT)}", file=sys.stderr)
            return 1

    kotlin_source = KOTLIN.read_text()
    monkey_source = MONKEY_C.read_text()

    kotlin_values = set(KOTLIN_CONST.findall(kotlin_source))
    monkey_values = set(MONKEY_CONST.findall(monkey_source))

    problems: list[str] = []

    kotlin_version = KOTLIN_VERSION.search(kotlin_source)
    monkey_version = MONKEY_VERSION.search(monkey_source)
    if not kotlin_version or not monkey_version:
        problems.append("could not find a VERSION constant on both sides")
    elif kotlin_version.group(1) != monkey_version.group(1):
        problems.append(
            f"protocol VERSION differs: Kotlin {kotlin_version.group(1)}, "
            f"Monkey C {monkey_version.group(1)}"
        )

    # A mismatched application id is the nastiest failure mode of the lot: the
    # phone initialises cleanly, finds the watch, and simply never sees the app.
    sync_id = WATCH_APP_ID.search(SYNC.read_text())
    manifest_id = MANIFEST_APP_ID.search(MANIFEST.read_text())
    if not sync_id or not manifest_id:
        problems.append("could not find the watch application id on both sides")
    elif sync_id.group(1).lower() != manifest_id.group(1).lower():
        problems.append(
            f"watch application id differs: WatchProtocol {sync_id.group(1)}, "
            f"garmin/manifest.xml {manifest_id.group(1)}"
        )

    only_kotlin = sorted(kotlin_values - monkey_values)
    only_monkey = sorted(monkey_values - kotlin_values)

    if only_kotlin:
        problems.append(f"declared only in WatchProtocol.kt: {', '.join(only_kotlin)}")
    if only_monkey:
        problems.append(f"declared only in Protocol.mc: {', '.join(only_monkey)}")

    if problems:
        print("Protocol parity check failed:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print(
        f"Protocol parity OK ({len(kotlin_values)} shared constants, "
        f"watch app id {sync_id.group(1)})."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
