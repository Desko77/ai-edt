#!/usr/bin/env python3
"""The facade help census is checked against facades, so it keeps catching what it was written for.

The census reconciles the operations a facade dispatches against the operations its own help names.
An operation in the first set and not the second is one an agent cannot learn exists, and the whole
value of the check is that it is run over every facade. So the samples pin the two ways a facade
escaped it:

  * a help method whose name only starts with `buildHelp`, which the declaration pattern missed -
    EditFormTool, the largest facade in the codebase, was read as one whose help is built elsewhere;
  * a declaration the census found and could not read, which reported nothing rather than failing.

The other half is the spellings. The dispatcher folds camelCase into its snake_case label, so a help
that writes `addField` for `add_field` has named that operation, and pinning only the label would
accuse a facade of hiding operations it documents.

Run: python3 scripts/tests/test_check_facade_help.py
"""

import importlib.util
import pathlib
import sys
import tempfile
import threading
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

SPEC = importlib.util.spec_from_file_location(
    "check_facade_help", SCRIPTS / "check-facade-help.py")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


def within_a_time_limit(function, seconds=20):
    """What the call answered, under the keys `value` or `failure`, and no return at all is a failure.

    The reading walks the source character by character, and a walk that does not finish is the
    defect one of the samples below pins. The call runs on its own thread so the wait can be given
    up on: a reading that hangs has to fail the test rather than hang the gate.
    """
    outcome = {}

    def call():
        try:
            outcome["value"] = function()
        except BaseException as failure:  # a defective reading reports itself by raising
            outcome["failure"] = failure

    caller = threading.Thread(target=call, daemon=True)
    caller.start()
    caller.join(seconds)
    if caller.is_alive():
        raise AssertionError("the call did not return within %d seconds" % seconds)
    return outcome


def facade(help_lines, cases, declaration="private static String buildHelp()"):
    """A facade whose help appends the given lines and whose dispatcher handles the given cases."""
    body = "".join('        sb.append("%s");\n' % line for line in help_lines)
    labels = "".join('                case "%s":\n                    return "ok";\n' % case
                     for case in cases)
    return ("class SampleTool\n"
            "{\n"
            "    " + declaration + "\n"
            "    {\n"
            "        StringBuilder sb = new StringBuilder();\n"
            + body +
            "        return sb.toString();\n"
            "    }\n"
            "\n"
            "    private String dispatch(String operation)\n"
            "    {\n"
            "        switch (operation)\n"
            "        {\n"
            + labels +
            "            default:\n"
            "                return null;\n"
            "        }\n"
            "    }\n"
            "}\n")


def run_over(source, name="SampleTool.java"):
    """The exit code the census gives one facade, with nothing else in the directory."""
    with tempfile.TemporaryDirectory() as folder:
        pathlib.Path(folder, name).write_text(source, encoding="utf-8")
        previous = CHECKER.OPS
        CHECKER.OPS = pathlib.Path(folder)
        try:
            import io
            import contextlib
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = CHECKER.main()
            return code, out.getvalue()
        finally:
            CHECKER.OPS = previous


class TheHelpIsFoundWhereItIs(unittest.TestCase):
    """A help method is the facade's help whatever its name ends with."""

    def test_a_build_help_response_declaration(self):
        source = facade(["### addField\n"], ["add_field"],
                        declaration="private static String buildHelpResponse()")
        self.assertIsNotNone(CHECKER.help_text(source))

    def test_a_call_site_is_not_a_declaration(self):
        source = ("class SampleTool\n"
                  "{\n"
                  "    private String dispatch(String operation)\n"
                  "    {\n"
                  "        return buildHelpResponse();\n"
                  "    }\n"
                  "}\n")
        self.assertIsNone(CHECKER.help_text(source))

    def test_a_braces_in_a_help_string_do_not_end_the_body_early(self):
        source = ("class SampleTool\n"
                  "{\n"
                  "    private static String buildHelp()\n"
                  "    {\n"
                  "        sb.append(\"}{\\\"operation\\\": \\\"addField\\\"}\");\n"
                  "        sb.append(\"### addField\\n\");\n"
                  "        return sb.toString();\n"
                  "    }\n"
                  "}\n")
        body = CHECKER.help_text(source)
        self.assertIn("### addField", body)


