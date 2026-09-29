#!/usr/bin/env python3
"""The word-class census is checked against samples, so it keeps catching what it was written for.

The defect it exists for is silent: a pattern that reads Cyrillic with ASCII semantics does not
fail, it answers nothing and that reads as a clean result. So the samples here are the shapes that
were live in this codebase - a word boundary before a Russian keyword, a word class after one, a
case fold over one - and each has to be named. The other half is the shapes that must be left
alone: the same pattern with its semantics declared, the replacement text of a call (which is not a
pattern at all), and the ASCII-only site the allowlist exists for.

The declared forms matter as much as the caught ones: a census that flags everything teaches a
reader to add a flag without asking what the pattern reads. `String.replaceAll` takes no flags
argument, so there the only declaration possible is the inline group `(?U)` - which the samples pin
too.

Run: python3 scripts/tests/test_check_identifier_word_class.py
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

SPEC = importlib.util.spec_from_file_location(
    "check_identifier_word_class", SCRIPTS / "check-identifier-word-class.py")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


def weigh_source(source: str, name: str = "Sample.java"):
    """What the checker makes of one source text, as (weighed, violations)."""
    with tempfile.TemporaryDirectory() as folder:
        path = pathlib.Path(folder) / name
        path.write_text(source, encoding="utf-8")
        weighed = list(CHECKER.weighed_in(path))
        return weighed, CHECKER.violations_in(path)


def body(statement: str) -> str:
    """A source with one statement inside a method, indented as the real files are."""
    return ("class Sample\n"
            "{\n"
            "    void run(String text)\n"
            "    {\n"
            "        " + statement + "\n"
            "    }\n"
            "}\n")


class PatternsReadingCyrillicAreNamed(unittest.TestCase):
    """Every shape that was a live defect, and the call that hides it."""

    def test_word_boundary_before_a_russian_keyword(self):
        weighed, found = weigh_source(
            body('text.replaceAll("\\\\b(ГДЕ|WHERE)\\\\b", " ");'))
        self.assertEqual(1, len(weighed))
        self.assertEqual(1, len(found))
        self.assertEqual(5, found[0][0])
        self.assertIn("UNICODE_CHARACTER_CLASS", found[0][2])

    def test_word_class_after_a_russian_keyword(self):
        # The defect this gate was widened for: the pattern is not built by Pattern.compile, so
        # nothing but this census weighs it at all.
        weighed, found = weigh_source(
            body('text.replaceAll("(?iu)\\\\bКАК\\\\s+[\\\\p{L}_]\\\\w*", " ");'))
        self.assertEqual(1, len(weighed))
        self.assertEqual(1, len(found))

    def test_word_class_in_a_pattern_compiled_with_a_flag_argument(self):
        weighed, found = weigh_source(
            body('Pattern.compile("(Процедура|Функция)\\\\s+([\\\\w_]+)");'))
        self.assertEqual(1, len(weighed))
        self.assertEqual(1, len(found))

    def test_case_folded_over_cyrillic(self):
        _, found = weigh_source(
            body('Pattern.compile("(Процедура|Функция)", Pattern.CASE_INSENSITIVE);'))
        self.assertEqual(1, len(found))
        self.assertIn("UNICODE_CASE", found[0][2])

    def test_the_split_and_matches_calls_are_weighed_too(self):
        _, split = weigh_source(body('text.split("\\\\bГДЕ\\\\b");'))
        _, matches = weigh_source(body('text.matches("\\\\w+Процедура");'))
        self.assertEqual(1, len(split))
        self.assertEqual(1, len(matches))


class DeclaredSemanticsAreLeftAlone(unittest.TestCase):
    """The same patterns, told what they are reading."""

    def test_an_inline_word_class_group(self):
        _, found = weigh_source(
            body('text.replaceAll("(?iuU)\\\\bКАК\\\\s+[\\\\p{L}_]\\\\w*", " ");'))
        self.assertEqual([], found)

    def test_a_flag_argument_on_compile(self):
        _, found = weigh_source(
            body('Pattern.compile("\\\\b(ГДЕ|WHERE)\\\\b", Pattern.UNICODE_CHARACTER_CLASS);'))
        self.assertEqual([], found)

    def test_a_case_fold_with_its_unicode_flag(self):
        _, found = weigh_source(
            body('Pattern.compile("Процедура|Функция", '
                 'Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);'))
        self.assertEqual([], found)


class WhatIsNotAPatternIsNotWeighed(unittest.TestCase):
    """A call's later arguments are a replacement and a limit, never a pattern."""

    def test_a_cyrillic_replacement_text(self):
        weighed, found = weigh_source(body('text.replaceAll("[A-Z_]+", "ГДЕ");'))
        self.assertEqual([], weighed)
        self.assertEqual([], found)

    def test_a_cyrillic_receiver(self):
        # The text the call is made on is not a pattern either, however much Cyrillic it holds.
        weighed, found = weigh_source(body('"ГДЕ".matches("[A-Z]+");'))
        self.assertEqual([], weighed)
        self.assertEqual([], found)


class AnAsciiOnlyPatternMayGoUnflagged(unittest.TestCase):
    """The allowlist is a decision with a reason, not a hole: it is keyed by file and by text."""

    def test_the_allowlist_covers_the_site_it_names(self):
        reason = CHECKER.excused(pathlib.Path("ValidateForExportTool.java"), "xmlns:\\w+")
        self.assertIsNotNone(reason)
        self.assertIn("ASCII", reason)

    def test_the_allowlist_does_not_cover_another_file(self):
        self.assertIsNone(CHECKER.excused(pathlib.Path("Other.java"), "xmlns:\\w+"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
