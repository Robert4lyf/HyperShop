#!/usr/bin/env python3
"""Checks the merged debug manifest of :app.

- No CAMERA permission (v1 has none; v1.1 uses TakePicture, which needs none).
- The only exported activities are the launcher and the share target
  (MainActivity, and ShareActivity once Step 5.9 adds it).
- No other exported component except those the Garmin SDK merges in.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "android/app/build/intermediates"
A = "{http://schemas.android.com/apk/res/android}"
ALLOWED_ACTIVITIES = {
    "com.robert4lyf.hypershop.MainActivity",
    "com.robert4lyf.hypershop.ShareActivity",
}
# Components other libraries merge in that are exported by design. Anything
# here is a deliberate exception, reviewed when it was added.
ALLOWED_OTHER: set[str] = {
    # The Connect IQ SDK's binding service: Garmin Connect Mobile binds to it.
    "com.garmin.android.connectiq.IQGarminBindingService",
}
# Debug-only tooling activities (ui-tooling previews, ui-test-manifest host).
DEBUG_ONLY_ACTIVITIES = {
    "androidx.activity.ComponentActivity",
    "androidx.compose.ui.tooling.PreviewActivity",
}


def find_manifest() -> Path | None:
    for candidate in (
        BUILD / "merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
        BUILD / "merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
    ):
        if candidate.exists():
            return candidate
    return None


def main() -> int:
    path = find_manifest()
    if path is None:
        print("merged manifest not found; run :app:assembleDebug first", file=sys.stderr)
        return 1
    root = ET.parse(path).getroot()
    problems: list[str] = []

    for perm in root.iter("uses-permission"):
        name = perm.get(A + "name", "")
        if name.endswith(".CAMERA"):
            problems.append(f"CAMERA permission declared: {name}")

    app = root.find("application")
    assert app is not None
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for comp in app.iter(tag):
            if comp.get(A + "exported") != "true":
                continue
            name = comp.get(A + "name", "")
            # Guarded by a system permission (BIND_JOB_SERVICE, DUMP): not reachable by apps.
            if comp.get(A + "permission", "").startswith("android.permission."):
                continue
            if tag in ("activity", "activity-alias"):
                if name not in ALLOWED_ACTIVITIES | DEBUG_ONLY_ACTIVITIES:
                    problems.append(f"exported {tag}: {name}")
            elif name not in ALLOWED_OTHER:
                problems.append(f"exported {tag}: {name}")

    if problems:
        print(f"Manifest check failed ({path.relative_to(ROOT)}):", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print(f"manifest OK ({path.relative_to(ROOT)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
