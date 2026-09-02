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
# Every helper that reads an argument by name, and every helper of the same shape that does not.
# Both lists are kept because neither side can be guessed: `addTypedCollectionChild(params,
# "Dimension")` passes a collection and `branchArgument(params, onDisk)` a value, so matching "any
# call taking the map and a literal" would put those in the map and in the help; while reading
# `extract*` alone missed 81 reads through `required`, and adding that one still left `strictInt`.
# A read set short of what an operation needs is what makes the guard refuse a call that works, so
# a helper of this shape belonging to neither list fails the check - see unclassified_helpers.
READER_HELPERS = ("required", "strictFlag", "strictInt", "parseInt", "optionalInt", "boolArg",
                  "intArg", "isTrue", "parseNumericArgument", "objectArgumentProblem")
NOT_READERS = ("addContentEntry", "addTypedCollectionChild", "bind", "unbind", "branchArgument",
               "doBorrow", "pathOf", "put", "removeContentEntry", "withMode")
READERS = r"\b(?:extract\w*|" + "|".join(READER_HELPERS) + r")"
HELPER_SIGNATURE = re.compile(r"\b(\w+)\(Map<String, ?String> \w+, String \w+")
SRC = ROOT / "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server"


def unclassified_helpers() -> list[str]:
    """Helpers taking the argument map and a name that neither list accounts for."""
    seen: set[str] = set()
    for path in sorted(SRC.rglob("*.java")):
        seen |= set(HELPER_SIGNATURE.findall(path.read_text(encoding="utf-8")))
    return sorted(name for name in seen
                  if not name.startswith("extract")
                  and name not in READER_HELPERS and name not in NOT_READERS)
EXTRACT = re.compile(READERS + r'\(\s*params\s*,\s*(?://[^\n]*\n\s*)*"([A-Za-z0-9_]+)"')
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


LOOP_OVER_CONST = re.compile(r"for\s*\(\s*(?:final\s+)?String\s+(\w+)\s*:\s*(\w+)\s*\)")
CONST_ARRAY = re.compile(r"static final String\[\]\s+(\w+)\s*=\s*\{([^}]*)\}", re.S)


def names_read_through_a_loop(body: str, source: str) -> set[str]:
    """Parameter names a body reads by looping over a constant list of them.

    `for (String prop : ATTRIBUTE_FEATURE_PROPERTIES) { ... extract(params, prop) ... }` reads five
    parameters and names none of them at the point of reading. A scan for a literal beside the map
    finds nothing there, so those five were absent from the map and the guard refused every call
    that set fillChecking on an attribute.
    """
    constants = {name: re.findall(r'"([A-Za-z0-9_]+)"', values)
                 for name, values in CONST_ARRAY.findall(source)}
    names: set[str] = set()
    for variable, constant in LOOP_OVER_CONST.findall(body):
        if constant not in constants:
            continue
        if re.search(READERS + r"\(\s*\w+\s*,\s*" + re.escape(variable) + r"\b", body):
            names |= set(constants[constant])
    return names


def method_body(source: str, name: str) -> str:
    """The body of a method by name, braces matched. Empty when it is not in this file.

    Braces are matched with strings, character literals and comments skipped: a message ending in
    a brace closed the body early, and everything the method read after that point went missing -
    which for an operation means a guard that refuses the arguments it did not see.
    """
    signature = re.search(r"\b" + re.escape(name) + r"\s*\([^;{]*\)\s*(?:throws [\w., ]+)?\{", source)
    if not signature:
        return ""
    start = source.find("{", signature.end() - 1)
    end = balanced_span(source, start, "{", "}")
    return source[start:end - 1] if end > 0 else ""


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
    # The map need not be the first argument - `applyParameterFields(parameter, params, project)`
    # hands it along just as much - and a consuming match swallows a nested call, so the name is
    # taken with a lookahead over the argument text.
    for called in set(re.findall(r"\b(\w+)\s*\((?=[^;{}]*\bparams\b)", branch)):
        if called in ("if", "for", "while", "switch", "return", "catch", "new"):
            continue
        body = method_body(source, called)
        holder = source
        if not body:
            # A static helper on another class - `EditMetadataTool.applyAttributeFeatureProperties(
            # attribute, params, applied)`. What it reads is in that file, and looking only in this
            # one lost the five properties it applies.
            owner = re.search(r"\b([A-Z]\w+)\s*\.\s*" + re.escape(called) + r"\s*\(", branch)
            if owner:
                path = OPS / f"{owner.group(1)}.java"
                if path.is_file():
                    holder = path.read_text(encoding="utf-8")
                    body = method_body(holder, called)
        if body:
            names |= set(EXTRACT.findall(body))
            names |= names_read_through_a_loop(body, holder)
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
            r"\b(\w+)\s*=\s*[^;]*?extract\w*\(\s*params\s*,\s*\"([A-Za-z0-9_]+)\"", source):
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


