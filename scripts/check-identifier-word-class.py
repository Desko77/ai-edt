#!/usr/bin/env python3
"""A regular expression that reads BSL must be told that BSL is not ASCII.

Java's `\\w`, `\\b`, `\\W` and `\\B` are ASCII-only unless UNICODE_CHARACTER_CLASS is set, and
CASE_INSENSITIVE folds only ASCII unless UNICODE_CASE is set. Both defaults are wrong for 1C source:
methods are named in Cyrillic and the language does not distinguish case, so

  * `\\b(ГДЕ|WHERE)\\b` never matches Russian ГДЕ at all - before a Cyrillic letter there is no
    ASCII word boundary to find;
  * `(Процедура|Функция)\\s+([\\w_]+)` matches the keyword and then fails on the NAME;
  * `(Если|Цикл)` under plain CASE_INSENSITIVE does not match `если` written in lower case.

Each of those was a live defect: whole scans reported nothing on configurations written in Russian
and called it a clean answer.

The rule this enforces: a pattern whose text carries Cyrillic must declare the matching semantics it
relies on. Patterns that read ASCII by nature (an XML namespace prefix, a language code) are listed
in ALLOWED with the reason, because a blanket flag there would widen what they accept.

A pattern does not have to be built by Pattern.compile to be wrong: String.replaceAll, replaceFirst,
matches and split compile their first argument the same way, and there is no flags argument to pass -
the only place to declare the semantics is an inline group inside the pattern text itself, `(?U)` for
the word classes and `(?u)` for case. So those calls are weighed too, and the inline group counts as
the declaration. What follows the first argument is the replacement or the limit, never a pattern, and
is not read as one.

Usage:
    python scripts/check-identifier-word-class.py            # report, exit 1 on a violation
    python scripts/check-identifier-word-class.py --list     # print every pattern it weighs
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "mcp/bundles/ru.aiedt.mcp.server/src"

# Every call whose first argument Java compiles as a regular expression. The flag says whether a
# second argument states the semantics, which only Pattern.compile has.
CALLS = (
    (re.compile(r"Pattern\s*\.\s*compile\s*\("), True),
    (re.compile(r"Pattern\s*\.\s*matches\s*\("), False),
    (re.compile(r"\.\s*(?:replaceAll|replaceFirst|matches|split)\s*\("), False),
)
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
CYRILLIC = re.compile("[Ѐ-ӿ]")
INLINE_FLAGS = re.compile(r"\(\?([a-zA-Z]+)[:)]")
WORD_CLASS = ("\\w", "\\W", "\\b", "\\B")

# A pattern that reads an ASCII-only input by nature. The reason is part of the entry: an
# unexplained exception is how a gate stops meaning anything.
ALLOWED = {
    ("ValidateForExportTool.java", "xmlns:"): "XML namespace prefix - ASCII by the XML specification",
    ("ValidateForExportTool.java", "<lang>"): "language code in a .mdo file - ASCII by the format",
}


def argument_list(text: str, open_paren: int) -> str:
    """The text of the call's arguments, from the opening parenthesis to its match."""
    depth = 0
    index = open_paren
    while index < len(text):
        char = text[index]
        if char == '"':
            index += 1
            while index < len(text) and (text[index] != '"' or text[index - 1] == "\\"):
                index += 1
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1 : index]
        index += 1
    return ""


def first_argument(args: str) -> tuple[str, str]:
    """The call's first argument, and what follows it, cut at the top-level comma."""
    depth = 0
    index = 0
    while index < len(args):
        char = args[index]
        if char == '"':
            index += 1
            while index < len(args) and (args[index] != '"' or args[index - 1] == "\\"):
                index += 1
        elif char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            return args[:index], args[index + 1 :]
        index += 1
    return args, ""


def weigh(path: pathlib.Path):
    """Every call whose first argument is a pattern, as (line, regex, flags)."""
    text = path.read_text(encoding="utf-8")
    for call, flags_follow in CALLS:
        for match in call.finditer(text):
            args = argument_list(text, match.end() - 1)
            if not args:
                continue
            pattern, rest = first_argument(args)
            cleaned = pattern.replace("//$NON-NLS-1$", " ").replace("//$NON-NLS-2$", " ")
            regex = "".join(LITERAL.findall(cleaned))
            flags = rest if flags_follow else ""
            yield text[: match.start()].count("\n") + 1, regex, flags


def excused(path: pathlib.Path, regex: str) -> str | None:
    for (name, needle), reason in ALLOWED.items():
        if path.name == name and needle in regex:
            return reason
    return None


def weighed_in(path: pathlib.Path):
    """Every pattern in one file whose text carries Cyrillic, as (line, regex, flags)."""
    for line, regex, flags in weigh(path):
        if CYRILLIC.search(regex):
            yield line, regex, flags


def violations_in(path: pathlib.Path):
    """The patterns in one file that read BSL as if it were ASCII, as (line, regex, why)."""
    found = []
    for line, regex, flags in weighed_in(path):
        if excused(path, regex):
            continue
        inline = "".join(INLINE_FLAGS.findall(regex))
        needs_class = any(token in regex for token in WORD_CLASS)
        has_class = "UNICODE_CHARACTER_CLASS" in flags or "U" in inline
        has_case = "UNICODE_CASE" in flags or has_class or "u" in inline
        if needs_class and not has_class:
            found.append((line, regex,
                          "reads a word class or a word boundary next to Cyrillic "
                          "without UNICODE_CHARACTER_CLASS"))
        elif "CASE_INSENSITIVE" in flags and not has_case:
            found.append((line, regex, "folds case over Cyrillic without UNICODE_CASE"))
    return found


def main() -> int:
    listing = "--list" in sys.argv
    weighed = 0
    violations = []
    for path in sorted(ROOT.rglob("*.java")):
        for line, regex, _flags in weighed_in(path):
            weighed += 1
            if listing:
                print(f"{path.name}:{line}  {regex[:70]}")
        violations.extend((path, *found) for found in violations_in(path))

    print(f"patterns carrying Cyrillic: {weighed}")
    print(f"excused by name and reason: {len(ALLOWED)}")
    if not violations:
        print("every one of them declares the semantics it relies on")
        return 0

    print(f"\n{len(violations)} pattern(s) read BSL as if it were ASCII:\n")
    for path, line, regex, why in violations:
        print(f"  {path.relative_to(ROOT.parent)}:{line}")
        print(f"      {regex[:90]}")
        print(f"      {why}\n")
    return 1


if __name__ == "__main__":
    sys.exit(main())
