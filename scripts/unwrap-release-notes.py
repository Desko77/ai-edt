#!/usr/bin/env python3
"""Join hand-wrapped paragraphs so a release body renders as written.

A GitHub release body renders a single newline as a line break. Notes wrapped by hand at some
column - the way source files are written - therefore come out with sentences snapped in half at
whatever word sat on the boundary. Paragraphs have to be one line each.

Structure is left alone: headings, list items, table rows, quotes and everything inside a fence
keep their own lines, because there a newline is the meaning.

Usage:
    python scripts/unwrap-release-notes.py <file.md> [more.md ...]      # rewrite in place
    python scripts/unwrap-release-notes.py --check <file.md>            # report, exit 1 on wrapping
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

# A line that carries structure rather than prose: joining it would destroy what it says.
STRUCTURAL = re.compile(r"^\s*(#{1,6}\s|[-*+]\s|\d+[.)]\s|\||>|\s*$)")
FENCE = re.compile(r"^\s*```")


def unwrap(text: str) -> str:
    out: list[str] = []
    in_fence = False
    for line in text.split("\n"):
        if FENCE.match(line):
            in_fence = not in_fence
            out.append(line)
            continue
        if in_fence or STRUCTURAL.match(line):
            out.append(line)
            continue
        # Prose. Join it to the previous line when that one was prose too.
        if out and out[-1].strip() and not STRUCTURAL.match(out[-1]) and not FENCE.match(out[-1]):
            out[-1] = out[-1].rstrip() + " " + line.strip()
        else:
            out.append(line)
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+")
    parser.add_argument("--check", action="store_true", help="report instead of rewriting")
    args = parser.parse_args()

    wrapped = []
    for name in args.files:
        path = pathlib.Path(name)
        original = path.read_text(encoding="utf-8")
        joined = unwrap(original)
        if joined == original:
            print(f"{path}: paragraphs are already one line each")
            continue
        if args.check:
            wrapped.append(str(path))
            print(f"{path}: paragraphs are wrapped and would render broken", file=sys.stderr)
            continue
        path.write_text(joined, encoding="utf-8", newline="")
        print(f"{path}: unwrapped")
    return 1 if wrapped else 0


if __name__ == "__main__":
    sys.exit(main())
