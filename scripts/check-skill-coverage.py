#!/usr/bin/env python3
"""Reconciles the shipped skill against the tools the server actually has.

The README tells a user to install `skills/ai-edt`, because the plugin hands an agent tools but not
the knowledge of when to reach for which. A skill that has fallen behind the code therefore does
more than omit: it teaches the agent that a capability is absent. That has already happened twice
here - once when the skill still described a facade that had been removed, and once when it stated
that importing a `.cf` was refused on purpose, months after the import was built.

The rule this enforces is not "every tool is named". The skill deliberately teaches the facade
route, because the default Canonical preset hides the standalone names anyway: `code_search
operation=text_search`, not `search_in_code`. So a tool passes if EITHER

  - the skill names it, or
  - the Canonical preset hides it, which means a facade covers it and the skill's facade map is
    the right place to look for it.

What is left over is the real gap: a tool the server advertises, that no facade folds in, and that
the skill never mentions. An agent reading the skill has no way to learn it exists.

Usage:
    python scripts/check-skill-coverage.py           # report, exit 1 on a gap
    python scripts/check-skill-coverage.py --list    # print the reconciled inventory
"""

from __future__ import annotations

import argparse
import io
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOG = ROOT / "docs" / "tools" / "README.ru.md"
SKILL = ROOT / "skills" / "ai-edt"
PROFILE = (ROOT / "mcp" / "bundles" / "ru.aiedt.mcp.server" / "src" / "ru" / "aiedt" / "mcp"
           / "server" / "settings" / "ToolProfile.java")

# A tool name as the catalogue writes it: first cell of a table row, in backticks.
CATALOG_TOOL = re.compile(r"^\|\s*\[?`([a-z][a-z0-9_]+)`", re.M)


def read(path: pathlib.Path) -> str:
    return io.open(path, encoding="utf-8", errors="replace").read()


def catalogued() -> set[str]:
    """Every tool name, from the catalogue that check-tool-catalog.py keeps in step with the code."""
    return set(CATALOG_TOOL.findall(read(CATALOG)))


def hidden_by_preset() -> set[str]:
    """The standalone names the Canonical preset drops from tools/list.

    Read from the preset itself rather than from a second list here: a name in two places is a name
    that will disagree with itself.
    """
    text = read(PROFILE)
    start = text.index("private static Set<String> canonicalUnlisted()")
    end = text.index("\n    }", start)
    return set(re.findall(r'"([a-z][a-z0-9_]+)"', text[start:end]))


def skill_text() -> str:
    return "".join(read(path) for path in sorted(SKILL.rglob("*.md")))


def code_spans(text: str) -> list[str]:
    """The inline code spans, in order, with the prose between them discarded.

    Taken pairwise from the backticks on one line, so a span never swallows the text that follows
    it up to the next example.
    """
    spans = []
    for line in text.splitlines():
        parts = line.split("`")
        # Odd indices sit between a pair of backticks; an unpaired trailing backtick is ignored.
        spans.extend(parts[index] for index in range(1, len(parts), 2))
    return spans


def names_tool(text: str, tool: str) -> bool:
    """Whether the skill actually names a tool, rather than merely containing its letters.

    A plain substring test passes for the wrong reasons twice over: `install_extension` is inside
    `uninstall_extension`, and `step`, `resume`, `insights` and `diagnostics` are tool names that
    are also ordinary words. Either way the census reports a tool as documented that an agent could
    not find, which is the exact drift it exists to catch - a gate that can be satisfied by accident
    is not a gate.

    So a mention counts only in code form, inside backticks, and only as a whole identifier. That
    costs nothing: every one of the names the skill documents today is already written that way,
    because that is how one writes something meant to be typed into a call.

    The spans are cut out first rather than matched around the name. A pattern anchored on a pair
    of backticks happily spans the PROSE BETWEEN two separate code spans - it starts at the closing
    backtick of one and ends at the opening backtick of the next - so ordinary text sitting between
    two examples would have counted as code. That is how a gate quietly starts passing everything.
    """
    return any(re.search(r"(?<![\w.])%s(?![\w])" % re.escape(tool), span)
               for span in code_spans(text))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list", action="store_true", help="print the reconciled inventory")
    args = parser.parse_args()

    tools = catalogued()
    hidden = hidden_by_preset()
    text = skill_text()

    named = {tool for tool in tools if names_tool(text, tool)}
    covered = {tool for tool in tools if tool in hidden}
    gap = sorted(tools - named - covered)

    if args.list:
        for tool in sorted(tools):
            where = "named" if tool in named else "facade" if tool in covered else "GAP"
            print("%-38s %s" % (tool, where))

    print("%d tools: %d named in the skill, %d folded into a facade, %d unreachable"
          % (len(tools), len(named), len(covered - named), len(gap)))

    if not gap:
        return 0
    print("\nAdvertised by the server, covered by no facade, absent from the skill:")
    for tool in gap:
        print("    %s" % tool)
    print("\nAdd them to skills/ai-edt - an agent reading the skill cannot learn these exist.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
