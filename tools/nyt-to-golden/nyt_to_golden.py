#!/usr/bin/env python3
"""Converts the picked NYT lines into golden ParsedIngredient JSON (spec R10).

  1. Automatic mapping of the NYT labels (R10 table).
  2. overrides.json, keyed by NYT index: field replacements, each with the spec
     rule it applies ("rule": "R4.2") and a short reason. An override without a
     rule is an error.

Output: testdata/ingredients/frozen.json. It is frozen once committed: never
edited to make a test pass (PLAN §6 rule 6).
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import nyt_snapshot  # noqa: E402

PICKED = ROOT / "tools/pick-golden/picked.json"
OVERRIDES = HERE / "overrides.json"
OUT = ROOT / "testdata/ingredients/frozen.json"

UNITS = {
    "g": ["gram", "grams", "g", "gr"],
    "kg": ["kilogram", "kilograms", "kg", "kilo", "kilos"],
    "oz": ["ounce", "ounces", "oz"],
    "lb": ["pound", "pounds", "lb", "lbs"],
    "ml": ["milliliter", "milliliters", "millilitre", "millilitres", "ml"],
    "l": ["liter", "liters", "litre", "litres", "l"],
    "tsp": ["teaspoon", "teaspoons", "tsp"],
    "tbsp": ["tablespoon", "tablespoons", "tbsp"],
    "cup": ["cup", "cups"],
    "fl_oz": ["fluid ounce", "fluid ounces", "fl oz"],
    "pint": ["pint", "pints"],
    "quart": ["quart", "quarts"],
    "clove": ["clove", "cloves"],
    "bunch": ["bunch", "bunches"],
    "tin": ["can", "cans", "tin", "tins"],
    "packet": ["package", "packages", "packet", "packets", "pack", "envelope", "sachet"],
    "sprig": ["sprig", "sprigs"],
    "slice": ["slice", "slices"],
    "head": ["head", "heads"],
    "stalk": ["stalk", "stalks"],
    "fillet": ["fillet", "fillets"],
    "piece": ["piece", "pieces"],
    "stick": ["stick", "sticks"],
    "pinch": ["pinch", "pinches"],
    "dash": ["dash", "dashes"],
}
SPELLING = {w: k for k, ws in UNITS.items() for w in ws}
SIZES = {"large", "medium", "small", "extra-large", "medium-size", "big"}


def num(s: str) -> float | None:
    try:
        v = float(s)
    except ValueError:
        return None
    return None if v == 0 else round(v, 4)


def unit(nyt: str) -> tuple[str | None, str | None]:
    """Returns (unit, size word moved to note)."""
    u = nyt.strip().lower()
    if not u:
        return None, None
    if u in SIZES:
        return None, u
    return SPELLING.get(u, "other"), None


def auto(row: dict[str, str]) -> dict:
    u, size = unit(row["unit"])
    note = row["comment"].strip() or None
    if size:
        note = size if not note else f"{size}, {note}"
    return {
        "raw": row["input"].strip(),
        "name": re.sub(r"\s+", " ", row["name"].strip().lower()),
        "quantity": num(row["qty"]),
        "quantity_max": num(row["range_end"]),
        "unit": u,
        "alt_quantity": None,
        "alt_unit": None,
        "note": note,
        "section": None,
        "optional": "optional" in row["input"].lower(),
    }


def main() -> int:
    picked = json.loads(PICKED.read_text(encoding="utf-8"))
    overrides = json.loads(OVERRIDES.read_text(encoding="utf-8")) if OVERRIDES.exists() else {}
    by_index = {int(r["index"]): r for r in nyt_snapshot.rows()}
    out = []
    used = set()
    for p in picked:
        row = by_index[p["index"]]
        g = auto(row)
        ov = overrides.get(str(p["index"]))
        if ov:
            if not ov.get("rule"):
                raise SystemExit(f"override {p['index']} cites no rule")
            for k, v in ov["set"].items():
                if k not in g:
                    raise SystemExit(f"override {p['index']}: unknown field {k}")
                g[k] = v
            used.add(str(p["index"]))
        out.append(
            {
                "id": f"nyt-{p['index']}",
                "stratum": p["stratum"],
                "heldOut": p["heldOut"],
                "nyt": {k: row[k] for k in ("name", "qty", "range_end", "unit", "comment")},
                "expected": g,
                "override": ov["rule"] if ov else None,
            }
        )
    stale = set(overrides) - used
    if stale:
        raise SystemExit(f"overrides for lines not picked: {sorted(stale)}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(out)} golden lines ({len(used)} overridden) to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
