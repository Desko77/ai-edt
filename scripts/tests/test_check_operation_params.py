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


if __name__ == "__main__":
    unittest.main()
