#!/usr/bin/env python3
"""A catalog argument is a promise the same way a schema argument is.

From the shared delivery on, the ordinary-forms bundle declares its arguments in a catalog
(`ParamSpec.string("name", ...)`) rather than in a schema builder. An argument declared
there and read by nothing in its own bundle is the same defect this census exists for: the
call succeeds, the value is dropped, and the caller reads the success as applied. The
mention scope is the bundle - the whole bundle is one tool - while the server's files are
weighed each against itself.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

BUNDLES = importlib.import_module("bundles")

SPEC = importlib.util.spec_from_file_location(
    "check_declared_arguments", SCRIPTS / "check-declared-arguments-are-read.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

OFORM = "ru.aiedt.mcp.oform"


def write(path, text):
    pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(path).write_text(text, encoding="utf-8")


class FakeOformBundle(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.src = self.root / "bundles" / OFORM / "src"
        self.saved = BUNDLES.BUNDLES_DIR
        BUNDLES.BUNDLES_DIR = self.root / "bundles"
        MODULE.OFORM_SRC = self.src

    def tearDown(self):
        BUNDLES.BUNDLES_DIR = self.saved
        MODULE.OFORM_SRC = BUNDLES.bundle_src(OFORM)
        self.tmp.cleanup()

    def weigh(self):
        return MODULE.weigh_catalog()

    def test_an_argument_read_anywhere_in_the_bundle_is_read(self):
        write(self.src / "ru/aiedt/mcp/oform/ops" / "ThemeOperations.java",
              'List.of(ParamSpec.string("title", "the new title."), //$NON-NLS-1$ //$NON-NLS-2$\n'
              '    ParamSpec.bool("dryRun", "preview only"));\n')
        write(self.src / "ru/aiedt/mcp/oform" / "LayoutOperations.java",
              'String title = text(params, "title"); //$NON-NLS-1$\n')
        declared, mentioned = self.weigh()
        self.assertEqual({"title", "dryRun"}, set(declared))
        self.assertGreater(mentioned["title"], 0)

    def test_an_argument_read_by_nothing_is_finding_material(self):
        write(self.src / "ru/aiedt/mcp/oform/ops" / "ThemeOperations.java",
              'List.of(ParamSpec.string("writeToModule", "never written")); //$NON-NLS-1$\n')
        declared, mentioned = self.weigh()
        self.assertEqual(0, mentioned["writeToModule"],
                         "a catalog argument no file of the bundle reads is unread")

    def test_the_declaration_itself_is_not_a_read(self):
        # The name appears in its own declaration and nowhere else: exactly one occurrence,
        # the one the census strips.
        write(self.src / "ru/aiedt/mcp/oform/ops" / "ThemeOperations.java",
              'List.of(ParamSpec.string("recursive", "borrow children too"));\n')
        _declared, mentioned = self.weigh()
        self.assertEqual(0, mentioned["recursive"])

    def test_an_absent_bundle_answers_none(self):
        BUNDLES.BUNDLES_DIR = self.root / "nowhere"
        MODULE.OFORM_SRC = BUNDLES.bundle_src(OFORM)
        self.assertIsNone(self.weigh(),
                          "no bundle on disk is the pre-merge state, not an empty census")


if __name__ == "__main__":
    unittest.main()
