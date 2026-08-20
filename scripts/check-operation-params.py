#!/usr/bin/env python3
"""Work out which parameters each facade operation actually reads, and keep that answer honest.

A facade advertises one schema for every operation it accepts. `edit_metadata` alone carries 113
parameters, of which any single operation reads a handful - so most of what a client is told about
on every request has nothing to do with the call it is about to make. Hiding the rest behind
per-operation help is the way out, and it needs one thing that exists nowhere today: a map from an
operation to its parameters. The operation registry knows a name, a group, a one-line summary and a
handler; which parameters the handler reads is known only to its code.

This derives that map from the sources, so it cannot drift from them:

  * a branch that hands the call to another tool (`new XxxTool().execute(params)`) takes that tool's
    schema - the delegate's own advertised parameters are exactly what the operation accepts;
  * a branch that does the work itself contributes every `extract*Argument(params, "name")` it
    reaches, following one level into private methods of the same class.

What it does NOT do is guess. An operation whose parameters cannot be established either way is
reported as such and fails the check, because an unknown answer must not be published as an empty
one - "this operation takes no parameters" is a claim, and a wrong one sends a caller looking for a
defect in the server.

Usage:
    python scripts/check-operation-params.py            # write the map, exit 1 on an unknown
    python scripts/check-operation-params.py --list     # print it
    python scripts/check-operation-params.py --check    # exit 1 without writing
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OPS = ROOT / "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/toolkit/ops"
REPORT = ROOT / "docs/tools/operation-parameters.md"
# The same map as a resource the plugin packages, so per-operation help answers from it at runtime.
# A data file rather than generated Java: generating Java from a script has cost this project two
# builds already - a $NON-NLS marker swallowing a closing bracket, and text taken out of a Java
# literal being escaped a second time on the way back in.
RESOURCE = ROOT / "mcp/bundles/ru.aiedt.mcp.server/schema/operation-parameters.tsv"
BASELINE = ROOT / "scripts/unadvertised-parameters.txt"

DISPATCH = re.compile(r"switch\s*\((operation|action|op|mode)\)")
CASE_LABEL = re.compile(r'case\s+"([a-z0-9_]+)"\s*:')
# The delegate is the tool, whatever wraps params on the way in - some facades rewrite first.
DELEGATE = re.compile(r"new\s+(\w+)\(\)\.execute\(")
EXTRACT = re.compile(r'extract\w*Argument\(\s*params\s*,\s*"([A-Za-z0-9_]+)"')
SCHEMA_PROP = re.compile(r'\.(?:string|boolean|integer|number|array|object)Property\(\s*"([A-Za-z0-9_]+)"')
CALLS = re.compile(r"\b([a-z]\w+)\s*\(")

# Operations that read nothing on purpose. Each needs a reason, because "no parameters" is the same
# shape of answer as "could not tell" and only one of them is knowledge.
KNOWN_EMPTY: dict[str, str] = {
    "help": "answers from the catalog itself; topic is read by the facade before the dispatch",
    "draw_template": "the handler refuses before reading anything - the MXL cell API is absent on "
                     "this runtime, which is an incompatibility rather than a defect",
    "set_template_cell": "same guard as draw_template",
    "merge_template_cells": "same guard as draw_template",
}

# Below this the derivation stopped matching rather than the code getting simpler.
MIN_OPERATIONS = 100


def dispatch_body(source: str) -> str:
    """The dispatch switch alone, braces matched.

    A file may switch on the same word more than once - one facade classifies its operations as
    writing or reading in a second switch that returns booleans. Taking the first match read that
    classification as the vocabulary and reported four operations as taking no parameters. The
    dispatch is the one carrying the whole vocabulary, so the widest switch wins.
    """
    widest = ""
    widest_labels = -1
    for m in DISPATCH.finditer(source):
        start = source.find("{", m.end())
        if start == -1:
            continue
        depth = 0
        body = source[start:]
        for i in range(start, len(source)):
            if source[i] == "{":
                depth += 1
            elif source[i] == "}":
                depth -= 1
                if depth == 0:
                    body = source[start:i]
                    break
        if classifier(body):
            continue
        labels = len(CASE_LABEL.findall(body))
        if labels > widest_labels:
            widest, widest_labels = body, labels
    return widest


def classifier(body: str) -> bool:
    """True for a switch that sorts operations rather than dispatching them.

    One workshop asks `isExpressionOp(op)` and `isQuerySpliceOp(op)` in switches that return nothing
    but booleans, and dispatches by other means entirely. Read as vocabulary, those switches turn
    four real operations into operations that take no parameters.
    """
    returns = re.findall(r"return\s+([^;]+);", body)
    if not returns:
        return False
    return all(value.strip() in ("true", "false") for value in returns)


def branches(body: str) -> dict[str, str]:
    """Splits a dispatch body into operation -> the source of its branch.

    Several labels may share one branch (`case "a": case "b": return ...`), and each of them gets
    the whole of it: they run the same code and therefore read the same parameters.
    """
    marks = [(m.start(), m.group(1)) for m in CASE_LABEL.finditer(body)]
    spans: list[tuple[str, str]] = []
    for index, (start, name) in enumerate(marks):
        end = marks[index + 1][0] if index + 1 < len(marks) else len(body)
        spans.append((name, body[start:end]))
    # Labels stacked on one body - `case "launch": case "debug_launch": return ...` - share it. Read
    # separately, the first of them looks like an operation that reads nothing, which is the one
    # answer this script must never produce by accident.
    found: dict[str, str] = {}
    pending: list[str] = []
    for name, text in spans:
        stripped = re.sub(r"//.*", "", text).strip()
        pending.append(name)
        if stripped.rstrip().endswith(":"):
            continue
        for label in pending:
            found[label] = text
        pending = []
    for label in pending:
        found[label] = ""
    return found


def schema_parameters(class_name: str) -> set[str] | None:
    """The parameters a tool advertises, read from its own getInputSchema."""
    path = OPS / f"{class_name}.java"
    if not path.is_file():
        return None
    source = path.read_text(encoding="utf-8")
    names = set(SCHEMA_PROP.findall(source))
    return names or None


def method_body(source: str, name: str) -> str:
    """The body of a method by name, braces matched. Empty when it is not in this file."""
    signature = re.search(r"\b" + re.escape(name) + r"\s*\([^;{]*\)\s*(?:throws [\w., ]+)?\{", source)
    if not signature:
        return ""
    start = source.find("{", signature.end() - 1)
    depth = 0
    for i in range(start, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[start:i]
    return ""


def parameters_of(branch: str, source: str) -> tuple[set[str], str]:
    """Everything a branch reads, and how it was established."""
    delegated = DELEGATE.findall(branch)
    if delegated:
        names: set[str] = set()
        for class_name in delegated:
            advertised = schema_parameters(class_name)
            if advertised:
                names |= advertised
        # A facade may rewrite the request on the way down - `execute(rewriteForTextSearcher(params))`
        # renames objectName to objectFqn and so on. The delegate's schema then names the parameters
        # AFTER the rewrite, which are not the ones a caller passes, so the rewrite's own reads have
        # to come along or the map would list names the facade does not accept.
        rewrites = set(re.findall(r"\.execute\(\s*(\w+)\s*\(", branch))
        for rewrite in rewrites:
            body = method_body(source, rewrite)
            if body:
                names |= set(EXTRACT.findall(body))
                names |= set(re.findall(r'params\.get\(\s*"([A-Za-z0-9_]+)"', body))
        if names:
            how = "delegate " + ", ".join(sorted(set(delegated)))
            if rewrites:
                how += " through " + ", ".join(sorted(rewrites))
            return names, how
    # A delegate built on one line and run on the next - `EditFormTool t = new EditFormTool(); ...
    # t.execute(forwarded)` - is the same delegation with the two halves apart, and matching only the
    # single-expression form left four operations looking unknowable.
    if ".execute(" in branch:
        for class_name in set(re.findall(r"new\s+(\w+Tool)\(\)", branch)):
            advertised = schema_parameters(class_name)
            if advertised:
                return advertised, f"delegate {class_name}"
    names = set(EXTRACT.findall(branch))
    # One level into a helper the branch hands `params` to - and ONLY such a helper. Following every
    # method called from the branch pulled in what unrelated ones read: call_hierarchy came back
    # claiming `operation` and `topic`, which are the help branch's, because both call the same
    # formatting helper. A wrong parameter list is worse than a missing one, and harder to notice.
    for called in set(re.findall(r"\b(\w+)\s*\(\s*params\b", branch)):
        if called in ("if", "for", "while", "switch", "return", "catch", "new"):
            continue
        body = method_body(source, called)
        if body:
            names |= set(EXTRACT.findall(body))
    if names:
        return names, "read in place"
    # A facade that reads its arguments BEFORE dispatching and hands them down as locals - which is
    # most of the form and debugger ones. The branch names the locals it passes, and each local was
    # bound to a parameter further up, so the mapping is exact rather than the union of everything
    # the facade reads. Attributing the whole union to every operation would be the guess this
    # script exists to avoid.
    bound = locals_bound_to_parameters(source)
    if bound:
        used = {bound[word] for word in set(re.findall(r"\b(\w+)\b", branch)) if word in bound}
        if used:
            return used, "passed in as locals"
    return set(), ""


def locals_bound_to_parameters(source: str) -> dict[str, str]:
    """Local variables that hold a request parameter, mapped to the parameter they hold.

    Two steps, because a facade routinely turns a parameter into something else before dispatching:
    `IProject project = ProjectResolver.resolve(projectName)`. The branch then mentions `project` and
    never the parameter it came from, and stopping at one step reports such an operation as reading
    nothing at all.
    """
    bound: dict[str, str] = {}
    for match in re.finditer(
            r"\b(\w+)\s*=\s*[^;]*?extract\w*Argument\(\s*params\s*,\s*\"([A-Za-z0-9_]+)\"", source):
        bound[match.group(1)] = match.group(2)
    for match in re.finditer(r"\b(\w+)\s*=\s*([^;]+);", source):
        target, expression = match.group(1), match.group(2)
        if target in bound or "extract" in expression:
            continue
        for word in re.findall(r"\b(\w+)\b", expression):
            if word in bound:
                bound[target] = bound[word]
                break
    return bound


def dispatches_otherwise() -> list[str]:
    """Tools that accept an operation but do not dispatch it with a switch.

    Named rather than skipped in silence: their operations are absent from the map, and a map that
    does not say what it leaves out reads as complete.
    """
    skipped = []
    for path in sorted(OPS.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        if '"operation"' not in source:
            continue
        if dispatch_body(source):
            continue
        if registry_operations(path, source):
            # Covered the other way - its operations come from a handler registry.
            continue
        if len(CASE_LABEL.findall(source)) >= 3:
            skipped.append(path.stem)
    return skipped


REGISTRY_ENTRY = re.compile(
    r'reg\(\s*\w+\s*,\s*"([a-z0-9_]+)"\s*,\s*"[^"]*"\s*,\s*"[^"]*"\s*,\s*\w+\s*->\s*(?:(\w+)\.)?(\w+)\(')
FIELD_TYPE = re.compile(r"\b(?:private|protected|public)\s+(?:final\s+)?(\w+)\s+(\w+)\s*[=;]")
EXTRACT_ANY = re.compile(r'extract\w*Argument\(\s*\w+\s*,\s*"([A-Za-z0-9_]+)"')


def registry_operations(path: pathlib.Path, source: str) -> dict[str, dict[str, object]]:
    """Operations declared in a handler registry rather than a dispatch switch.

    The largest facade of all keeps `op name -> group + help + handler` in a map and calls the
    handler through a lambda. Its 161 operations carry the schema this whole exercise exists to
    shrink, so leaving them out would make the map cover everything except the part that matters.
    """
    entries = REGISTRY_ENTRY.findall(source)
    if not entries:
        return {}
    fields = {name: type_name for type_name, name in FIELD_TYPE.findall(source)}
    found: dict[str, dict[str, object]] = {}
    for operation, receiver, method in entries:
        names: set[str] = set()
        how = ""
        holder_source = source
        holder_name = path.stem
        if receiver and receiver in fields:
            holder = OPS / f"{fields[receiver]}.java"
            if holder.is_file():
                holder_source = holder.read_text(encoding="utf-8")
                holder_name = fields[receiver]
        body = method_body(holder_source, method)
        if body:
            names = set(EXTRACT_ANY.findall(body))
            how = f"handler {holder_name}.{method}"
            if not names:
                # A handler that only forwards - `delegateToEditForm("add_field", p)` hands the whole
                # request to another facade under that name. The parameters are that facade's, and
                # the same resolution the switch branches get applies here.
                names, forwarded = parameters_of(body, holder_source)
                how = f"handler {holder_name}.{method}" + (f", {forwarded}" if forwarded else "")
        found[f"{path.stem}:{operation}"] = {
            "facade": path.stem,
            "operation": operation,
            "parameters": sorted(names),
            "how": how,
        }
    return found


def collect() -> dict[str, dict[str, object]]:
    result: dict[str, dict[str, object]] = {}
    for path in sorted(OPS.glob("*.java")):
        source = path.read_text(encoding="utf-8")
        result.update(registry_operations(path, source))
        body = dispatch_body(source)
        if not body:
            continue
        for operation, branch in branches(body).items():
            names, how = parameters_of(branch, source)
            key = f"{path.stem}:{operation}"
            result[key] = {
                "facade": path.stem,
                "operation": operation,
                "parameters": sorted(names),
                "how": how,
            }
    return result


def unadvertised(found: dict[str, dict[str, object]]) -> list[str]:
    """Parameters a handler reads that its facade's schema never mentions.

    The schema is the only place a client learns a parameter exists, so one that is read but not
    advertised is a capability nobody can reach - the same hole the facade gate already closes for
    operations, one level down. Found the day this map first existed: five of them were the merge
    arguments added hours earlier, which made merging undiscoverable through the facade that is what
    the default preset shows.
    """
    by_facade: dict[str, set[str]] = {}
    for row in found.values():
        by_facade.setdefault(str(row["facade"]), set()).update(row["parameters"])
    missing = []
    for facade, params in sorted(by_facade.items()):
        path = OPS / f"{facade}.java"
        if not path.is_file():
            continue
        advertised = set(SCHEMA_PROP.findall(path.read_text(encoding="utf-8")))
        if not advertised:
            continue
        for name in sorted(params):
            if name not in advertised:
                missing.append(f"{facade}:{name}")
    return missing


def baseline() -> set[str]:
    """The unadvertised parameters already there when the check was introduced.

    A count that may only go down. Failing on all of them at once would have meant either a red
    build for weeks or a check nobody turns on - and a check nobody turns on stops new ones from
    being noticed, which is the whole point.
    """
    if not BASELINE.is_file():
        return set()
    return {line.strip() for line in BASELINE.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")}


def unknowns(found: dict[str, dict[str, object]]) -> list[str]:
    missing = []
    for key, row in sorted(found.items()):
        if row["parameters"]:
            continue
        if row["operation"] in KNOWN_EMPTY:
            continue
        missing.append(key)
    return missing


def write_report(found: dict[str, dict[str, object]]) -> None:
    lines = [
        "# Параметры операций",
        "",
        "Выведено из исходников скриптом `scripts/check-operation-params.py`, вручную не правится.",
        "Ветка, отдающая работу другому инструменту, берёт его схему; ветка, делающая работу сама,",
        "перечисляет то, что читает из `params`.",
        "",
        "| Фасад | Операция | Параметры | Откуда |",
        "|---|---|---|---|",
    ]
    for _, row in sorted(found.items()):
        params = ", ".join(f"`{p}`" for p in row["parameters"]) or "-"
        how = row["how"] or "не установлено"
        lines.append(f"| `{row['facade']}` | `{row['operation']}` | {params} | {how} |")
    skipped = dispatches_otherwise()
    if skipped:
        lines.append("")
        lines.append("## Что сюда не попало")
        lines.append("")
        lines.append("Инструменты, которые принимают операцию, но маршрутизируют её не через "
                     "`switch`, и потому не выводятся этим скриптом: "
                     + ", ".join(f"`{name}`" for name in skipped) + ".")
    lines.append("")
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(lines), encoding="utf-8")

    rows = ["# derived by scripts/check-operation-params.py - do not edit"]
    for _, row in sorted(found.items()):
        rows.append("\t".join((str(row["facade"]), str(row["operation"]),
                               ",".join(row["parameters"]), str(row["how"]))))
    rows.append("")
    RESOURCE.parent.mkdir(parents=True, exist_ok=True)
    RESOURCE.write_text("\n".join(rows), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    found = collect()
    if len(found) < MIN_OPERATIONS:
        print(f"only {len(found)} operations found - the derivation stopped matching", file=sys.stderr)
        return 1

    if args.list:
        for _, row in sorted(found.items()):
            print(f"{row['facade']}:{row['operation']} -> {', '.join(row['parameters']) or '(none)'}"
                  f"  [{row['how'] or 'unknown'}]")

    missing = unknowns(found)
    if not args.check:
        write_report(found)

    hidden = unadvertised(found)
    known = baseline()
    fresh = [name for name in hidden if name not in known]
    covered = len(found) - len(missing)
    skipped = dispatches_otherwise()
    print(f"{len(found)} operations, {covered} with parameters established")
    if skipped:
        print("not covered - these dispatch without a switch: " + ", ".join(skipped))
    print(f"{len(hidden)} parameters read but not advertised by their facade "
          f"({len(fresh)} of them new)")
    if fresh:
        print("these are read but a client cannot discover them:", file=sys.stderr)
        for name in fresh:
            print(f"  {name}", file=sys.stderr)
        print("advertise them in the facade schema, or add them to "
              "scripts/unadvertised-parameters.txt with a reason", file=sys.stderr)
        return 1
    if missing:
        print(f"{len(missing)} operations whose parameters could not be established:", file=sys.stderr)
        for key in missing[:20]:
            print(f"  {key}", file=sys.stderr)
        if len(missing) > 20:
            print(f"  ... and {len(missing) - 20} more", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
