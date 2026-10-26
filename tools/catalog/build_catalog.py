#!/usr/bin/env python3
"""Validates catalog/ingredients.csv and generates what is derived from it.

  catalog/synonyms.csv                              key -> id (every name, plural, synonym)
  android/core/src/main/resources/catalog/catalog.tsv   the app's copy, with its version
  catalog/REVIEW.md                                 the human review sheet (Hand-off A)

--check exits non-zero when any generated file is stale (verify.sh runs it).
"""

from __future__ import annotations

import csv
import hashlib
import io
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
from norm import key  # noqa: E402

SRC = ROOT / "catalog/ingredients.csv"
TOP = ROOT / "catalog/top-names.csv"
SYN = ROOT / "catalog/synonyms.csv"
TSV = ROOT / "android/core/src/main/resources/catalog/catalog.tsv"
REVIEW = ROOT / "catalog/REVIEW.md"

COLUMNS = ["id", "name", "plural", "synonyms", "aisleCode", "staple", "shoppingUnit",
           "displayPolicy", "gramsPerCup", "packNote", "group", "gramsEach", "src"]
AISLES = ["PRODUCE", "BAKERY", "MEAT_FISH", "DAIRY_EGGS", "CHILLED", "FROZEN", "TINS_JARS",
          "PASTA_RICE_GRAINS", "WORLD_FOODS", "BAKING", "NUTS_DRIED_FRUIT", "OILS_VINEGARS",
          "SPICES_CONDIMENTS", "FREE_FROM", "DRINKS", "HOUSEHOLD", "OTHER"]
POLICIES = {"count", "mass", "volume", "presence"}
SRCS = {"agent-estimate", "sourced", "user"}


def validate(rows: list[dict[str, str]]) -> list[str]:
    errors: list[str] = []
    ids: set[str] = set()
    keys: dict[str, str] = {}
    for n, r in enumerate(rows, 2):
        where = f"line {n} ({r.get('id')})"
        if list(r.keys()) != COLUMNS:
            errors.append(f"{where}: columns {list(r.keys())}")
            continue
        if not r["id"] or not all(c.isalnum() or c == "_" for c in r["id"]) or r["id"] != r["id"].lower():
            errors.append(f"{where}: bad id")
        if r["id"] in ids:
            errors.append(f"{where}: duplicate id")
        ids.add(r["id"])
        if not r["name"] or not r["plural"]:
            errors.append(f"{where}: name and plural required")
        if r["aisleCode"] not in AISLES:
            errors.append(f"{where}: aisleCode {r['aisleCode']}")
        if r["staple"] not in ("true", "false"):
            errors.append(f"{where}: staple {r['staple']}")
        if r["displayPolicy"] not in POLICIES:
            errors.append(f"{where}: displayPolicy {r['displayPolicy']}")
        if r["gramsEach"]:
            try:
                if not 1 <= float(r["gramsEach"]) <= 5000:
                    errors.append(f"{where}: gramsEach out of range")
            except ValueError:
                errors.append(f"{where}: gramsEach not a number")
        if r["gramsPerCup"]:
            try:
                if not 5 <= float(r["gramsPerCup"]) <= 400:  # a cup of dill is 9 g
                    errors.append(f"{where}: gramsPerCup out of range")
            except ValueError:
                errors.append(f"{where}: gramsPerCup not a number")
        if r["group"] and (len(r["group"]) > 30 or r["group"] != r["group"].strip()):
            errors.append(f"{where}: group {r['group']!r}")
        if r["src"] not in SRCS:
            errors.append(f"{where}: src {r['src']}")
        if any("\t" in v or "\n" in v for v in r.values()):
            errors.append(f"{where}: tab or newline in a field")
        names = [r["name"], r["plural"], *[s for s in r["synonyms"].split("|") if s]]
        for nm in names:
            k = key(nm)
            if not k:
                errors.append(f"{where}: empty key from {nm!r}")
            elif keys.get(k, r["id"]) != r["id"]:
                errors.append(f"{where}: key {k!r} also belongs to {keys[k]}")
            keys[k] = r["id"]
    packs = sum(1 for r in rows if r.get("packNote"))
    # W.3: pack notes are sourced now (catalog/SOURCES.md) and size the count of anything bought in a container.
    if packs < 15:
        errors.append(f"{packs} pack notes; the common containers need theirs")
    if not 350 <= len(rows) <= 600:
        errors.append(f"{len(rows)} rows; the plan calls for about 400 (600 at most)")
    return errors


