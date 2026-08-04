#!/usr/bin/env python3
"""Reconcile the tool catalog in docs/tools against the group table in the source.

The catalog is written by hand - prose about what a tool is for does not generate well - but its
inventory has to match the code. It did not: the catalog carried a section claiming that
`create_project` and `get_outgoing_structures` belonged to no group and that no preset could switch
them off. Both had been grouped long before, and an outside reviewer read that section and filed it
as a live security hole. A document that says the product is unsafe when it is not costs as much as
one that says it is safe when it is not.

So this compares two lists: every tool name declared in `ToolCategory`, and every tool name the
catalog mentions in a table. It reports what one has and the other does not. It says nothing about
descriptions - those are prose and stay a human's job.

Usage:
    python scripts/check-tool-catalog.py            # report, exit 1 on drift
    python scripts/check-tool-catalog.py --list     # print the reconciled inventory
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATEGORY_SOURCE = (ROOT / "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/settings"
                   "/ToolCategory.java")
CATALOGS = [ROOT / "docs/tools/README.ru.md"]

# A tool name as it appears in either place: snake_case, no capitals.
TOOL_NAME = re.compile(r"^[a-z][a-z0-9_]*$")

# In the source, a member is a bare string literal on its own line inside the enum constant.
SOURCE_LITERAL = re.compile(r'"([a-z][a-z0-9_]*)"')

# In the catalog, a tool is the first cell of a table row, wrapped in backticks - and for the
# facades, that cell is also a link to the section that details the operations.
CATALOG_CELL = re.compile(r"^\|\s*\[?`([a-z][a-z0-9_]*)`")


def names_from_source() -> set[str]:
    """Every tool name listed in the group table.

    :return: the names ToolCategory assigns to a group
    """
    if not CATEGORY_SOURCE.exists():
        sys.exit(f"group table not found: {CATEGORY_SOURCE}")

    text = CATEGORY_SOURCE.read_text(encoding="utf-8")
    # Cut off the tail of the file - after the last enum constant come helper methods whose string
    # literals are not tool names.
    head = text.split("private static final Map<String, ToolCategory>")[0]

    names = set()
    for line in head.splitlines():
        stripped = line.strip()
        if not stripped.startswith('"'):
            continue  # group id, display name and description sit on the constant's own line
        for candidate in SOURCE_LITERAL.findall(stripped):
            if TOOL_NAME.match(candidate):
                names.add(candidate)
    return names


def names_from_catalog(path: pathlib.Path) -> set[str]:
    """Every tool name the catalog documents in a table.

    :param path: the catalog file to read
    :return: the names it mentions in the first cell of a table row
    """
    if not path.exists():
        sys.exit(f"catalog not found: {path}")

    names = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        match = CATALOG_CELL.match(line)
        if match:
            names.add(match.group(1))
    return names


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true",
                        help="print the reconciled inventory instead of only the drift")
    args = parser.parse_args()

    source = names_from_source()
    if not source:
        sys.exit("no tool names parsed from the group table - the parser is out of step with the "
                 "source, which is a defect in this script, not a clean result")

    failed = False
    for catalog in CATALOGS:
        documented = names_from_catalog(catalog)
        undocumented = sorted(source - documented)
        phantom = sorted(documented - source)
        rel = catalog.relative_to(ROOT).as_posix()

        if args.list:
            print(f"{rel}: {len(documented)} documented, {len(source)} grouped in the source")

        if undocumented:
            failed = True
            print(f"{rel}: grouped in ToolCategory but absent from the catalog:")
            for name in undocumented:
                print(f"  {name}")
        if phantom:
            failed = True
            print(f"{rel}: documented but no longer in any group - renamed or removed:")
            for name in phantom:
                print(f"  {name}")
        if not undocumented and not phantom:
            print(f"{rel}: in step with ToolCategory ({len(documented)} tools)")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
