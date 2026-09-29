#!/usr/bin/env python3
"""Check that no reflection failure disappears without a word.

Reflection into EDT fails in two very different ways that look identical at the catch:
the member is genuinely absent on this release - ordinary, and what probing is for - or the
call is refused because the implementation class is not public, which is a defect. Caught as
`catch (Exception ignored) {}`, both vanish, and the caller reads the resulting null as
"this EDT does not offer it".

That is not hypothetical. `IDtProject.getWorkspaceProject()` was reached by name on the
implementation for years; invoke answered IllegalAccessException, the catch discarded it, and
the project came through null. Every step that needed the project quietly degraded - primitive
types were written through the `unresolved:/` fallback instead of resolving to real platform
types - and nothing anywhere said so. It surfaced only when an unrelated check tripped over it.

So the rule is not "never swallow". It is: a swallowed reflection failure must carry a reason.
A comment inside the catch is enough - it forces whoever writes it to say why absence is
expected here, and it makes the silent ones countable.

The catch is selected by its BODY, not by the name of its variable. A variable called
`ignored` says what the author intended, and the intent is exactly what is in question: a
`catch (Exception ignored)` that answers null teaches the caller the same thing as
`catch (Exception e)` that answers null. Only the body tells whether the failure was read,
carried on, acted upon, or simply dropped - so every catch over a reflective probe is
weighed, whatever its variable is called.

A catch is BARE when its body does nothing at all with the situation: it neither reads the
caught exception, nor throws, nor calls anything, nor says why. What is left in such a body
is an exit with a neutral answer, and the caller reads "not offered" where the truth may be
"refused". A body that reaches for something - a log call, a fallback route, another probe -
is not bare: the call is where the handling is, and it is there to be read.

Usage:
    python scripts/check-swallowed-reflection.py            # report, exit 1 on a bare swallow
    python scripts/check-swallowed-reflection.py --list     # every catch weighed, with its verdict
"""

from __future__ import annotations

import argparse
import os
import re
import sys

SOURCE_ROOT = os.path.join("mcp", "bundles", "ru.aiedt.mcp.server", "src")

CATCH = re.compile(r"catch\s*\(\s*(?:final\s+)?[\w.]+(?:\s*\|\s*[\w.]+)*\s+(\w+)\s*\)")

CALL = re.compile(r"\w\s*\(")

RETHROW = re.compile(r"\bthrow\b")

DECL = re.compile(r"^ {0,8}(?:(?:public|private|protected|static|final|synchronized|abstract"
                  r"|native|default)\s+)*(!?[\w<>\[\],.?]+)(?:\s+[\w<>\[\],.?]+)*\s+\w+\s*\(")

STATEMENT_WORDS = {"if", "for", "while", "switch", "catch", "try", "return", "else", "do",
                   "new", "throw", "synchronized", "assert"}

ABSENT = re.compile(r"absent|missing|unavailable|no such|not available|unreadable|"
                    r"does not (?:have|expose|answer|carry)|without", re.I)

REFLECTION = (
    ".getClass().getMethod(",
    ".getClass().getDeclaredMethod(",
    ".getClass().getField(",
    ".getClass().getDeclaredField(",
    "Class.forName(",
    ".getMethod(",
    ".getDeclaredMethod(",
    ".newInstance(",
)


def try_block(lines, catch_index):
    """The lines of the try this catch closes, found by walking back over brace depth."""
    depth = 0
    for i in range(catch_index, max(-1, catch_index - 200), -1):
        depth += lines[i].count("}") - lines[i].count("{")
        if lines[i].lstrip().startswith("try") and depth <= 0:
            return lines[i:catch_index]
    return lines[max(0, catch_index - 15):catch_index]


def catch_body(lines, catch_index):
    """The lines from the catch to the brace that closes it."""
    depth = 0
    started = False
    body = []
    for i in range(catch_index, min(len(lines), catch_index + 60)):
        body.append(lines[i])
        depth += lines[i].count("{") - lines[i].count("}")
        if "{" in lines[i]:
            started = True
        if started and depth <= 0:
            break
    return body


def reflective(block):
    text = "\n".join(block)
    return any(marker in text for marker in REFLECTION)


def explained(body):
    """A comment anywhere in the catch counts: someone had to state the reason."""
    return any("//" in line or "/*" in line for line in body)


def inner_body(body):
    """The text between the body's braces, comments and all."""
    text = "\n".join(body)
    opened = text.find("{")
    closed = text.rfind("}")
    if opened < 0 or closed < opened:
        return ""
    return text[opened + 1:closed]


