#!/usr/bin/env python3
"""Check that no reflection failure disappears without a word.

Reflection into EDT fails in two very different ways that look identical at the catch:
the member is genuinely absent on this release - ordinary, and what probing is for - or the
call is refused because the implementation class is not public, which is a defect. Caught as
`catch (Exception ignored) {}`, both vanish, and the caller reads the resulting null as
"this EDT does not offer it".

That is not hypothetical. `IDtProject.getWorkspaceProject()` was reached by name on the
implementation for years; invoke answered IllegalAccessException, the catch discarded it, and
the project came through null. Every step that needed the project quietly degraded - primitive
types were written through the `unresolved:/` fallback instead of resolving to real platform
types - and nothing anywhere said so. It surfaced only when an unrelated check tripped over it.

So the rule is not "never swallow". It is: a swallowed reflection failure must carry a reason.
A comment inside the catch is enough - it forces whoever writes it to say why absence is
expected here, and it makes the silent ones countable.

Usage:
    python scripts/check-swallowed-reflection.py            # report, exit 1 on a bare swallow
    python scripts/check-swallowed-reflection.py --list     # every swallow found, with its verdict
"""

from __future__ import annotations

import argparse
import os
import re
import sys

SOURCE_ROOT = os.path.join("mcp", "bundles", "ru.aiedt.mcp.server", "src")

CATCH = re.compile(r"catch\s*\(\s*(?:final\s+)?[\w.]+(?:\s*\|\s*[\w.]+)*\s+(?:ignored|ignore)\s*\)")

REFLECTION = (
    ".getClass().getMethod(",
    ".getClass().getDeclaredMethod(",
    ".getClass().getField(",
    ".getClass().getDeclaredField(",
    "Class.forName(",
    ".getMethod(",
    ".getDeclaredMethod(",
    ".newInstance(",
)


def try_block(lines, catch_index):
    """The lines of the try this catch closes, found by walking back over brace depth."""
    depth = 0
    for i in range(catch_index, max(-1, catch_index - 200), -1):
        depth += lines[i].count("}") - lines[i].count("{")
        if lines[i].lstrip().startswith("try") and depth <= 0:
            return lines[i:catch_index]
    return lines[max(0, catch_index - 15):catch_index]


def catch_body(lines, catch_index):
    """The lines from the catch to the brace that closes it."""
    depth = 0
    started = False
    body = []
    for i in range(catch_index, min(len(lines), catch_index + 60)):
        body.append(lines[i])
        depth += lines[i].count("{") - lines[i].count("}")
        if "{" in lines[i]:
            started = True
        if started and depth <= 0:
            break
    return body


def reflective(block):
    text = "\n".join(block)
    return any(marker in text for marker in REFLECTION)


def explained(body):
    """A comment anywhere in the catch counts: someone had to state the reason."""
    return any("//" in line or "/*" in line for line in body)


def scan():
    findings = []
    for dirpath, _dirs, files in os.walk(SOURCE_ROOT):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                lines = handle.read().splitlines()
            for i, line in enumerate(lines):
                if not CATCH.search(line):
                    continue
                if not reflective(try_block(lines, i)):
                    continue
                findings.append({
                    "path": path.replace(os.sep, "/"),
                    "line": i + 1,
                    "explained": explained(catch_body(lines, i)),
                })
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true",
                        help="print every swallow found, explained or not")
    args = parser.parse_args()

    findings = scan()
    bare = [f for f in findings if not f["explained"]]

    if args.list:
        for f in findings:
            print("%-4s %s:%d" % ("ok" if f["explained"] else "BARE", f["path"], f["line"]))
        print()

    print("%d swallowed reflection failures, %d of them without a stated reason"
          % (len(findings), len(bare)))
    if bare:
        print()
        for f in bare:
            print("  %s:%d" % (f["path"], f["line"]))
        print()
        print("Each of these has to say why the failure is expected - a comment in the catch.")
        print("If it is NOT expected, the fix is elsewhere: a method declared by an interface")
        print("the file already imports should be called, not looked up by name.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
