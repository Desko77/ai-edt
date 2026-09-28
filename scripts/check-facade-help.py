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

Two things the abstention must not cover. A method whose name only STARTS with `buildHelp` is that
facade's help just as much as `buildHelp` is, and reading only the exact spelling left a whole
facade outside the census while the run reported it as one whose help is built elsewhere. And a
declaration the checker found but could not read is not the same as a declaration that is not there:
the first means the reading failed, and an operation set compared against nothing would pass. Only
the second is an abstention.

An operation is named in whichever of its two spellings the facade's help uses. A dispatcher is
handed a name the caller wrote, and `JsonUtils.normalizeOperationToken` folds the camelCase form into
the snake_case label, so both reach the same `case`; a help that writes `addField` for the label
`add_field` has named that operation, and reading only the label would accuse the facade of hiding
six operations it documents.
"""

import pathlib
import re
import sys

OPS = pathlib.Path(__file__).resolve().parent.parent / (
    "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/toolkit/ops")

DISPATCH = re.compile(r'case "([a-z_0-9]+)":')

# The help method is not always called exactly `buildHelp`: a facade whose help is the response
# body names it `buildHelpResponse`. Matched with a modifier in front, which a call site does not
# have, so the declaration is found and the caller's block is not.
HELP_DECL = re.compile(r'(private|public|protected)[^\n]*\bbuildHelp\w*\s*\(')

# `help` itself and the help topics are not dispatched operations.
NOT_AN_OPERATION = {"help", "workflow"}


class HelpUnreadable(Exception):
    """A help declaration is here and its body could not be read from it."""


def spellings(operation: str):
    """The spellings one operation may be named by: its label, and the camelCase form of it.

    The camelCase form is built the way the dispatcher reads it back - the first segment stays in
    lower case, the following ones start with a capital, so `add_field` reads `addField`, which is
    what a caller writes and what the help of a facade that documents in that spelling says.

    @param operation an operation label as the dispatcher writes it
    @return both spellings
    """
    parts = operation.split("_")
    camel = parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])
    return (operation, camel)


def next_code(source: str, index: int) -> int:
    """The next character that is code: literals and comments are stepped over.

    The body is cut by counting braces, and a help string is full of them - a JSON example, a table
    snippet. Counted as code, a lone brace in a string ends the body early or leaves it unclosed,
    and either reads as a facade whose help is wrong. Both a `{` and a `}` appear in the run of
    characters this must not look at: a quoted string or character, and a comment.

    @param source the text being read
    @param index where the reading is
    @return where the next brace that counts begins
    """
    character = source[index]
    if character in "\"'":
        index += 1
        while index < len(source) and source[index] != character:
            index += 2 if source[index] == "\\" else 1
        return index + 1
    if source.startswith("//", index):
        return source.find("\n", index) + 1 or len(source)
    if source.startswith("/*", index):
        return source.find("*/", index) + 2
    return index + 1


def help_text(source: str):
    """The body of the facade's help method, brace-matched from its DECLARATION.

    Matched from the declaration and not from the first mention of the name: `buildHelp` appears at
    its call site first, and a body taken from there is the caller's block, which contains none of
    the operation names and makes every facade look broken.

    @param source the facade's source text
    @return the body, or None when no help method is declared in this file
    @raises HelpUnreadable when such a declaration is here and the body could not be read
    """
    declaration = HELP_DECL.search(source)
    if not declaration:
        return None
    start = source.find("{", declaration.end())
    # A semicolon between the declaration and the brace means the declaration ended before it: the
    # method has no body, and the block that follows belongs to another member. Read as the help, it
    # would compare the operations against a stranger's code and pass.
    if start < 0 or ";" in source[declaration.end():start]:
        raise HelpUnreadable("the declaration at character %d has no body" % declaration.start())
    depth = 0
    index = start
    while index < len(source):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index]
        index = next_code(source, index)
    raise HelpUnreadable("the body starting at character %d is not closed" % start)


def main() -> int:
    gaps = []
    abstained = []
    unreadable = []
    checked = 0
    for path in sorted(OPS.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        operations = sorted(set(DISPATCH.findall(source)) - NOT_AN_OPERATION)
        if not operations:
            continue
        try:
            body = help_text(source)
        except HelpUnreadable as failure:
            unreadable.append((path.name, str(failure)))
            continue
        if body is None:
            if "buildHelp" in source or "help" in operations:
                abstained.append(path.name)
            continue
        checked += 1
        missing = [op for op in operations
                   if not any(reading in body for reading in spellings(op))]
        if missing:
            gaps.append((path.name, missing))

    for name, missing in gaps:
        print("%s: dispatches but never names in its own help: %s" % (name, ", ".join(missing)))
    for name, failure in unreadable:
        print("%s: declares help here and its body could not be read - %s" % (name, failure))
    if abstained:
        print("abstained (help built elsewhere): %s" % ", ".join(abstained))
    if gaps:
        print("\nAdd them to the facade's help catalogue - an agent reading it cannot learn "
              "these exist.")
    if unreadable:
        print("\nNothing was compared for the facades above: read the declaration, or teach this "
              "check where their help is built.")
    if gaps or unreadable:
        return 1
    print("%d facades: every dispatched operation is named in its own help" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