REG_CALL = re.compile(r'\breg\(\s*\w+\s*,\s*"([a-z0-9_]+)"')
SIMPLE_CALL = re.compile(r"^(?:(\w+)\.)?(\w+)\s*\(")
FIELD_TYPE = re.compile(r"\b(?:private|protected|public)\s+(?:final\s+)?(\w+)\s+(\w+)\s*[=;]")
EXTRACT_ANY = re.compile(READERS + r'\(\s*\w+\s*,\s*(?://[^\n]*\n\s*)*"([A-Za-z0-9_]+)"')


def balanced_span(source: str, start: int, opener: str, closer: str) -> int:
    """The index just past the closer matching the opener at `start`, or -1.

    Java carries brackets inside strings, character literals and comments - a help text ending in
    a parenthesis, a regex, a brace in a sentence - so those are skipped rather than counted.
    """
    depth = 0
    i = start
    n = len(source)
    while i < n:
        c = source[i]
        if c == '"' or c == "'":
            quote = c
            i += 1
            while i < n and source[i] != quote:
                i += 2 if source[i] == "\\" else 1
        elif c == "/" and i + 1 < n and source[i + 1] == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue
        elif c == "/" and i + 1 < n and source[i + 1] == "*":
            closed = source.find("*/", i + 2)
            if closed < 0:
                return -1
            i = closed + 1
        elif c == opener:
            depth += 1
        elif c == closer:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return -1


def outside_strings(text: str, token: str) -> int:
    """Where `token` first appears outside a string, a character literal or a comment, or -1."""
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"' or c == "'":
            quote = c
            i += 1
            while i < n and text[i] != quote:
                i += 2 if text[i] == "\\" else 1
        elif c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            closed = text.find("*/", i + 2)
            if closed < 0:
                return -1
            i = closed + 1
        elif text.startswith(token, i):
            return i
        i += 1
    return -1


def registry_entries(source: str) -> list[tuple[str, str]]:
    """Every `reg(...)` registration and the body of the lambda it hands the call to.

    Two call shapes are in use - a name with a group and a help string, and a name on its own - and
    the lambda is written either as an expression or as a block. Matching the whole call and taking
    what follows the arrow covers all four. A regex that expected a call immediately after the
    arrow read 103 of 181 registrations and dropped the other 78 without naming one of them: the
    three that delegate to a standalone tool, and every operation of the composition workshop.
    """
    entries = []
    for match in REG_CALL.finditer(source):
        opened = source.index("(", match.start())
        body = lambda_body_at(source, opened)
        if body is not None:
            entries.append((match.group(1), body))
    entries.extend(looped_registrations(source))
    return entries


def lambda_body_at(source: str, opened: int) -> str | None:
    """The body of the lambda inside the call whose opening parenthesis is at `opened`."""
    end = balanced_span(source, opened, "(", ")")
    if end < 0:
        return None
    call = source[opened:end]
    arrow = outside_strings(call, "->")
    if arrow < 0:
        return None
    body = call[arrow + 2:].strip()
    if body.startswith("{"):
        closed = balanced_span(body, 0, "{", "}")
        if closed > 0:
            body = body[1:closed - 1]
    return body


LOOP_HEAD = re.compile(r"for\s*\(\s*(?:final\s+)?String\s+(\w+)\s*:\s*Arrays\.asList\(")


