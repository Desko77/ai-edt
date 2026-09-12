#!/usr/bin/env python3
"""A facade that keeps a second list of its routing must agree with the one it dispatches by.

A facade routes in a `switch (operation)`. One of them also keeps a map of the same operations to
the same tools, so its help can describe the tool an operation reaches without asking the switch -
the operation-parameter census reads the widest `switch (operation)` in a file as the facade's
vocabulary, and a second switch of equal width would leave which one it reads to the order the two
happen to sit in.

Two lists of the same routing drift. The suite holds the map to the catalogue and the catalogue to
the dispatch, but it cannot tell apart two tools that refuse in the same words: four of the insights
delegates answer `projectName is required`, so a case pointing at the wrong one of those four
behaves exactly like a case pointing at the right one until a workspace is loaded. The sources say
which tool each case names, and this reads them.

Silent on a file with no such map, which is every facade but one. A check that guesses at a
structure would report the other twenty as broken.
"""

import pathlib
import re
import sys

OPS = pathlib.Path(__file__).resolve().parent.parent / (
    "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/toolkit/ops")

# `case "x": return new YTool().execute(params);` - the class the dispatch reaches. The label and
# the return are separated by a line comment in every one of them, because the string carries a
# $NON-NLS marker; an expression matching only whitespace between the two finds nothing at all.
DISPATCHED = re.compile(
    r'case\s+"([a-z0-9_]+)"\s*:(?:\s*//[^\n]*)*\s*return\s+new\s+(\w+)\s*\(\s*\)\s*\.execute\s*\(',
    re.S)
# `m.put("x", YTool::new);` - the class the second list names.
DESCRIBED = re.compile(r'\.put\(\s*"([a-z0-9_]+)"\s*,\s*(\w+)::new\s*\)')


def disagreements(source: str) -> list[str]:
    """Where the two lists in one file name different tools, or different operations."""
    dispatched = dict(DISPATCHED.findall(source))
    described = dict(DESCRIBED.findall(source))
    if not described:
        return []

    found = []
    for operation in sorted(set(dispatched) | set(described)):
        goes_to = dispatched.get(operation)
        described_as = described.get(operation)
        if goes_to is None:
            found.append(f"{operation}: described as {described_as}, dispatched nowhere")
        elif described_as is None:
            found.append(f"{operation}: dispatched to {goes_to}, described nowhere")
        elif goes_to != described_as:
            found.append(f"{operation}: dispatched to {goes_to}, described as {described_as}")
    return found


def main() -> int:
    read = 0
    complaints = []
    for path in sorted(OPS.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        found = disagreements(source)
        if DESCRIBED.search(source):
            read += 1
        for line in found:
            complaints.append(f"{path.name}: {line}")

    if read == 0:
        # Said rather than passed over: the map this exists for is written by hand, and a rename
        # of the builder would leave this check sweeping nothing and reporting success.
        print("no facade keeps a second list of its routing - either one was removed or the "
              "shape this reads has changed", file=sys.stderr)
        return 1

    if complaints:
        print("a facade describes its routing differently from the way it dispatches:",
              file=sys.stderr)
        for complaint in complaints:
            print(f"  {complaint}", file=sys.stderr)
        return 1

    print(f"{read} facade(s) keep a second list of their routing, and it matches the dispatch")
    return 0


if __name__ == "__main__":
    sys.exit(main())
