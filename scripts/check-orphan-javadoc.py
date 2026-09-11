#!/usr/bin/env python3
"""A javadoc block documents the declaration under it, or it documents nothing.

An orphan is a `/** ... */` block with no declaration after it: the next thing in the file is
another javadoc block, a closing brace, or the end of the file. It reads as documentation of
whatever follows and documents something else, or nothing at all - and nothing in the build says a
word, because a comment always compiles.

They arrive the same way every time: a member moves or is replaced and its block stays behind,
which leaves the block above some other member's block and the moved member with no header at all.

Reports by default; `--check` fails the build when any orphan stands.
"""

import pathlib
import re
import sys

# The orphans allowed to stand. It only ever goes down: a fix lowers it, and nothing may raise it.
# The 63 the census started with were traced to their members and put back; the floor is now zero.
BASELINE = 0

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = [
    ROOT / "mcp/bundles/ru.aiedt.mcp.server/src",
    ROOT / "mcp/tests/ru.aiedt.mcp.server.tests/src",
]

# What may legitimately stand between a javadoc block and the declaration it documents.
BETWEEN = re.compile(r"^\s*(@\w[\w.]*(\(.*\))?\s*)+$")
LINE_COMMENT = re.compile(r"^\s*//")

# A declaration the block can be documenting. Kept deliberately wide: the question is whether
# SOMETHING is declared, not what kind - a narrow list would report a legitimate block as an
# orphan, and a check that cries wolf gets switched off.
DECLARATION = re.compile(
    r"^\s*("
    r"(public|protected|private|static|final|abstract|synchronized|native|default|transient|volatile|strictfp)\s"
    r"|class\s|interface\s|enum\s|record\s|@interface\s"
    r"|package\s|import\s"
    r"|[\w.\[\]]+(<[^>]*>)?(\[\])*\s+\w+\s*[;=(]"
    r"|\w+\s*\("
    # An enum constant: a name, optional arguments, then a comma, a semicolon or a body.
    r"|\w+\s*(\(.*\))?\s*[,;{]\s*$"
    # The last enum constant carries no comma.
    r"|\w+\s*$"
    r")"
)


def orphans_in(text):
    """The 1-based line numbers of javadoc blocks that document nothing."""
    lines = text.split("\n")
    found = []
    index = 0
    while index < len(lines):
        stripped = lines[index].strip()
        if not stripped.startswith("/**"):
            index += 1
            continue
        start = index
        # Walk to the end of the block. A single-line /** ... */ ends on its own line.
        end = start
        if "*/" not in stripped[2:]:
            end += 1
            while end < len(lines) and "*/" not in lines[end]:
                end += 1
        if end >= len(lines):
            found.append(start + 1)
            break
        # The next thing that carries meaning.
        after = end + 1
        while after < len(lines):
            nxt = lines[after].strip()
            if not nxt or LINE_COMMENT.match(lines[after]) or BETWEEN.match(lines[after]):
                after += 1
                continue
            break
        if after >= len(lines) or lines[after].strip().startswith("/**") \
                or lines[after].strip().startswith("}") \
                or not DECLARATION.match(lines[after]):
            found.append(start + 1)
        index = end + 1
    return found


def main():
    check = "--check" in sys.argv
    total_files = 0
    hits = []
    for source in SOURCES:
        if not source.exists():
            continue
        for path in sorted(source.rglob("*.java")):
            total_files += 1
            text = path.read_text(encoding="utf-8", errors="replace")
            for line in orphans_in(text):
                hits.append((path.relative_to(ROOT).as_posix(), line))

    for path, line in hits:
        print("%s:%d: javadoc block documents no declaration" % (path, line))
    print("%d java files: %d orphan javadoc blocks" % (total_files, len(hits)))
    if check:
        if len(hits) > BASELINE:
            print("FAIL: %d orphan blocks, above the baseline of %d - a new one was added."
                  % (len(hits), BASELINE))
            return 1
        if len(hits) < BASELINE:
            print("The baseline is stale: %d orphan blocks stand, below the recorded %d. "
                  "Lower BASELINE in this script to %d so the ground that was won is held."
                  % (len(hits), BASELINE, len(hits)))
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
