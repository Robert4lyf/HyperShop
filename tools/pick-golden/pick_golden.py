#!/usr/bin/env python3
"""Picks the 80 golden NYT lines, stratified, with a committed seed (PLAN Step 1.2).

Strata per docs/parser-spec.md R11 (first match wins):
  no_qty 20, fractions_ranges 15, comments_parens 20, simple 25.
5 lines per stratum are held out for the human check at Hand-off A.

Sanity filter (R11), applied before sampling so the draw is reproducible and
the labels are usable at all.

Output: tools/pick-golden/picked.json (index, input, stratum, heldOut).
"""

from __future__ import annotations

import json
import random
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import nyt_snapshot  # noqa: E402

SEED = 20261017
QUOTAS = {"simple": 25, "comments_parens": 20, "fractions_ranges": 15, "no_qty": 20}
HELD_OUT_PER_STRATUM = 5
OUT = Path(__file__).resolve().parent / "picked.json"
FRACTION = re.compile(r"\d\s*/\s*\d|[½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞]|\d\.\d")


def num(s: str) -> float:
    try:
        return float(s)
    except ValueError:
        return 0.0


def stratum(row: dict[str, str]) -> str:
    text = row["input"]
    if num(row["qty"]) == 0 or ("to taste" in text.lower() and not text.strip()[:1].isdigit()):
        return "no_qty"
    if FRACTION.search(text) or num(row["range_end"]) > 0:
        return "fractions_ranges"
    lowered = f" {text.lower()} "
    if row["comment"].strip() or any(c in text for c in "(,;") or " or " in lowered or " and " in lowered:
        return "comments_parens"
    return "simple"


def usable(row: dict[str, str]) -> bool:
    text = row["input"].strip()
    name = row["name"].strip().lower()
    return (
        3 <= len(text) <= 120
        and "<" not in text
        and "\\" not in text
        and len(name) >= 3
        and name in text.lower()
    )


def main() -> int:
    seen: set[str] = set()
    pools: dict[str, list[dict[str, str]]] = {k: [] for k in QUOTAS}
    for row in nyt_snapshot.rows():
        key = row["input"].strip().lower()
        if not usable(row) or key in seen:
            continue
        seen.add(key)
        pools[stratum(row)].append(row)

    rng = random.Random(SEED)
    picked = []
    for name, quota in QUOTAS.items():
        chosen = rng.sample(pools[name], quota)
        held = set(rng.sample(range(quota), HELD_OUT_PER_STRATUM))
        for i, row in enumerate(chosen):
            picked.append(
                {"index": int(row["index"]), "input": row["input"], "stratum": name, "heldOut": i in held}
            )
    OUT.write_text(json.dumps(picked, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"picked {len(picked)} lines into {OUT.name} (pools: {', '.join(f'{k}={len(v)}' for k, v in pools.items())})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