def looped_registrations(source: str) -> list[tuple[str, str]]:
    """Registrations whose name is a loop variable rather than a literal.

    `edit_metadata` registers its five extension operations and 51 composition ones by running one
    `reg` call over a list of names. The name is then an identifier, so a scan for a string literal
    finds nothing - and those 56 are on the one facade that does consult the map, which is where a
    missing row means an argument goes unchecked and help has no answer.
    """
    entries: list[tuple[str, str]] = []
    for head in LOOP_HEAD.finditer(source):
        variable = head.group(1)
        list_open = source.index("(", head.end() - 1)
        list_end = balanced_span(source, list_open, "(", ")")
        if list_end < 0:
            continue
        names = re.findall(r'"([a-z0-9_]+)"', source[list_open:list_end])
        brace = source.find("{", list_end)
        if brace < 0 or not names:
            continue
        block_end = balanced_span(source, brace, "{", "}")
        if block_end < 0:
            continue
        block = source[brace:block_end]
        call = re.search(r"\breg\(\s*\w+\s*,\s*" + re.escape(variable) + r"\s*,", block)
        if not call:
            continue
        body = lambda_body_at(block, block.index("(", call.start()))
        if body is None:
            continue
        for name in names:
            entries.append((name, body))
    return entries


CONTROL_WORDS = ("if", "for", "while", "switch", "return", "catch", "new", "synchronized")
# Keys the call carries rather than the operation - they steer dispatch, preview and batching, and
# UnreadArguments exempts every one of them. A facade reads them once for all its operations, so
# attributing them to each adds nothing the guard uses and puts seven names of plumbing in front of
# a caller who asked what one operation reads. Subtracted from the facade-wide set only: an
# operation that reads one of these itself keeps it.
CALL_KEYS = frozenset(("operation", "batch", "operations", "stopOnError", "runKey",
                       "timeoutSeconds", "topic", "confirm"))
ALIAS_PUT = re.compile(r'\bput\(\s*"([a-z0-9_]+)"\s*,\s*"([a-z0-9_]+)"\s*\)')


def forwarded_operation(handler: str, holder_source: str, operation: str) -> tuple[str, str] | None:
    """The facade and operation a handler hands the whole call to under another name.

    `edit_metadata` runs its 51 composition operations by renaming the operation and passing the
    call to the composition workshop. Reading that as "delegates to the workshop" and taking the
    workshop's whole schema gave each of the 51 the parameters of all the others - 62 of them for
    an operation that reads three. The alias map beside the handler says which single operation the
    call becomes, and that operation's own row is the answer.
    """
    if ".execute(" not in handler or 'put("operation"' not in handler:
        return None
    targets = re.findall(r"new\s+(\w+Tool)\(\)", handler)
    if not targets:
        return None
    aliases = dict(ALIAS_PUT.findall(holder_source))
    return targets[0], aliases.get(operation, operation)


def facade_common_reads(source: str, handlers: set[str], depth: int = 5) -> set[str]:
    """What a facade reads on the way from `execute` down to a registry handler.

    A facade that resolves the thing before dispatching reads the arguments that name it and hands
    the handler the resolved object, so no handler mentions them and every call carries them. The
    composition workshop takes objectName, templateName, formFqn and nestedSchemaName two calls
    below `execute` and passes a schema down; attributed to nothing, the guard would refuse the one
    argument that says which schema to work on, on every operation the facade has.

    Registry handlers are left out of the walk - what they read is theirs alone, and folding it in
    would give every operation the parameters of all the others.
    """
    names: set[str] = set()
    seen: set[str] = set()
    frontier = ["execute"]
    for _ in range(depth):
        following: list[str] = []
        for method in frontier:
            if method in seen or method in handlers or method in CONTROL_WORDS:
                continue
            seen.add(method)
            body = method_body(source, method)
            if not body:
                continue
            names |= set(EXTRACT.findall(body))
            # Lookahead rather than a consuming match: `resultRef.set(dispatch(op, params))` has
            # the call that matters nested inside another, and a consuming match swallows it.
            following.extend(re.findall(r"\b(\w+)\s*\((?=[^;{}]*\bparams\b)", body))
        frontier = following
    return names


