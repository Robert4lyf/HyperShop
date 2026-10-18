#!/usr/bin/env python3
"""Prints what the installed device definitions say about each watch in the manifest.

PLAN §3.6 leaves two facts to be read off the real definitions rather than
remembered: the memory a watch app gets, and whether the Forerunner 245 runs
glances (and with how much memory). The definitions sit behind a Garmin login,
so this runs in CI after `connect-iq-sdk-manager device download`, and its
Markdown goes to the job summary. garmin/README.md records the answer.

Read-only and tolerant: an unknown layout prints what it can, never fails the job.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEVICES = Path.home() / ".Garmin/ConnectIQ/Devices"


def glance_keys(node: object, path: str = "") -> list[str]:
    """Every key mentioning a glance, wherever it is nested."""
    found: list[str] = []
    if isinstance(node, dict):
        for key, value in node.items():
            here = f"{path}.{key}" if path else key
            if "glance" in key.lower():
                found.append(f"{here} = {json.dumps(value)}")
            found.extend(glance_keys(value, here))
    elif isinstance(node, list):
        for i, value in enumerate(node):
            found.extend(glance_keys(value, f"{path}[{i}]"))
    return found


def describe(device: str) -> list[str]:
    path = DEVICES / device / "compiler.json"
    if not path.is_file():
        return [f"## {device}", "", f"No definition at `{path}`."]
    data = json.loads(path.read_text())
    lines = [f"## {device}", ""]
    for app_type in data.get("appTypes", []):
        lines.append(f"- appType `{app_type.get('type')}`: memoryLimit {app_type.get('memoryLimit')}")
    types = {t.get("type") for t in data.get("appTypes", [])}
    lines.append(f"- glance app type listed: {'yes' if 'glance' in types else 'no'}")
    for key in glance_keys(data):
        lines.append(f"- {key}")
    resolution = data.get("resolution")
    if resolution:
        lines.append(f"- resolution {resolution.get('width')}×{resolution.get('height')}, display {data.get('displayType')}")
    versions = sorted({p.get("connectIQVersion") for p in data.get("partNumbers", []) if p.get("connectIQVersion")})
    if versions:
        lines.append(f"- Connect IQ versions: {', '.join(versions)}")
    return lines


def main() -> int:
    manifest = (ROOT / "garmin/manifest.xml").read_text()
    devices = re.findall(r'<iq:product id="([^"]+)"', manifest)
    out = ["# Device facts", ""]
    for device in devices:
        out.extend(describe(device))
        out.append("")
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
