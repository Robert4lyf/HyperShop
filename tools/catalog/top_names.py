#!/usr/bin/env python3
"""Builds the 400 most common normalised ingredient names from the NYT snapshot.

PLAN Step 1.4a. Normalising (docs/parser-spec.md R4, R5):
  - lower case, punctuation stripped, prep and size words removed (R4.1);
  - folds: kosher/sea/table/coarse/flaky salt -> salt; *black pepper -> black pepper (R5.3);
  - compounds split on " and " / " or " / "/" ("salt and pepper" -> salt, pepper);
  - plurals merged into the singular when both forms occur (R5.4).

Output: catalog/top-names.csv (rank, name, count, variants). The catalogue
(catalog/ingredients.csv, Step 1.4b) is written from this list.
"""

from __future__ import annotations

import collections
import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import nyt_snapshot  # noqa: E402

OUT = ROOT / "catalog/top-names.csv"
TOP = 400

PREP = {
    "chopped", "diced", "minced", "sliced", "grated", "shredded", "crushed", "sifted", "peeled",
    "cored", "seeded", "deseeded", "pitted", "halved", "quartered", "cubed", "trimmed", "rinsed",
    "drained", "beaten", "whisked", "melted", "softened", "cooked", "thawed", "defrosted", "torn",
    "squeezed", "zested", "finely", "roughly", "coarsely", "thinly", "lightly", "freshly", "fresh",
    "small", "medium", "large", "extra-large", "medium-size", "big", "thick", "thin", "about",
    "ripe", "good", "good-quality", "high-quality", "best-quality", "quality", "organic", "cold",
    "warm", "hot water", "room-temperature", "packed", "loosely", "firmly", "plus", "more", "optional",
}
SALTS = {"kosher salt", "sea salt", "table salt", "coarse salt", "flaky salt", "fine salt",
         "coarse kosher salt", "fine sea salt", "flaky sea salt", "coarse sea salt", "kosher salt and"}
SPLIT = re.compile(r"\s+(?:and|or|&)\s+|\s*/\s*")


def singular(word: str) -> str:
    if word.endswith("ies") and len(word) > 4:
        return word[:-3] + "y"
    if word.endswith("oes"):
        return word[:-2]
    if word.endswith(("ches", "shes", "sses", "xes")):
        return word[:-2]
    if word.endswith("s") and not word.endswith(("ss", "us", "is")):
        return word[:-1]
    return word


def normalise(name: str) -> list[str]:
    name = name.lower().replace("’", "'")
    name = re.sub(r"\([^)]*\)", " ", name)
    name = re.sub(r"[^a-z' -/&]", " ", name)
    out = []
    for part in SPLIT.split(name):
        words = [w for w in part.split() if w not in PREP]
        n = " ".join(words).strip(" -'")
        if not n:
            continue
        if n in SALTS or n.endswith(" salt") and n.split()[0] in {"kosher", "sea", "coarse", "table", "flaky", "fine"}:
            n = "salt"
        if n.endswith("black pepper") or n == "ground pepper":
            n = "black pepper"
        if len(n) >= 3:
            out.append(n)
    return out


def main() -> int:
    counts: collections.Counter[str] = collections.Counter()
    for row in nyt_snapshot.rows():
        for n in normalise(row["name"]):
            counts[n] += 1
    # Merge plurals into singulars when the singular form exists too.
    merged: collections.Counter[str] = collections.Counter()
    variants: dict[str, set[str]] = collections.defaultdict(set)
    for n, c in counts.items():
        words = n.split()
        sing = " ".join(words[:-1] + [singular(words[-1])])
        target = sing if sing != n and counts.get(sing, 0) > 0 else n
        merged[target] += c
        variants[target].add(n)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["rank", "name", "count", "variants"])
        for i, (n, c) in enumerate(merged.most_common(TOP), 1):
            w.writerow([i, n, c, "|".join(sorted(variants[n] - {n}))])
    print(f"wrote {TOP} names to {OUT.relative_to(ROOT)} ({len(merged)} distinct after merging)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