def registration_read_set(body: str, source: str, facade: str, fields: dict[str, str],
                          operation: str = "") -> tuple[set[str], str, tuple[str, str] | None]:
    """What one registration reads, and how that was established.

    Everything established is added together rather than chosen between. For the guard that refuses
    unread arguments a set that is too wide only refuses less; one that is too narrow refuses calls
    that work, which is the failure this whole map exists to prevent.
    """
    names: set[str] = set()
    how = ""
    simple = SIMPLE_CALL.match(body.strip())
    if simple:
        receiver, method = simple.group(1), simple.group(2)
        holder_source, holder_name = source, facade
        if receiver and receiver in fields:
            holder = OPS / f"{fields[receiver]}.java"
            if holder.is_file():
                holder_source = holder.read_text(encoding="utf-8")
                holder_name = fields[receiver]
        handler = method_body(holder_source, method)
        if handler:
            renamed = forwarded_operation(handler, holder_source, operation)
            names = set(EXTRACT_ANY.findall(handler))
            names |= names_read_through_a_loop(handler, holder_source)
            how = f"handler {holder_name}.{method}"
            forwarded_names, forwarded = parameters_of(handler, holder_source)
            if forwarded_names:
                names |= forwarded_names
                if forwarded:
                    how += f", {forwarded}"
            if renamed:
                how += f", renamed to {renamed[1]}"
            return names, how, renamed
    # The lambda does the work itself, or checks a preset gate before handing the call to a
    # standalone tool - `new AttributeAdder().execute(p)`, whose own schema names the parameters.
    names = set(EXTRACT_ANY.findall(body))
    delegated, delegation = parameters_of(body, source)
    if delegated:
        names |= delegated
        how = f"registration, {delegation}" if delegation else "registration"
    elif names:
        how = "registration"
    return names, how, None


