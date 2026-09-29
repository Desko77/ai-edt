#!/usr/bin/env python3
"""The regex that reads a tool's schema has to see every kind of property the schema can declare.

A kind it does not see is a parameter the map does not know the tool accepts. That is not a
cosmetic gap: the map is what `UnreadArguments` asks before deciding an argument is read by nobody,
and that check REFUSES the call rather than warning. A parameter missing from the map is therefore
one refusal away from breaking a call that works.

Measured on 11.09: `stringArrayProperty` was invisible, because the alternation tried `string`
first and then failed on `Array`. Twelve declarations naming objectFqns, objects, sections and tags
went unseen, and `revalidate_objects` stood in the map knowing only `projectName` - while the tool
has taken `objects` all along.
"""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

SCHEMA_PROP = __import__("importlib").import_module(
    "importlib.util").spec_from_file_location(
        "check_operation_params",
        pathlib.Path(__file__).resolve().parent.parent / "check-operation-params.py")
MODULE = __import__("importlib").util.module_from_spec(SCHEMA_PROP)
SCHEMA_PROP.loader.exec_module(MODULE)


class EveryDeclaredKindIsSeen(unittest.TestCase):
    """Each builder method of SchemaComposer, as a call site the regex has to match."""

    def names_in(self, source):
        return MODULE.SCHEMA_PROP.findall(source)

    def test_a_string_is_seen(self):
        self.assertEqual(["projectName"],
                         self.names_in('.stringProperty("projectName", "the project")'))

    def test_a_boolean_is_seen(self):
        self.assertEqual(["dryRun"], self.names_in('.booleanProperty("dryRun", "preview")'))

    def test_an_integer_is_seen(self):
        self.assertEqual(["limit"], self.names_in('.integerProperty("limit", "how many")'))

    def test_a_string_array_is_seen(self):
        # The one that was not. "string" matched the first six letters and the match then died on
        # "Array", so the declaration read as no declaration at all.
        self.assertEqual(["objects"],
                         self.names_in('.stringArrayProperty("objects", "the addresses")'))

    def test_an_array_is_seen(self):
        self.assertEqual(["operations"], self.names_in('.arrayProperty("operations", "a batch")'))

    def test_an_object_is_seen(self):
        self.assertEqual(["query"], self.names_in('.objectProperty("query", "the filter")'))

    def test_the_required_overload_is_seen_too(self):
        self.assertEqual(["objectFqns"], self.names_in(
            '.stringArrayProperty("objectFqns", "the addresses", true)'))

    def test_every_builder_of_the_composer_is_covered(self):
        """Whatever SchemaComposer can declare, the regex has to read - checked against the class.

        Written against the source rather than a list, so a builder added later fails here instead
        of quietly producing parameters the map cannot see.
        """
        composer = (pathlib.Path(__file__).resolve().parent.parent.parent
                    / "mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/wire"
                    / "SchemaComposer.java")
        import re
        declared = set(re.findall(r"public SchemaComposer (\w+Property)\(", composer.read_text(
            encoding="utf-8")))
        self.assertTrue(declared, "no builders found - the path or the class shape changed")
        unseen = [name for name in sorted(declared)
                  if not self.names_in('.%s("someName", "text")' % name)]
        self.assertEqual([], unseen, "builders the map cannot see: %s" % unseen)


class AMethodDeclaredOverSeveralLinesIsStillRead(unittest.TestCase):
    """The body of a method whose declaration spans more than one line.

    Found on 29.09: the signature regex required the opening brace on the line of the closing
    parenthesis or of `throws`, and `GitTool` writes the brace on the line below. Every method of
    that facade came back as an empty body, and the seven operations of it were then derived not
    from what each one reads but from the set of locals the whole facade binds - so each operation
    appeared to read every parameter its siblings read, and a narrowed set was never established.
    """

    SOURCE = (
        '    private String doCommit(IProject project, Git git, Map<String, String> params)\n'
        '        throws Exception\n'
        '    {\n'
        '        String message = JsonUtils.extractStringArgument(params, "message");\n'
        '        return message;\n'
        '    }\n'
        '\n'
        '    private String doStatus(Map<String, String> params) {\n'
        '        return JsonUtils.extractStringArgument(params, "projectName");\n'
        '    }\n'
        '\n'
        '    private String doRevert(Map<String, String> params) throws IOException,\n'
        '        CoreException\n'
        '    {\n'
        '        return JsonUtils.extractStringArgument(params, "fromRef");\n'
        '    }\n')

    def test_a_brace_on_the_line_below_the_declaration_is_seen(self):
        body = MODULE.method_body(self.SOURCE, "doCommit")
        self.assertIn('"message"', body)

    def test_a_throws_list_over_two_lines_is_seen(self):
        self.assertIn('"fromRef"', MODULE.method_body(self.SOURCE, "doRevert"))

    def test_a_declaration_on_one_line_is_still_seen(self):
        self.assertIn('"projectName"', MODULE.method_body(self.SOURCE, "doStatus"))

    def test_the_body_given_back_is_the_one_method(self):
        # The span is balanced, so it ends at this method's closing brace and not at the last
        # brace in the file.
        body = MODULE.method_body(self.SOURCE, "doCommit")
        self.assertNotIn('"projectName"', body)

    def test_every_method_of_the_git_facade_has_a_body(self):
        """The shape that was missed, checked against the file it was missed in."""
        source = (MODULE.OPS / "GitTool.java").read_text(encoding="utf-8")
        empty = [name for name in ("doStatus", "doBranches", "doLog", "doCommit", "doCheckout",
                                   "doShowFileChanges", "doRevertFile")
                 if not MODULE.method_body(source, name)]
        self.assertEqual([], empty, "methods whose body came back empty: %s" % empty)


if __name__ == "__main__":
    unittest.main()