def looks_like_declaration(text):
    """A signature rather than a statement: a type and a name before the bracket.

    The leading run of spaces is capped, so a call indented inside a method body cannot
    pass for a signature however far back the walk has gone.
    """
    if not DECL.match(text):
        return False
    head = text[:text.index("(")].strip()
    if any(mark in head for mark in ';="') or ")" in head:
        return False
    words = head.split()
    return len(words) >= 2 and words[0] not in STATEMENT_WORDS


def declaring_method(lines, catch_index):
    """The line of the method declaration the catch sits in, walking up from the catch."""
    for i in range(catch_index - 1, max(-1, catch_index - 200), -1):
        text = lines[i]
        stripped = text.strip()
        if not stripped or stripped.startswith(("//", "*", "/*", "@")):
            continue
        if looks_like_declaration(text):
            return i
    return None


def javadoc_of(lines, declaration):
    """The javadoc block immediately above a declaration, or None."""
    if declaration is None:
        return None
    i = declaration - 1
    while i >= 0 and (not lines[i].strip() or lines[i].strip().startswith("@")):
        i -= 1
    if i < 0 or not lines[i].strip().endswith("*/"):
        return None
    start = i
    while start >= 0 and "/*" not in lines[start]:
        start -= 1
    if start < 0:
        return None
    return "\n".join(lines[start:i + 1])


def answer_of(body):
    """The neutral value the body's exit hands back, or None when it hands back nothing."""
    inside = " ".join(inner_body(body).split())
    found = re.search(r"\breturn\s+(null|false|true|0)\s*;", inside)
    if found:
        return found.group(1)
    found = re.search(r"=\s*(null|false|true|0)\s*;", inside)
    if found:
        return found.group(1)
    return None


def contract_states_it(doc, answer):
    """Does the method's own contract speak about the member that is not there?

    Either it names what the caller gets instead - the answer the catch hands back -
    or it says the member is absent, which is the case the catch is discarding.
    """
    if not doc:
        return False
    if ABSENT.search(doc):
        return True
    return answer is not None and re.search(r"\b%s\b" % answer, doc, re.I) is not None


def verdict(lines, catch_index, variable):
    """What the catch does with the failure it caught.

    stated  - the reason is written where it was caught
    carried - the failure is thrown on
    acted   - the body calls something: the handling is there to be read
    read    - the caught exception itself is used
    bare    - nothing above: an exit with a neutral answer, and no word about why
    """
    body = catch_body(lines, catch_index)
    inside = inner_body(body)
    if explained(body):
        return "stated"
    if RETHROW.search(inside):
        return "carried"
    if CALL.search(inside):
        return "acted"
    if re.search(r"\b%s\b" % re.escape(variable), inside):
        return "read"
    doc = javadoc_of(lines, declaring_method(lines, catch_index))
    if contract_states_it(doc, answer_of(body)):
        return "documented"
    return "bare"


def catches_in(lines):
    """Every catch over a reflective probe in one source, as (line, verdict)."""
    findings = []
    for i, line in enumerate(lines):
        caught = CATCH.search(line)
        if not caught:
            continue
        if not reflective(try_block(lines, i)):
            continue
        findings.append({"line": i + 1, "verdict": verdict(lines, i, caught.group(1))})
    return findings


def scan():
    findings = []
    for dirpath, _dirs, files in os.walk(SOURCE_ROOT):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                lines = handle.read().splitlines()
            for found in catches_in(lines):
                found["path"] = path.replace(os.sep, "/")
                findings.append(found)
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true",
                        help="print every catch weighed, with its verdict")
    args = parser.parse_args()

    findings = scan()
    bare = [f for f in findings if f["verdict"] == "bare"]

    if args.list:
        for f in findings:
            print("%-8s %s:%d" % (f["verdict"], f["path"], f["line"]))
        print()

    counted = {}
    for f in findings:
        counted[f["verdict"]] = counted.get(f["verdict"], 0) + 1

    print("%d catches over a reflective probe" % len(findings))
    print("  %d state why they swallow" % counted.get("stated", 0))
    print("  %d carry the failure on" % counted.get("carried", 0))
    print("  %d call something with it" % counted.get("acted", 0))
    print("  %d read the exception" % counted.get("read", 0))
    print("  %d are answered by the contract of the method they sit in"
          % counted.get("documented", 0))
    print("  %d answer a neutral value without a word" % len(bare))
    if bare:
        print()
        for f in bare:
            print("  %s:%d" % (f["path"], f["line"]))
        print()
        print("Each of these has to say why the failure is expected - a comment in the catch.")
        print("If it is NOT expected, the fix is elsewhere: a method declared by an interface")
        print("the file already imports should be called, not looked up by name.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
