#!/usr/bin/env python3
"""Every operation a facade dispatches must be named in that facade's own help.

A facade's `help` is what an agent reads to find out what the facade can do. An operation missing
from it is invisible in practice: it works, and nobody calls it. That is the same defect as a skill
that has fallen behind a release, one layer further in - and it had accumulated five instances
before anyone counted (`branch_infobase`, `read_event_log`, `start_client`,
`unpack_external_binary`, `system_enum_values`).

The check reconciles two sets read from the same file: the `case "..."` labels the dispatcher
handles, and the text the help method builds. It abstains, loudly, when it cannot find the help
method to read - a facade whose help is assembled elsewhere is not a defect, and guessing at one
would produce exactly the false accusations that made the first draft of this check useless: it
reported 83 missing operations because it had matched the wrong block of code.
"""

import pathlib
import re
import sys

OPS = pathlib.Path(__file__).resolve().parent.parent / (
    "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/toolkit/ops")

DISPATCH = re.compile(r'case "([a-z_0-9]+)":')
HELP_DECL = re.compile(r'(private|public|protected)[^\n]*\bbuildHelp\s*\(')

# `help` itself and the help topics are not dispatched operations.
NOT_AN_OPERATION = {"help", "workflow"}


def help_text(source: str):
    """The body of buildHelp, brace-matched from its DECLARATION.

    Matched from the declaration and not from the first mention of the name: `buildHelp` appears at
    its call site first, and a body taken from there is the caller's block, which contains none of
    the operation names and makes every facade look broken.
    """
    declaration = HELP_DECL.search(source)
    if not declaration:
        return None
    start = source.find("{", declaration.end())
    if start < 0:
        return None
    depth = 0
    for index in range(start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index]
    return None


def main() -> int:
    gaps = []
    abstained = []
    checked = 0
    for path in sorted(OPS.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        operations = sorted(set(DISPATCH.findall(source)) - NOT_AN_OPERATION)
        if not operations:
            continue
        body = help_text(source)
        if body is None:
            if "buildHelp" in source or "help" in operations:
                abstained.append(path.name)
            continue
        checked += 1
        missing = [op for op in operations if op not in body]
        if missing:
            gaps.append((path.name, missing))

    for name, missing in gaps:
        print("%s: dispatches but never names in its own help: %s" % (name, ", ".join(missing)))
    if abstained:
        print("abstained (help built elsewhere): %s" % ", ".join(abstained))
    if gaps:
        print("\nAdd them to the facade's help catalogue - an agent reading it cannot learn "
              "these exist.")
        return 1
    print("%d facades: every dispatched operation is named in its own help" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
