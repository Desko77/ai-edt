#!/usr/bin/env python3
"""The detector is checked against a fixture, so it keeps detecting.

A census is only worth the build step it occupies if it still finds what it was written to find,
and still leaves alone what it was calibrated to leave alone. Both halves are here: every shape
that was a false positive during calibration is pinned as legitimate, and every shape that was a
real finding is pinned as an orphan.

Run: python3 scripts/tests/test_check_orphan_javadoc.py
"""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import importlib.util

SPEC = importlib.util.spec_from_file_location(
    "check_orphan_javadoc",
    pathlib.Path(__file__).resolve().parent.parent / "check-orphan-javadoc.py")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class OrphansAreFound(unittest.TestCase):
    """What the detector must report."""

    def test_javadoc_followed_by_javadoc(self):
        source = (
            "/**\n"
            " * Left behind when its method was replaced.\n"
            " */\n"
            "/**\n"
            " * Documents the method below.\n"
            " */\n"
            "public void work()\n"
            "{\n"
            "}\n")
        self.assertEqual([1], CHECKER.orphans_in(source))

    def test_javadoc_before_a_closing_brace(self):
        source = (
            "public class A\n"
            "{\n"
            "    /**\n"
            "     * Documents nothing: the class ends here.\n"
            "     */\n"
            "}\n")
        self.assertEqual([3], CHECKER.orphans_in(source))

    def test_javadoc_at_the_end_of_the_file(self):
        source = "/**\n * Nothing follows.\n */\n"
        self.assertEqual([1], CHECKER.orphans_in(source))

    def test_an_annotation_does_not_rescue_an_orphan(self):
        # Measured in this codebase: a block, an annotation, then another block. The first pair
        # documents nothing.
        source = (
            "/** Adds an item to a raw list. */\n"
            "@SuppressWarnings({ \"rawtypes\", \"unchecked\" })\n"
            "/**\n"
            " * Documents the method below.\n"
            " */\n"
            "public void add()\n"
            "{\n"
            "}\n")
        self.assertEqual([1], CHECKER.orphans_in(source))


class LegitimateBlocksAreLeftAlone(unittest.TestCase):
    """Every shape that was a false positive while the pattern was being calibrated."""

    def test_the_file_header_above_a_package(self):
        source = (
            "/**\n"
            " * AI-EDT - 1C AI tools for EDT\n"
            " */\n"
            "\n"
            "package ru.aiedt.mcp.server;\n")
        self.assertEqual([], CHECKER.orphans_in(source))

    def test_an_enum_constant(self):
        source = (
            "public enum Kind\n"
            "{\n"
            "    /** The first. */\n"
            "    LEGACY,\n"
            "\n"
            "    /** The last, which carries no comma. */\n"
            "    UNSUPPORTED\n"
            "}\n")
        self.assertEqual([], CHECKER.orphans_in(source))

    def test_a_generic_return_type_with_a_space(self):
        source = (
            "/**\n"
            " * Documents the method below.\n"
            " */\n"
            "Map<String, Integer> sectionMap()\n"
            "{\n"
            "}\n")
        self.assertEqual([], CHECKER.orphans_in(source))

    def test_an_annotation_between_the_block_and_its_method(self):
        source = (
            "/**\n"
            " * Documents the method below.\n"
            " */\n"
            "@Override\n"
            "public void run()\n"
            "{\n"
            "}\n")
        self.assertEqual([], CHECKER.orphans_in(source))

    def test_a_single_line_block_above_a_field(self):
        source = (
            "/** The port this server listens on. */\n"
            "private static final int PORT = 12250;\n")
        self.assertEqual([], CHECKER.orphans_in(source))

    def test_a_line_comment_between_the_block_and_its_method(self):
        source = (
            "/**\n"
            " * Documents the method below.\n"
            " */\n"
            "// Why it is written this way.\n"
            "public void run()\n"
            "{\n"
            "}\n")
        self.assertEqual([], CHECKER.orphans_in(source))


if __name__ == "__main__":
    unittest.main(verbosity=2)
