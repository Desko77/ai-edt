#!/usr/bin/env python3
"""Check that a facade gates what it runs, and can actually be asked to run it.

A preset switches a tool off by name, and the request router enforces that for a direct call. A
facade that hands the work to a tool in Java never passes the router, so without a check of its own
the whole preset can be walked around by asking the facade instead. That is not a hole in one
facade - it is the same hole in each of them, which is why this counts them rather than fixing one.

A file counts as a facade when it dispatches on an `operation` or an `action` and hands two or more
of the resulting branches to another tool. Each one has to mention the gate.

This lives here rather than in the JUnit suite because it reads the sources: a test inside the OSGi
runtime cannot find them, and a test that quietly skips proves nothing.

The second check is about reach. A facade that keeps a catalog of the operations it accepts refuses
anything not in it BEFORE the dispatch runs, so an operation with a `case` label and no catalog entry
is code nobody can call. Nothing says so: the facade answers "unknown operation" exactly as it would
for a typo, the tests pass, and the capability is simply absent. Caught live on 2026-08-19, on an
operation added the same hour.

Usage:
    python scripts/check-facade-gates.py           # report, exit 1 on a facade with no gate
                                                   # or an operation nobody can reach
    python scripts/check-facade-gates.py --list    # print what was found and how it was judged
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OPS = ROOT / "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/toolkit/ops"

DELEGATION = re.compile(r"new\s+\w+\(\)\.execute\(params\)")
# Work a facade does before dispatching - installing an engine, updating a database. Anything here
# has already happened by the time a late gate refuses the call.
PRE_STEP = re.compile(r"\b(ensure|install|update|create|delete)[A-Z]\w*\s*\(")
DISPATCH = re.compile(r"switch\s*\((operation|action|op|mode)\)")
# A case label in the dispatch, and an entry in the catalog a facade checks membership against.
CASE_LABEL = re.compile(r'case\s+"([a-z0-9_]+)"\s*:')
CATALOG_GUARD = re.compile(r"!\s*(\w+)\s*\.containsKey\(\s*(operation|action|op|mode)\s*\)")
GATES = ("gateIfPresetDisabled", "gatedRoute")

# A facade whose branches are all reads and that deliberately has no gate would go here, with the
# reason. Nothing is exempt today: a read is still a tool a preset may switch off, and answering it
# anyway would misreport what the preset does.
EXEMPT: dict[str, str] = {}

# Fewer than this many facades means the detection stopped matching, not that the code got simpler.
MIN_FACADES = 8


def gate_comes_first(source: str) -> str | None:
    """Returns why a facade's gate is too late, or None when it is early enough.

    Refusing a switched-off tool is worth nothing if the refusal comes after the request has already
    changed something. One facade installs the test engine into the infobase as a pre-step, and the
    gate sat after it: a call the preset had switched off still wrote to the infobase and only then
    got its error.
    """
    gate = min((source.find(g) for g in GATES if source.find(g) != -1), default=-1)
    if gate == -1:
        return None
    for pattern, what in ((DELEGATION, "hands work to a tool"), (PRE_STEP, "runs a pre-step")):
        m = pattern.search(source)
        if m and m.start() < gate:
            return f"{what} at offset {m.start()} before the gate at {gate}"
    return None


def unreachable_operations(source: str) -> list[str]:
    """Operations a facade dispatches but refuses before it ever gets there.

    A facade that keeps a catalog and answers `!CATALOG.containsKey(operation)` with "unknown
    operation" has decided its vocabulary in one place. A `case` label outside that vocabulary is
    unreachable: the refusal fires first, and it looks exactly like the refusal for a typo. Nothing
    else notices - the code compiles, the tests pass, and the capability is missing.

    Only facades with such a catalog are checked. One that dispatches straight on the string has no
    second list to disagree with, so there is nothing here to be wrong about.
    """
    guard = CATALOG_GUARD.search(source)
    if not guard:
        return []
    catalog = guard.group(1)
    # The catalog is built from a list of names somewhere in the file. Take every quoted name that
    # appears in the method building it, which is the widest reading and therefore the one that
    # will not accuse a facade wrongly.
    building = re.search(r"(?:Map<String,\s*String>|Set<String>)\s+build\w*\(\)\s*\{",
                         source)
    if not building:
        return []
    tail = source[building.end():]
    end = tail.find("\n    }")
    catalogued = set(re.findall(r'"([a-z0-9_]+)"', tail[:end if end != -1 else len(tail)]))
    if not catalogued:
        return []
    dispatched = set(CASE_LABEL.findall(dispatch_body(source)))
    # help is answered before the catalog is consulted, by every facade that has one.
    return sorted(dispatched - catalogued - {"help"} - {catalog.lower()})


def dispatch_body(source: str) -> str:
    """Just the switch that dispatches the operation, braces matched.

    Scanning the whole file instead reads every other switch as part of the vocabulary. A facade
    also switches on a help TOPIC, and those labels are not operations - counting them reported two
    of them as unreachable operations the first time this ran.
    """
    m = DISPATCH.search(source)
    if not m:
        return ""
    start = source.find("{", m.end())
    if start == -1:
        return ""
    depth = 0
    for i in range(start, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[start:i]
    return source[start:]


def facades() -> list[tuple[pathlib.Path, int]]:
    found = []
    for path in sorted(OPS.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        if not DISPATCH.search(source):
            continue
        delegations = len(DELEGATION.findall(source))
        if delegations >= 2:
            found.append((path, delegations))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="print every facade and its verdict")
    args = parser.parse_args()

    if not OPS.is_dir():
        print(f"{OPS} is not a directory - run this from the repository", file=sys.stderr)
        return 2

    found = facades()
    ungated = []
    late = []
    unreachable = []
    for path, delegations in found:
        source = path.read_text(encoding="utf-8")
        gated = any(gate in source for gate in GATES)
        name = path.name
        missing = unreachable_operations(source)
        if missing:
            unreachable.append((name, missing))
        if not gated and name not in EXEMPT:
            ungated.append(name)
        too_late = gate_comes_first(source) if gated else None
        if too_late:
            late.append((name, too_late))
        if args.list:
            verdict = "gated" if gated else EXEMPT.get(name, "NO GATE")
            if too_late:
                verdict = "GATE TOO LATE"
            print(f"{name:<32} {delegations:>2} delegations  {verdict}")

    if len(found) < MIN_FACADES:
        print(f"only {len(found)} facades recognised, expected at least {MIN_FACADES} - the "
              "detection pattern has drifted and this check is no longer checking anything",
              file=sys.stderr)
        return 1

    if ungated:
        print("these facades hand work to a tool without asking whether a preset switched it off, "
              "so the preset can be walked around by calling the facade:", file=sys.stderr)
        for name in ungated:
            print(f"  {name}", file=sys.stderr)
        return 1

    if unreachable:
        print("these facades dispatch an operation their own catalog does not list, so the call is "
              "refused as unknown before the case is ever reached - the capability is there and "
              "nobody can ask for it:", file=sys.stderr)
        for name, missing in unreachable:
            print(f"  {name}: {', '.join(missing)}", file=sys.stderr)
        return 1

    if late:
        print("these facades ask about the preset only after the request has already done "
              "something, so a switched-off call still changes state before it is refused:",
              file=sys.stderr)
        for name, why in late:
            print(f"  {name}: {why}", file=sys.stderr)
        return 1

    print(f"every facade gates what it delegates to, and every operation it dispatches can be "
          f"reached ({len(found)} facades)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