def synonyms_csv(rows: list[dict[str, str]]) -> str:
    out = io.StringIO()
    w = csv.writer(out, lineterminator="\n")
    w.writerow(["key", "id"])
    pairs = set()
    for r in rows:
        for nm in [r["name"], r["plural"], *[s for s in r["synonyms"].split("|") if s]]:
            pairs.add((key(nm), r["id"]))
    for k, i in sorted(pairs):
        w.writerow([k, i])
    return out.getvalue()


def tsv(rows: list[dict[str, str]], version: str) -> str:
    lines = [f"#version\t{version}", "#" + "\t".join(COLUMNS[:-1])]
    lines += ["\t".join(r[c] for c in COLUMNS[:-1]) for r in rows]
    return "\n".join(lines) + "\n"


def review(rows: list[dict[str, str]], version: str) -> str:
    ranks = {}
    if TOP.exists():
        for t in csv.DictReader(TOP.open(encoding="utf-8")):
            ranks.setdefault(key(t["name"]), int(t["rank"]))
    out = [
        "# Catalogue review (generated — do not edit)",
        "",
        f"Generated by `tools/catalog/build_catalog.py` from `catalog/ingredients.csv` (version `{version}`).",
        "Rows marked `src=agent-estimate` were written by an agent; `src=sourced` rows have their pack size and weight "
        "from a retailer listing or USDA FoodData Central (links in SOURCES.md, W.3).",
        "Tick a row if it is right; fix it in `ingredients.csv` (and set `src=user`) if not.",
        "",
        f"{len(rows)} items · {sum(r['staple'] == 'true' for r in rows)} staples · "
        f"{sum(bool(r['packNote']) for r in rows)} pack notes.",
    ]
    for aisle in AISLES:
        items = [r for r in rows if r["aisleCode"] == aisle]
        if not items:
            continue
        out += ["", f"## {aisle} ({len(items)})", "", "| ✓ | name | also matches | group | NYT rank | staple | pack note |", "|---|---|---|---|---|---|---|"]
        for r in items:
            syn = [s for s in r["synonyms"].split("|") if s]
            rank = min([ranks[k] for k in [key(r["name"]), key(r["plural"]), *map(key, syn)] if k in ranks], default=None)
            shown = ", ".join(syn[:4]) + (" …" if len(syn) > 4 else "")
            out.append(f"| ☐ | {r['name']} | {shown} | {r['group']} | {rank or ''} | {'yes' if r['staple'] == 'true' else ''} | {r['packNote']} |")
    return "\n".join(out) + "\n"


def main() -> int:
    check = "--check" in sys.argv
    raw = SRC.read_bytes()
    rows = list(csv.DictReader(io.StringIO(raw.decode("utf-8"))))
    errors = validate(rows)
    if errors:
        print("catalog/ingredients.csv is invalid:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1
    version = hashlib.sha256(raw).hexdigest()[:12]
    outputs = {SYN: synonyms_csv(rows), TSV: tsv(rows, version), REVIEW: review(rows, version)}
    stale = [p for p, text in outputs.items() if not p.exists() or p.read_text(encoding="utf-8") != text]
    if check:
        if stale:
            print("stale generated catalogue files (run tools/catalog/build_catalog.py):", file=sys.stderr)
            for p in stale:
                print(f"  - {p.relative_to(ROOT)}", file=sys.stderr)
            return 1
        print(f"catalogue OK: {len(rows)} rows, version {version}")
        return 0
    for p in stale:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(outputs[p], encoding="utf-8")
    print(f"catalogue valid: {len(rows)} rows, version {version}; wrote {len(stale)} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
