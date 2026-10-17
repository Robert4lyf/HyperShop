#!/usr/bin/env python3
"""Trims captured recipe pages to their structured block (PLAN Step 5.1).

Keeps: <title>, og:title, the JSON-LD script(s) that hold a Recipe (with
instructions, descriptions, reviews and images removed; @graph keeps its Recipe
node and a WebPage node so the @graph quirk survives), and the WPRM / Tasty
ingredient container. Everything else, the prose included, is dropped.

Usage: trim.py <captured.html> <out.html>
"""

from __future__ import annotations

import json
import sys

from bs4 import BeautifulSoup

DROP = {"recipeInstructions", "description", "review", "reviews", "image", "video", "author", "aggregateRating",
        "nutrition", "comment", "articleBody", "thumbnailUrl", "publisher"}


def is_recipe(node) -> bool:
    t = node.get("@type") if isinstance(node, dict) else None
    return t == "Recipe" or (isinstance(t, list) and "Recipe" in t)


def clean(node):
    if isinstance(node, dict):
        return {k: clean(v) for k, v in node.items() if k not in DROP}
    if isinstance(node, list):
        return [clean(x) for x in node]
    return node


def trim_ld(text: str):
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return None
    if isinstance(data, dict) and "@graph" in data:
        graph = data["@graph"]
        recipes = [n for n in graph if is_recipe(n)]
        if not recipes:
            return None
        others = [n for n in graph if isinstance(n, dict) and n.get("@type") == "WebPage"][:1]
        return {"@context": data.get("@context"), "@graph": [clean({"@type": "WebPage", "name": o.get("name")}) for o in others] + [clean(r) for r in recipes]}
    items = data if isinstance(data, list) else [data]
    recipes = [n for n in items if is_recipe(n)]
    if not recipes:
        return None
    out = [clean(r) for r in recipes]
    return out if isinstance(data, list) else out[0]


def main() -> int:
    src, dst = sys.argv[1], sys.argv[2]
    soup = BeautifulSoup(open(src, encoding="utf-8", errors="replace").read(), "lxml")
    parts = ["<!doctype html>", "<html><head>"]
    if soup.title and soup.title.string:
        parts.append(f"<title>{soup.title.string.strip()}</title>")
    og = soup.find("meta", property="og:title")
    if og and og.get("content"):
        parts.append(f'<meta property="og:title" content="{og["content"]}">')
    for s in soup.find_all("script", type="application/ld+json"):
        trimmed = trim_ld(s.string or s.get_text() or "")
        if trimmed is not None:
            parts.append('<script type="application/ld+json">' + json.dumps(trimmed, ensure_ascii=False, indent=1) + "</script>")
    parts.append("</head><body>")
    for sel in ("div.wprm-recipe-ingredients-container", "div.tasty-recipes-ingredients"):
        for el in soup.select(sel)[:1]:
            for bad in el.select("script, style, svg, img"):
                bad.decompose()
            parts.append(str(el))
    parts.append("</body></html>")
    open(dst, "w", encoding="utf-8").write("\n".join(parts) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
