#!/usr/bin/env python3
"""The census has to see a second bundle and its own test fragment, or it blesses a gap.

From the shared delivery on the reactor carries two plugin bundles. The coverage census read
one hardcoded source root and one hardcoded fragment, so a class of the second bundle would
have been counted against the FIRST bundle's tests or not at all - and a report over a merged
tree would have looked exactly like a report over the old one while covering half the code.

These tests build fake bundles in a temporary directory, so nothing here depends on whether
the ordinary-forms bundle has landed in this working copy yet.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

BUNDLES = importlib.import_module("bundles")

SPEC = importlib.util.spec_from_file_location("test_coverage_audit", SCRIPTS / "test-coverage-audit.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

SERVER = "ru.aiedt.mcp.server"
OFORM = "ru.aiedt.mcp.oform"


def write(path, text):
    pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(path).write_text(text, encoding="utf-8")


def plain_class(bundle, name, body=""):
    package = bundle + ".kept"
    return ("package %s;\n\npublic final class %s {\n%s\n}\n" % (package, name, body))


class FakeTree(unittest.TestCase):
    """A two-bundle reactor in a temporary directory, with the fragments each bundle owns."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(self.tmp.name)
        self.root = root
        self.saved = (BUNDLES.BUNDLES_DIR, BUNDLES.TESTS_DIR)
        BUNDLES.BUNDLES_DIR = root / "bundles"
        BUNDLES.TESTS_DIR = root / "tests"

        write(root / "bundles" / SERVER / "src" / "ru/aiedt/mcp/server/kept" / "Plain.java",
              plain_class(SERVER, "Plain"))
        write(root / "tests" / (SERVER + ".tests") / "src" / "ru/aiedt/mcp/server/tests"
              / "PlainTest.java",
              "package ru.aiedt.mcp.server.tests;\n\n"
              "public class PlainTest {\n    @org.junit.Test\n    public void reads() { }\n}\n")

    def tearDown(self):
        BUNDLES.BUNDLES_DIR, BUNDLES.TESTS_DIR = self.saved
        self.tmp.cleanup()

    def add_oform(self, with_test=True, test_mentions=None, comment_only=False):
        """A second bundle with one class, and the fragment shape the caller asks for."""
        write(self.root / "bundles" / OFORM / "src" / "ru/aiedt/mcp/oform/kept" / "Other.java",
              plain_class(OFORM, "Other"))
        fragment = self.root / "tests" / (OFORM + ".tests") / "src" / "ru/aiedt/mcp/oform/tests"
        # The fragment directory itself always exists: the question is whether a test class
        # is in it, not whether the fragment is on disk at all.
        write(fragment / "package-info.java", "")
        if with_test:
            body = "package ru.aiedt.mcp.oform.tests;\n\npublic class OtherTest {\n}\n"
            write(fragment / "OtherTest.java", body)
        if test_mentions is not None:
            # A test of the SERVER fragment that mentions the oform class - in code or in prose.
            mention = "// %s is fine" % test_mentions if comment_only else "    %s x = null;" % test_mentions
            write(self.root / "tests" / (SERVER + ".tests") / "src" / "ru/aiedt/mcp/server/tests"
                  / "MentionTest.java",
                  "package ru.aiedt.mcp.server.tests;\n\n"
                  "public class MentionTest {\n    void m() {\n%s\n    }\n}\n" % mention)

    def audit(self):
        return MODULE.audit(BUNDLES.census_roots())


class BothBundlesAreDiscovered(FakeTree):
    def test_one_bundle_answers_one_and_the_second_is_picked_up(self):
        first = self.audit()
        self.add_oform(with_test=False)
        second = self.audit()
        self.assertEqual(1, sum(len(rows) for rows in first[0].values()))
        self.assertEqual(2, sum(len(rows) for rows in second[0].values()),
                         "the second bundle has to appear in the census")

    def test_the_bundle_list_comes_from_the_tree(self):
        self.add_oform(with_test=False)
        self.assertEqual(sorted([SERVER, OFORM]), BUNDLES.production_bundles())

    def test_the_fragment_is_chosen_by_name(self):
        self.add_oform(with_test=False)
        # A directory under tests that is no fragment of any bundle is not read as one.
        write(self.root / "tests" / "not.a.fragment" / "src" / "x" / "Y.java", "package x;\n")
        for bundle, _src, fragment in BUNDLES.census_roots():
            self.assertIsNotNone(fragment, "every bundle here has its fragment on disk")
            self.assertTrue(str(fragment).startswith(str(BUNDLES.TESTS_DIR / (bundle + ".tests"))))


class TheSecondFragmentAnswersForItsOwnClasses(FakeTree):
    def test_a_class_with_its_own_fragment_test_is_direct(self):
        self.add_oform(with_test=True)
        buckets, _count, _methods = self.audit()
        self.assertIn(("oform:kept", "Other", OFORM + "/ru/aiedt/mcp/oform/kept/Other.java"),
                      buckets["direct"])

    def test_a_class_without_a_test_is_untested(self):
        self.add_oform(with_test=False)
        buckets, _count, _methods = self.audit()
        self.assertIn(("oform:kept", "Other", OFORM + "/ru/aiedt/mcp/oform/kept/Other.java"),
                      buckets["untested"],
                      "a second-bundle class with no test of its own must not read as covered")

    def test_a_test_of_another_fragment_does_not_make_it_direct(self):
        # OtherTest exists in the SERVER fragment, not in the oform one: it cannot load the
        # oform classes, so naming one there is not a test of it.
        write(self.root / "bundles" / OFORM / "src" / "ru/aiedt/mcp/oform/kept" / "Other.java",
              plain_class(OFORM, "Other"))
        write(self.root / "tests" / (SERVER + ".tests") / "src" / "ru/aiedt/mcp/server/tests"
              / "OtherTest.java",
              "package ru.aiedt.mcp.server.tests;\npublic class OtherTest {\n}\n")
        buckets, _count, _methods = self.audit()
        entry = ("oform:kept", "Other", OFORM + "/ru/aiedt/mcp/oform/kept/Other.java")
        self.assertNotIn(entry, buckets["direct"])
        # The test class's own name carries "Other" only inside the word "OtherTest", so the
        # census does not even see a mention: the class is untested, not directly tested.
        self.assertIn(entry, buckets["untested"])


class ProseIsNotCoverage(FakeTree):
    def test_a_comment_mention_does_not_exercise_a_class(self):
        self.add_oform(with_test=False, test_mentions="Other", comment_only=True)
        buckets, _count, _methods = self.audit()
        self.assertIn(("oform:kept", "Other", OFORM + "/ru/aiedt/mcp/oform/kept/Other.java"),
                      buckets["untested"],
                      "a class named in a comment of some other test is not tested by that comment")

    def test_a_code_mention_does_exercise_a_class(self):
        self.add_oform(with_test=False, test_mentions="Other", comment_only=False)
        buckets, _count, _methods = self.audit()
        self.assertIn(("oform:kept", "Other", OFORM + "/ru/aiedt/mcp/oform/kept/Other.java"),
                      buckets["exercised"])


class VersionFloor(unittest.TestCase):
    def test_qualifiers_do_not_move_a_version_past_its_release(self):
        self.assertTrue(BUNDLES.at_least("0.2.60.v20261017", "0.2.60"))
        self.assertTrue(BUNDLES.at_least("0.2.61", "0.2.60"))
        self.assertFalse(BUNDLES.at_least("0.2.59", "0.2.60"))
        self.assertFalse(BUNDLES.at_least("0.2.6", "0.2.60"))


if __name__ == "__main__":
    unittest.main()
