#!/usr/bin/env python3
"""A facade that keeps a second list of its routing must agree with the one it dispatches by.

A facade routes in a `switch (operation)`. Eight of them also keep a map of the same operations to
the same tools, so their help can describe the tool an operation reaches without asking the switch -
the operation-parameter census reads the widest `switch (operation)` in a file as the facade's
vocabulary, and a second switch of equal width would leave which one it reads to the order the two
happen to sit in.

Two lists of the same routing drift. The suite holds the map to the catalogue and the catalogue to
the dispatch, but it cannot tell apart two tools that refuse in the same words: four of the insights
delegates answer `projectName is required`, so a case pointing at the wrong one of those four
behaves exactly like a case pointing at the right one until a workspace is loaded. The sources say
which tool each case names, and this reads them.

A facade that dispatches to standalone tools and keeps no map is a complaint of its own, unless it
is named below with the reason. Counting the maps instead let one file's vanish behind the others
still having theirs, which is the same shrink this refuses one operation at a time.
"""

import pathlib
import re
import sys

OPS = pathlib.Path(__file__).resolve().parent.parent / (
    "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/toolkit/ops")

# Every label the dispatch switches on, and every key the second list holds. Read apart from the
# expressions that resolve them to a class, because a label whose route this cannot read is the
# case that matters: equality between two things neither of which was recognised is not agreement.
LABEL = re.compile(r'case\s+"([a-z0-9_]+)"\s*:')
PUT_KEY = re.compile(r'\.put\(\s*"([a-z0-9_]+)"\s*,')

# `case "x": return new YTool().execute(params);` - the class the dispatch reaches. The label and
# the return are separated by a line comment in every one of them, because the string carries a
# $NON-NLS marker; an expression matching only whitespace between the two finds nothing at all.
# Labels may stack - `case "a": case "b": return new YTool()...` - so any run of them shares one
# route, and each is recorded.
#
# The return must follow the label with nothing but comments in between, and that is the rule
# rather than a convenience. A branch that does anything first is not a pass-through: the
# infobase_admin case for sync_control reads syncOperation and forwards it as operation, so the
# tool behind it declares an argument the caller does not send and does not declare the one they
# do. Describing such an operation from the delegate's schema would publish the wrong names, so
# it must not be recognised as a plain route. Loosening this expression to skip statements would
# silently start doing exactly that.
PLAIN_ROUTE = re.compile(r'new\s+(\w+)\s*\(\s*\)\s*\.execute\s*\(\s*params\s*\)')
ANY_CALL_DOWN = re.compile(r'\.execute\s*\(')
# `m.put("x", YTool::new);` - the class the second list names.
DESCRIBED = re.compile(r'\.put\(\s*"([a-z0-9_]+)"\s*,\s*(\w+)::new\s*\)')


# Anchored to the start of a line, because the words also occur in prose: the javadoc above each
# map explains why the map is not a second switch on that word, and a search that took the first
# occurrence anywhere brace-matched from inside a comment and found no routes at all.
DISPATCH_SWITCH = re.compile(r"^[ \t]*switch\s*\(\s*operation\s*\)", re.M)


def switch_body(source: str) -> str:
    """The dispatch switch alone, braces matched.

    Scoped because the rest of a facade is full of the words this reads: a `case` label in a second
    switch, and `.put("operation", operation)` building a refusal payload, which read as routing
    made the first draft accuse a file that was correct.
    """
    found = DISPATCH_SWITCH.search(source)
    if not found:
        return ""
    start = source.find("{", found.end())
    if start < 0:
        return ""
    depth = 0
    for index in range(start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index]
    return ""


def map_body(source: str) -> str:
    """The span the second list occupies, from its first entry to its last."""
    entries = list(DESCRIBED.finditer(source))
    return "" if not entries else source[entries[0].start():entries[-1].end()]


def branches(switch: str) -> list[tuple[list[str], str]]:
    """Each run of case labels and the code that follows it, to the next label."""
    marks = [(m.start(), m.end(), m.group(1)) for m in LABEL.finditer(switch)]
    out = []
    for index, (start, end, operation) in enumerate(marks):
        body = switch[end:marks[index + 1][0]] if index + 1 < len(marks) else switch[end:]
        if body.strip() == "":
            # A label stacked directly on the next one shares its body.
            out.append(([operation], None))
        else:
            out.append(([operation], body))
    # Give a stacked label the body of the first labelled branch that has one.
    resolved = []
    for index, (labels, body) in enumerate(out):
        if body is None:
            for later in out[index + 1:]:
                if later[1] is not None:
                    body = later[1]
                    break
        resolved.append((labels, body or ""))
    return resolved


