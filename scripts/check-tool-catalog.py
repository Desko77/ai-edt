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
import xml.etree.ElementTree as ET

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import bundles  # noqa: E402  (scripts/ is not a package; the path is the import)

ROOT = bundles.REPO_ROOT
CATEGORY_SOURCE = (bundles.bundle_src(bundles.SERVER)
                   / "ru/aiedt/mcp/server/settings/ToolCategory.java")
CATALOGS = [ROOT / "docs/tools/README.ru.md"]

# A tool name as it appears in either place: snake_case, no capitals.
TOOL_NAME = re.compile(r"^[a-z][a-z0-9_]*$")

# In the source, a member is a bare string literal on its own line inside the enum constant.
SOURCE_LITERAL = re.compile(r'"([a-z][a-z0-9_]*)"')

# In the catalog, a tool is the first cell of a table row, wrapped in backticks - and for the
# facades, that cell is also a link to the section that details the operations.
CATALOG_CELL = re.compile(r"^\|\s*\[?`([a-z][a-z0-9_]*)`")

# The interface a tool of another bundle plugs into the whiteboard with.
TOOL_INTERFACE = "ru.aiedt.mcp.server.toolkit.IMcpTool"

# getName() answering a string literal: the only place in a source file where the name is a
# promise. Read from live code only - comments are stripped before this is applied.
DS_TOOL_NAME = re.compile(r'public String getName\(\)\s*\{\s*return "([a-z][a-z0-9_]+)"',
                          re.S)


def manifest_header(manifest_text: str, header: str) -> str | None:
    """The value of a main-section manifest header, continuation lines folded in.

    A MANIFEST.MF line that starts with a space continues the header before it, and a long
    Service-Component header is written that way. The main section ends at the first blank
    line; nothing below it belongs to the headers.

    :param manifest_text: the whole text of a MANIFEST.MF
    :param header: the header whose value is asked for
    :return: the folded value, or None when the manifest carries no such header
    """
    folded = None
    reading = False
    for line in manifest_text.splitlines():
        if not line.strip():
            break
        if line[0] in " \t":
            if reading:
                folded += line[1:]
            continue
        name, separator, value = line.partition(":")
        reading = bool(separator) and name.strip() == header
        if reading:
            folded = value.strip()
    return folded


def component_descriptors(header_value: str) -> list[str]:
    """The descriptor paths a Service-Component value names.

    Each entry is a path relative to the bundle root, optionally followed by component
    properties after a colon; the path is the part this census reads.

    :param header_value: the folded Service-Component value
    :return: the descriptor paths, empty entries dropped
    """
    return [entry.partition(":")[0].strip()
            for entry in header_value.split(",") if entry.strip()]


def local_name(tag: str) -> str:
    """An XML tag without its namespace: component descriptors come in several of them."""
    return tag.rsplit("}", 1)[-1]


def ds_component(descriptor: pathlib.Path) -> tuple[str | None, set[str]]:
    """The implementation class and the provided interfaces of one component descriptor.

    :param descriptor: the component XML file
    :return: the implementation class name, None when the descriptor names none, and the
             set of interfaces the component provides
    """
    implementation = None
    interfaces = set()
    for element in ET.parse(descriptor).getroot().iter():
        if local_name(element.tag) == "implementation" and implementation is None:
            implementation = element.get("class")
        elif local_name(element.tag) == "provide" and element.get("interface"):
            interfaces.add(element.get("interface"))
    return implementation, interfaces


def strip_java_comments(text: str) -> str:
    """Java text without its comments, string and character literals left intact.

    A getName() written inside a block or line comment is not a method, and a URL or a
    path inside a string literal does not open a comment.

    :param text: the whole text of a Java source file
    :return: that text with every comment replaced by a single space
    """
    out = []
    index = 0
    size = len(text)
    while index < size:
        char = text[index]
        nxt = text[index + 1] if index + 1 < size else ""
        if char == "/" and nxt == "/":
            index += 2
            while index < size and text[index] != "\n":
                index += 1
        elif char == "/" and nxt == "*":
            index += 2
            while index + 1 < size and not (text[index] == "*" and text[index + 1] == "/"):
                index += 1
            index = min(index + 2, size)
            out.append(" ")
        elif char in "\"'":
            out.append(char)
            index += 1
            while index < size and text[index] != char:
                if text[index] == "\\" and index + 1 < size:
                    out.append(text[index:index + 2])
                    index += 2
                else:
                    out.append(text[index])
                    index += 1
            if index < size:
                out.append(text[index])
                index += 1
        else:
            out.append(char)
            index += 1
    return "".join(out)


def names_from_source() -> set[str]:
    """Every tool name the code answers with: the group table and the DS-registered tools.

    A tool of another bundle counts only when its registration chain is on disk in full;
    a broken link is a defect in the tree and stops the census (see ``ds_tool_names``).

    :return: the names ToolCategory assigns to a group, plus the getName() of a tool that
             plugs into the whiteboard of another bundle on disk
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

    for bundle in bundles.production_bundles():
        if bundle == bundles.SERVER:
            continue  # the server registers its tools in code, not as components
        names |= ds_tool_names(bundles.bundle_src(bundle))
    return names


def ds_tool_names(src: pathlib.Path) -> set[str]:
    """The getName() answers of the tools a bundle registers as DS components.

    A tool is a component the manifest's Service-Component header names, whose descriptor
    provides ru.aiedt.mcp.server.toolkit.IMcpTool, and whose implementation class is on
    disk with a getName() answering a string literal. A component that provides another
    interface is another service of that bundle, not one of these tools. A declared tool
    whose descriptor, implementation or name cannot be read stops the census with an
    error: an unreadable registration is a defect in the tree, not a tool that silently
    does not exist.

    :param src: the source root of the bundle (``<bundle root>/src``)
    :return: the tool names the bundle's declarative services answer with
    """
    manifest = src.parent / "META-INF" / "MANIFEST.MF"
    if not manifest.is_file():
        return set()
    header = manifest_header(manifest.read_text(encoding="utf-8", errors="replace"),
                             "Service-Component")
    if not header:
        return set()

    names = set()
    for relative in component_descriptors(header):
        descriptor = src.parent / relative
        if not descriptor.is_file():
            sys.exit(f"component descriptor {relative} is declared by {manifest} "
                     f"but not found - the census cannot see what it registers")
        try:
            implementation, interfaces = ds_component(descriptor)
        except ET.ParseError as error:
            sys.exit(f"component descriptor {descriptor} does not parse ({error}) - "
                     f"the census cannot see what it registers")
        if TOOL_INTERFACE not in interfaces:
            continue
        if not implementation:
            sys.exit(f"component descriptor {descriptor} provides {TOOL_INTERFACE} "
                     f"without naming an implementation class")
        java = src / (implementation.replace(".", "/") + ".java")
        if not java.is_file():
            sys.exit(f"implementation {implementation} of {descriptor} has no source at "
                     f"{java} - the census cannot read its getName()")
        found = DS_TOOL_NAME.search(strip_java_comments(java.read_text(encoding="utf-8")))
        if not found:
            sys.exit(f"implementation {implementation} of {descriptor} answers no getName() "
                     f"with a string literal - the census cannot read its tool name")
        names.add(found.group(1))
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
