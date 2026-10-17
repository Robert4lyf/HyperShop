"""Fetches the NYT ingredient-phrase-tagger snapshot, pinned by commit and sha256.

Apache-2.0 (catalog/LICENSE-NYT). Cached in tools/.cache/nyt/, which is git-ignored:
the 14 MB CSV is an input, not something the repo ships.
"""

from __future__ import annotations

import csv
import hashlib
import urllib.request
from pathlib import Path

COMMIT = "e414c2ca279f23c99c8338ceba00653d88d40dfe"
URL = f"https://raw.githubusercontent.com/nytimes/ingredient-phrase-tagger/{COMMIT}/nyt-ingredients-snapshot-2015.csv"
SHA256 = "7e029d8d00acd86933bd92dbec81d04f4b9fe4e60b3422325bac1cd5a049f56d"
CACHE = Path(__file__).resolve().parent / ".cache/nyt/nyt-ingredients-snapshot-2015.csv"


def path() -> Path:
    if not CACHE.exists() or hashlib.sha256(CACHE.read_bytes()).hexdigest() != SHA256:
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(URL, timeout=60) as r:
            data = r.read()
        digest = hashlib.sha256(data).hexdigest()
        if digest != SHA256:
            raise SystemExit(f"NYT snapshot sha256 mismatch: {digest}")
        CACHE.write_bytes(data)
    return CACHE


def rows() -> list[dict[str, str]]:
    with path().open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))