def routes(switch: str) -> tuple[dict[str, str], list[str]]:
    """Operation to the tool its branch hands the call to unchanged, and any named twice.

    Unchanged is the whole point, and it is read as `execute(params)` rather than as the shape of
    the branch. A gate around the call is still a pass-through - config_io wraps two of its routes
    so a preset that switched the standalone off is honoured, and project_admin wraps one - and
    those were missed while this looked for a return sitting directly under the label. A branch
    that hands down anything else is not one: infobase_admin builds a copy for sync_control and
    forwards syncOperation as operation, so the tool behind it declares an argument the caller does
    not send, and describing that operation from its schema would publish the wrong names.
    """
    found: dict[str, str] = {}
    twice = []
    for labels, body in branches(switch):
        plain = PLAIN_ROUTE.findall(body)
        calls = ANY_CALL_DOWN.findall(body)
        if len(plain) != 1 or len(calls) != 1:
            continue
        for operation in labels:
            if operation in found:
                twice.append(operation)
            found[operation] = plain[0]
    return found, twice


def described(source: str) -> tuple[dict[str, str], list[str]]:
    """Operation to the class the second list names, and the operations named twice."""
    found: dict[str, str] = {}
    twice = []
    for operation, tool in DESCRIBED.findall(source):
        if operation in found:
            twice.append(operation)
        found[operation] = tool
    return found, twice


def disagreements(source: str) -> list[str]:
    """Where the two lists in one file name different tools, or different operations."""
    # Resolved inside the blocks they belong to, exactly like the censuses below. Read over the
    # whole file, a matching line in a comment or in a second switch resolves a label whose real
    # route cannot be read, and the completeness check then finds nothing to complain about.
    switch = switch_body(source)
    entries = map_body(source)
    dispatched, dispatched_twice = routes(switch)
    second, described_twice = described(entries)
    if not second:
        return []

    found = []
    # Recognised apart from reconciled. Both lists losing an operation together reads as agreement,
    # and an operation whose route this cannot parse leaves exactly that. So every label the switch
    # carries has to resolve to a class, and so does every key the second list holds - whatever the
    # two then say about each other.
    # A label whose route this cannot read is a complaint only when the map claims to describe it:
    # a facade handles some operations inline, and those have no delegate to name. What must not
    # happen is a map entry pointing at a route nobody can read - that is the pair going quiet
    # together, which equality between two unread things would have called agreement.
    for operation in sorted((set(LABEL.findall(switch)) & set(second)) - set(dispatched)):
        found.append(f"{operation}: is described, and its case is one this cannot read a tool "
                     "out of")
    for operation in sorted(set(PUT_KEY.findall(entries)) - set(second)):
        found.append(f"{operation}: is put into the map in a shape this cannot read a tool out of")
    for operation in sorted(set(dispatched_twice) | set(described_twice)):
        found.append(f"{operation}: is named more than once, so one of them is unreachable")

    for operation in sorted(set(dispatched) | set(second)):
        goes_to = dispatched.get(operation)
        described_as = second.get(operation)
        if goes_to is None and operation not in set(LABEL.findall(switch)):
            found.append(f"{operation}: described as {described_as}, dispatched nowhere")
        elif goes_to is None:
            continue
        elif described_as is None:
            found.append(f"{operation}: dispatched to {goes_to}, described nowhere")
        elif goes_to != described_as:
            found.append(f"{operation}: dispatched to {goes_to}, described as {described_as}")
    return found


# A facade whose operations are renamed or whose arguments are rewritten on the way down cannot
# describe itself from the delegate's schema: the delegate declares what it receives, not what the
# caller sends. Such a facade answers from the operation-parameter map instead, and keeps no second
# list here.
NO_SECOND_LIST = {
    "CodeSearchTool": "renames all eight of its operations and rewrites the arguments of five",
}


def main() -> int:
    read = 0
    complaints = []
    for path in sorted(OPS.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        found = disagreements(source)
        if DESCRIBED.search(source):
            read += 1
        elif path.stem not in NO_SECOND_LIST and routes(switch_body(source))[0]:
            # Counted facades would let one file's map vanish behind the others still having
            # theirs, which is the same shrink this check exists to refuse one operation at a
            # time. A facade that dispatches to standalone tools describes them or is named above.
            complaints.append(f"{path.name}: dispatches to standalone tools and keeps no list "
                              "describing them, so its help cannot name what an operation takes")
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
