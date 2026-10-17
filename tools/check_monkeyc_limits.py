#!/usr/bin/env python3
"""Fails if a Monkey C method declares more arguments than the VM allows.

Monkey C permits a method nine arguments. Ten is not a warning, it is
`ERROR: Too many arguments passed to method` and no .prg — but *only* when
monkeyc is targeting a device. The build in CI is device-independent, because
device definitions are behind a Garmin login, and it compiles a ten-argument
method quite happily. So this particular mistake passes every check the
repository has and fails on the one machine that can produce the sideload.

That is what this script is for. It is a text scan rather than a parse: the
count only has to be right for declarations, which are the only thing the limit
applies to, and being approximately right about a hard limit is enough to catch
crossing it.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "garmin/source"

#: The VM's limit. Not configurable at the language level; do not "fix" a
#: failure by raising it.
MAX_ARGUMENTS = 9

DECLARATION = re.compile(r"\bfunction\s+(\w+)\s*\(")


def argument_count(text: str, open_paren: int) -> tuple[int, int]:
    """Arguments in the list starting at [open_paren], and the closing index."""
    depth = 0
    close = open_paren
    for index in range(open_paren, len(text)):
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                close = index
                break

    inner = text[open_paren + 1 : close]
    if not inner.strip():
        return 0, close

    # Commas inside a nested type or a default value belong to that, not to the
    # argument list: `Lang.Array<Lang.Number>` and `method(:x)` both appear here.
    depth = 0
    count = 1
    for char in inner:
        if char in "([<":
            depth += 1
        elif char in ")]>":
            depth -= 1
        elif char == "," and depth == 0:
            count += 1
    return count, close


def main() -> int:
    if not SOURCE.is_dir():
        print(f"No Monkey C source at {SOURCE}", file=sys.stderr)
        return 1

    over = []
    checked = 0
    for path in sorted(SOURCE.rglob("*.mc")):
        text = path.read_text(encoding="utf-8")
        for match in DECLARATION.finditer(text):
            count, _ = argument_count(text, match.end() - 1)
            checked += 1
            if count > MAX_ARGUMENTS:
                line = text.count("\n", 0, match.start()) + 1
                over.append(
                    f"{path.relative_to(ROOT)}:{line}: "
                    f"{match.group(1)}() takes {count} arguments; the limit is {MAX_ARGUMENTS}"
                )

    if over:
        print("Monkey C argument limit exceeded:", file=sys.stderr)
        for line in over:
            print(f"  {line}", file=sys.stderr)
        print(
            "\nA device build rejects these. Pass the extras another way — a "
            "setter after construction, or a field the caller assigns.",
            file=sys.stderr,
        )
        return 1

    print(f"Monkey C argument limits OK ({checked} declarations, max {MAX_ARGUMENTS}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