def registry_operations(path: pathlib.Path, source: str) -> dict[str, dict[str, object]]:
    """Operations declared in a handler registry rather than a dispatch switch.

    The largest facade of all keeps `op name -> group + help + handler` in a map and calls the
    handler through a lambda. Its 161 operations carry the schema this whole exercise exists to
    shrink, so leaving them out would make the map cover everything except the part that matters.
    """
    entries = registry_entries(source)
    if not entries:
        return {}
    fields = {name: type_name for type_name, name in FIELD_TYPE.findall(source)}
    handler_methods = set()
    for _, registration in entries:
        simple = SIMPLE_CALL.match(registration.strip())
        if simple:
            handler_methods.add(simple.group(2))
    common = facade_common_reads(source, handler_methods) - CALL_KEYS
    found: dict[str, dict[str, object]] = {}
    for operation, body in entries:
        names, how, renamed = registration_read_set(body, source, path.stem, fields, operation)
        if common:
            names = set(names) | common
        found[f"{path.stem}:{operation}"] = {
            "renamed": renamed,
            "common": sorted(common),
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
        # A facade that dispatches with a switch reads its shared arguments before it, exactly as a
        # registry one does - the form facade takes formFqn there and every branch works on the form
        # it found. Left out, the operations another facade delegates to these lose the argument
        # that names the form, and the guard refuses every call carrying it.
        common = facade_common_reads(source, set()) - CALL_KEYS
        for operation, branch in branches(body).items():
            names, how = parameters_of(branch, source)
            names = set(names) | common
            key = f"{path.stem}:{operation}"
            result[key] = {
                "facade": path.stem,
                "operation": operation,
                "parameters": sorted(names),
                "how": how,
            }
    resolve_renamed(result)
    return result


def resolve_renamed(rows: dict[str, dict[str, object]]) -> None:
    """Fill in the rows whose call is handed to another facade under another name.

    Done after every facade has been read, because the target row is another facade's and may not
    exist yet while this one is being worked out.
    """
    for row in rows.values():
        renamed = row.pop("renamed", None)
        common = row.pop("common", [])
        if not renamed:
            continue
        target = rows.get(f"{renamed[0]}:{renamed[1]}")
        if not target:
            # The rename points at a row nobody produced - the target facade dispatches some other
            # way, or the alias names an operation that is gone. Whatever the reason, the answer is
            # not a set of three: what the handler itself reads is already in place, and leaving it
            # is the difference between a guard that lets a working call through and one that
            # refuses it.
            row["how"] += " - target row not found, read from the handler instead"
            continue
        row["parameters"] = sorted(set(target["parameters"]) | set(common))


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


def narrowed(found: dict[str, dict[str, object]]) -> list[str]:
    """Operations that would come out reading LESS than the committed map says they read.

    Every other check here asks whether the map is complete. This one asks whether a change to the
    derivation took something away, which is the failure with teeth: the guard refuses an argument
    the map does not name, so a parameter that drops out of a row turns a call that worked into a
    refusal. Caught this way once already - resolving a renamed delegation whose target row did not
    exist replaced four rows with three parameters each, and one of the four was the form.

    Compared against the committed resource, so it answers "did my edit take something away",
    not "is the file on disk current" - which is what `stale` is for.
    """
    import subprocess
    previous = subprocess.run(
        ["git", "show", f"HEAD:{RESOURCE.relative_to(ROOT).as_posix()}"],
        capture_output=True, text=True, encoding="utf-8", cwd=ROOT)
    if previous.returncode != 0:
        return []
    was: dict[tuple[str, str], set[str]] = {}
    for line in previous.stdout.split("\n"):
        cells = line.split("\t")
        if len(cells) >= 3 and not line.startswith("#"):
            was[(cells[0], cells[1])] = {name for name in cells[2].split(",") if name}
    lost = []
    for row in found.values():
        key = (str(row["facade"]), str(row["operation"]))
        if key not in was:
            continue
        missing = was[key] - set(row["parameters"]) - CALL_KEYS
        if missing:
            lost.append(f"{key[0]}:{key[1]} no longer reads {', '.join(sorted(missing))}")
    return sorted(lost)


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

    RESOURCE.parent.mkdir(parents=True, exist_ok=True)
    RESOURCE.write_text(resource_text(found), encoding="utf-8")


def resource_text(found: dict[str, dict[str, object]]) -> str:
    """The map as it is shipped, built the one way both writing and checking use."""
    rows = ["# derived by scripts/check-operation-params.py - do not edit"]
    for _, row in sorted(found.items()):
        rows.append("\t".join((str(row["facade"]), str(row["operation"]),
                               ",".join(row["parameters"]), str(row["how"]))))
    rows.append("")
    return "\n".join(rows)


def stale(found: dict[str, dict[str, object]]) -> bool:
    """
    Whether the shipped map still describes the sources it was derived from.

    The map is read at run time by UnreadArguments, so a stale one is not a documentation
    problem: an operation missing from it is an operation whose arguments nothing checks.
    The check used to derive the map afresh and never compare it with the file, so the
    resource drifted through a whole release - the three named-area operations shipped in
    0.2.38 were never in it, and CI stayed green. Measured 2026-09-01.
    """
    if not RESOURCE.exists():
        print("the operation map is not there at all", file=sys.stderr)
        return True
    if RESOURCE.read_text(encoding="utf-8") == resource_text(found):
        return False
    print("the shipped operation map does not match the sources - run the script without "
          "--check and commit what it writes", file=sys.stderr)
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--no-narrowing", action="store_true",
                        help="fail when a row would read less than the committed map says")
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
    if args.check:
        if stale(found):
            return 1
    else:
        write_report(found)

    if args.no_narrowing:
        lost = narrowed(found)
        if lost:
            print("these would read less than the committed map says they do:", file=sys.stderr)
            for line in lost:
                print(f"  {line}", file=sys.stderr)
            print("a parameter dropping out of a row turns a call that worked into a refusal",
                  file=sys.stderr)
            return 1

    stray = unclassified_helpers()
    if stray:
        print("these take the argument map and a name, and nothing says whether they read it:",
              file=sys.stderr)
        for name in stray:
            print(f"  {name}", file=sys.stderr)
        print("add each to READER_HELPERS or to NOT_READERS in this script - a reader left out "
              "makes the map short, and a short map refuses calls that work", file=sys.stderr)
        return 1

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