class AnUnreadableDeclarationFails(unittest.TestCase):
    """Found and unreadable is not the same as not there: nothing was compared, so nothing passes."""

    def test_a_declaration_without_a_body(self):
        source = ("class SampleTool\n"
                  "{\n"
                  "    private static String buildHelp();\n"
                  "}\n")
        with self.assertRaises(CHECKER.HelpUnreadable):
            CHECKER.help_text(source)

    def test_a_body_that_is_never_closed(self):
        # A truncated file, which is what a body that does not close looks like: the walk reaches
        # the end of the source with the brace still open.
        source = ("class SampleTool\n"
                  "{\n"
                  "    private static String buildHelp()\n"
                  "    {\n"
                  "        sb.append(\"### addField\\n\");\n")
        with self.assertRaises(CHECKER.HelpUnreadable):
            CHECKER.help_text(source)

    def test_a_block_comment_that_is_never_closed(self):
        # A declaration cut off inside a block comment. The reading used to step back to the start
        # of the file from the opening marker, meet the same comment again and never return; the
        # call is bounded here so a reading that does not finish fails instead of hanging the gate.
        source = ("class SampleTool\n"
                  "{\n"
                  "    private static String buildHelp()\n"
                  "    {\n"
                  "        /* ### addField\n"
                  "        sb.append(\"### addField\\n\");\n")
        outcome = within_a_time_limit(lambda: CHECKER.help_text(source))
        self.assertIsInstance(outcome.get("failure"), CHECKER.HelpUnreadable,
                              "the unclosed comment must be reported as an unreadable declaration")

    def test_the_census_fails_the_run_over_an_unclosed_comment(self):
        # The same truncation through the run, which is what the gate reads: the census has to end
        # with a non-zero code, and it is the run that used to spin here.
        source = ("class SampleTool\n"
                  "{\n"
                  "    private static String buildHelp()\n"
                  "    {\n"
                  "        /* ### addField\n"
                  "        sb.append(\"### addField\\n\");\n"
                  "\n"
                  "    private String dispatch(String operation)\n"
                  "    {\n"
                  "        switch (operation)\n"
                  "        {\n"
                  "            case \"add_field\":\n"
                  "                return \"ok\";\n"
                  "        }\n"
                  "        return null;\n"
                  "    }\n")
        outcome = within_a_time_limit(lambda: run_over(source))
        self.assertNotIn("failure", outcome,
                         "the census must report the unreadable declaration: %s" % outcome)
        code, output = outcome["value"]
        self.assertEqual(1, code)
        self.assertIn("could not be read", output)

    def test_the_declaration_of_a_facade_without_a_body(self):
        # A declaration that ends in a semicolon: read on, the block that follows is another
        # member's, and the operations would be compared against a stranger's code.
        source = ("class SampleTool\n"
                  "{\n"
                  "    private static String buildHelp();\n"
                  "\n"
                  "    private String dispatch(String operation)\n"
                  "    {\n"
                  "        switch (operation)\n"
                  "        {\n"
                  "            case \"add_field\":\n"
                  "                return \"ok\";\n"
                  "        }\n"
                  "        return null;\n"
                  "    }\n"
                  "}\n")
        with self.assertRaises(CHECKER.HelpUnreadable):
            CHECKER.help_text(source)

    def test_the_census_fails_the_run(self):
        code, output = run_over(
            "class SampleTool\n"
            "{\n"
            "    private static String buildHelp()\n"
            "    {\n"
            "        sb.append(\"### addField\\n\");\n"
            "\n"
            "    private String dispatch(String operation)\n"
            "    {\n"
            "        switch (operation)\n"
            "        {\n"
            "            case \"add_field\":\n"
            "                return \"ok\";\n"
            "        }\n"
            "        return null;\n"
            "    }\n")
        self.assertEqual(1, code)
        self.assertIn("could not be read", output)


class AnOperationTheHelpNeverNames(unittest.TestCase):
    """The finding itself, at the level of the run: the exit code is what the build reads."""

    def test_an_operation_missing_from_the_help(self):
        code, output = run_over(facade(["### addField\n"], ["add_field", "remove_item"]))
        self.assertEqual(1, code)
        self.assertIn("remove_item", output)

    def test_every_dispatched_operation_is_named(self):
        code, output = run_over(facade(["### addField\n", "### removeItem\n"],
                                       ["add_field", "remove_item"]))
        self.assertEqual(0, code)
        self.assertIn("every dispatched operation", output)


class TheSpellingTheDispatcherAccepts(unittest.TestCase):
    """A help written in camelCase has named the snake_case label the dispatcher switches on."""

    def test_the_camel_case_form_counts_as_named(self):
        code, _output = run_over(facade(["### addField\n"], ["add_field"]))
        self.assertEqual(0, code)

    def test_the_snake_case_form_counts_as_named(self):
        code, _output = run_over(facade(["### add_field\n"], ["add_field"]))
        self.assertEqual(0, code)

    def test_both_spellings_of_every_operation(self):
        self.assertEqual(("add_field", "addField"), CHECKER.spellings("add_field"))
        self.assertEqual(("move", "move"), CHECKER.spellings("move"))
        self.assertEqual(("set_help_page", "setHelpPage"), CHECKER.spellings("set_help_page"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
