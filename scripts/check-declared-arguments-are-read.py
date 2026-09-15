#!/usr/bin/env python3
"""A parameter a tool declares is a parameter that tool reads.

A client builds its call from the schema. An argument advertised there and read by nothing is a
promise the server does not keep, and nothing anywhere says so: the call succeeds, the value is
dropped, and the caller reads the success as "applied". Measured 2026-09-15 on this tree: seven such
arguments, among them `writeToModule` (the handlers were never written), `recursive` (children were
never borrowed) and three YAxUnit filters that never reached the runner.

What counts as read, in the file that declares it:

  * `extract*Argument(params, "name")` - the ordinary reader;
  * the name listed in an array or map the file then loops over - how edit_metadata applies
    `indexing` and its neighbours, which the first draft of this census mistook for unread;
  * any other standalone `"name"` literal in the same file outside the declaration itself.

Bundle-wide mentions do NOT count: `tags` is declared by yaxunit_tests and never reaches the runner,
while the same literal is read by the marker tools - a census that looks across files calls that
read and passes.

A name that is genuinely accepted elsewhere - a facade declaring what its delegate reads - is listed
in ROUTED with the tool it routes to, because the delegate reads it and the facade should not have
to repeat that.

Usage:
    python scripts/check-declared-arguments-are-read.py           # report, exit 1 on a finding
    python scripts/check-declared-arguments-are-read.py --list    # print every declaration weighed
"""

from __future__ import annotations

import collections
import pathlib
import re
import sys

BUNDLE = pathlib.Path(__file__).resolve().parent.parent / (
    "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server")
OPS = BUNDLE / "toolkit/ops"
# Shared readers: a name read here is read for every tool that calls them. TimeoutArgs reads
# timeoutSeconds for a dozen tools, PendingExecutor reads runKey, ScopeArgs reads scope - and a
# census that only looked at the declaring file called all of those unread.
SHARED = BUNDLE / "support"

DECLARATION = re.compile(
    r"\.(string|integer|boolean|number|array|stringArray|object)Property\s*\(\s*\"([A-Za-z0-9_]+)\"")
LITERAL = re.compile(r'"([A-Za-z0-9_]+)"')

# A facade declares what its delegate takes: the delegate reads it, and repeating that read in the
# facade would be the second list this project keeps refusing to grow.
FACADES = {
    "ConfigIoFacadeTool.java",
    "DiagnosticsFacadeTool.java",
    "DocsLookupFacadeTool.java",
    "EditMetadataTool.java",
    "ExtensionWorkshopTool.java",
    "InfobaseAdminFacadeTool.java",
    "InsightsFacadeTool.java",
    "ProjectAdminFacadeTool.java",
    "SecurityAuditFacadeTool.java",
    "SupportRegistryTool.java",
    "ThreeWayComparisonTool.java",
    "WorkspaceMarksFacadeTool.java",
    "CodeSearchTool.java",
    "LaunchDebuggerTool.java",
    "YaxunitTestsTool.java",
    "DcsWorkshopTool.java",
    "XdtoWorkshopTool.java",
    "MxlWorkshopTool.java",
    "ExternalObjectWorkshopTool.java",
    "ExternalDataSourceWorkshopTool.java",
}


def weigh(path: pathlib.Path):
    """Names this file declares, and names it mentions anywhere else."""
    text = path.read_text(encoding="utf-8")
    declared = [name for _, name in DECLARATION.findall(text)]
    without_declarations = DECLARATION.sub("", text)
    mentioned = collections.Counter(LITERAL.findall(without_declarations))
    mentioned.update(shared_names())
    return declared, mentioned


_SHARED_CACHE = None


def shared_names():
    """Names the shared readers under support/ take off a call for whoever asked."""
    global _SHARED_CACHE
    if _SHARED_CACHE is None:
        found = collections.Counter()
        for helper in SHARED.glob("*.java"):
            found.update(LITERAL.findall(helper.read_text(encoding="utf-8")))
        _SHARED_CACHE = found
    return _SHARED_CACHE


def main() -> int:
    listing = "--list" in sys.argv
    weighed = 0
    findings = []
    for path in sorted(OPS.glob("*.java")):
        declared, mentioned = weigh(path)
        if not declared:
            continue
        for name in declared:
            weighed += 1
            if listing:
                print(f"{path.name}: {name} ({mentioned[name]} mentions)")
            if mentioned[name] == 0 and path.name not in FACADES:
                findings.append((path.name, name))

    print(f"declarations weighed: {weighed}")
    print(f"tools treated as facades (their delegate reads the argument): {len(FACADES)}")
    if not findings:
        print("every declared argument is read by the tool that declares it")
        return 0

    print(f"\n{len(findings)} argument(s) advertised and read by nothing:\n")
    for tool, name in findings:
        print(f"  {tool}: {name}")
    print("\nEither read it, or stop declaring it: a client cannot tell the difference and the "
          "call succeeds either way.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
