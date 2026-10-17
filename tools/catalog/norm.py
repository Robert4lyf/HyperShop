"""The catalogue's key normaliser (spec R5.1): lower case, accents stripped,
straight quotes, whitespace collapsed. Kotlin's `Normaliser.key` matches it."""

import re
import unicodedata


def key(s: str) -> str:
    s = unicodedata.normalize("NFKD", s.replace("’", "'")).encode("ascii", "ignore").decode()
    s = re.sub(r"[^a-z0-9' -]", " ", s.lower())
    return re.sub(r"\s+", " ", s).strip(" -'")
