#!/usr/bin/env python3
"""The catalog reconciliation has to see a tool that is registered as a service.

From the shared delivery on, `ordinary_forms` plugs into the whiteboard as an OSGi
Declarative Services component: no line in McpHttpEndpoint, no group in ToolCategory.
The catalog reconciliation read the group table alone, so a documented ordinary_forms
row would have read as phantom and a missing one as fine - the same drift this check
exists to catch, one registration style later.

A tool is the whole registration chain: the Service-Component header of the bundle
manifest names the descriptor, the descriptor provides
ru.aiedt.mcp.server.toolkit.IMcpTool and names the implementation class, and that
class's getName() answers the name. A file that merely looks like a tool registers
nothing, a getName() inside a comment is not a method, and a declared service whose
chain cannot be read is a defect in the tree - the census stops with an error instead
of reading the tool as absent.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

BUNDLES = importlib.import_module("bundles")

SPEC = importlib.util.spec_from_file_location("check_tool_catalog", SCRIPTS / "check-tool-catalog.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

SERVER = "ru.aiedt.mcp.server"
OFORM = "ru.aiedt.mcp.oform"

TOOL_INTERFACE = "ru.aiedt.mcp.server.toolkit.IMcpTool"
TOOL_CLASS = "ru.aiedt.mcp.oform.tool.OrdinaryFormsTool"

# The manifest as bnd writes it: the Service-Component header spans a continuation line
# and names a second component that provides another interface.
REGISTERED_MANIFEST = (
    "Manifest-Version: 1.0\n"
    "Bundle-SymbolicName: " + OFORM + "\n"
    "Service-Component: OSGI-INF/ordinary-forms.xml,\n"
    " OSGI-INF/other-service.xml\n"
)

TOOL_DESCRIPTOR = """<?xml version="1.0" encoding="UTF-8"?>
<scr:component xmlns:scr="http://www.osgi.org/xmlns/scr/v1.3.0" name="ordinary.forms">
  <implementation class="%s"/>
  <service>
    <provide interface="%s"/>
  </service>
</scr:component>
""" % (TOOL_CLASS, TOOL_INTERFACE)

OTHER_DESCRIPTOR = """<?xml version="1.0" encoding="UTF-8"?>
<scr:component xmlns:scr="http://www.osgi.org/xmlns/scr/v1.3.0" name="other">
  <implementation class="ru.aiedt.mcp.oform.other.OtherService"/>
  <service>
    <provide interface="ru.aiedt.mcp.oform.other.OtherInterface"/>
  </service>
</scr:component>
"""

TOOL_JAVA = """package ru.aiedt.mcp.oform.tool;

public final class OrdinaryFormsTool {
    @Override
    public String getName()
    {
        return "ordinary_forms"; //$NON-NLS-1$
    }
}
"""


def write(path, text):
    pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(path).write_text(text, encoding="utf-8")


def register_tool(root, java=TOOL_JAVA, descriptor=TOOL_DESCRIPTOR):
    """A second bundle whose declarative services answer with ordinary_forms."""
    bundle = root / "bundles" / OFORM
    write(bundle / "META-INF" / "MANIFEST.MF", REGISTERED_MANIFEST)
    write(bundle / "OSGI-INF" / "ordinary-forms.xml", descriptor)
    write(bundle / "OSGI-INF" / "other-service.xml", OTHER_DESCRIPTOR)
    write(bundle / "src" / "ru/aiedt/mcp/oform/tool" / "OrdinaryFormsTool.java", java)


class AServiceToolIsCounted(unittest.TestCase):
    """A second bundle on disk contributes the name its registered getName() answers with."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        self.saved = BUNDLES.BUNDLES_DIR
        BUNDLES.BUNDLES_DIR = self.root / "bundles"
        # The server bundle of the fake tree is empty of tools: the names read here are
        # the real group table's plus whatever the fake second bundle declares.
        write(self.root / "bundles" / SERVER / "src" / "placeholder.java", "")

    def tearDown(self):
        BUNDLES.BUNDLES_DIR = self.saved
        self.tmp.cleanup()

    def names(self):
        return MODULE.names_from_source()

    def test_a_service_tool_name_is_counted(self):
        register_tool(self.root)
        self.assertIn("ordinary_forms", self.names())

    def test_the_group_table_still_answers(self):
        # Without the second bundle the answer is the group table's, as before.
        names = self.names()
        self.assertTrue(names, "the real group table has to keep answering")
        self.assertNotIn("ordinary_forms", names)

    def test_a_service_providing_another_interface_is_not_a_tool(self):
        bundle = self.root / "bundles" / OFORM
        write(bundle / "META-INF" / "MANIFEST.MF",
              "Manifest-Version: 1.0\n"
              "Bundle-SymbolicName: " + OFORM + "\n"
              "Service-Component: OSGI-INF/other-service.xml\n")
        write(bundle / "OSGI-INF" / "other-service.xml", OTHER_DESCRIPTOR)
        write(bundle / "src" / "ru/aiedt/mcp/oform/other" / "OtherService.java",
              "public final class OtherService {\n"
              "    public String getName()\n"
              "    {\n"
              "        return \"not_a_tool\";\n"
              "    }\n"
              "}\n")
        self.assertNotIn("not_a_tool", self.names())

    def test_an_unregistered_tool_file_is_not_read(self):
        write(self.root / "bundles" / OFORM / "src" / "ru/aiedt/mcp/oform" / "Catalog.java",
              "public final class Catalog {\n"
              "    public String getName()\n"
              "    {\n"
              "        return \"not_a_tool\";\n"
              "    }\n"
              "}\n")
        self.assertNotIn("not_a_tool", self.names())

    def test_a_commented_get_name_without_registration_is_not_counted(self):
        # No manifest, so no registration: the only getName() sits in a block comment.
        write(self.root / "bundles" / OFORM / "src" / "ExampleTool.java",
              "/* public String getName() { return \"phantom_probe\"; } */\n"
              "public class ExampleTool {}\n")
        names = self.names()
        self.assertNotIn("phantom_probe", names)

    def test_an_unregistered_tool_file_with_a_live_get_name_is_not_counted(self):
        write(self.root / "bundles" / OFORM / "src" / "ru/aiedt/mcp/oform" / "UnregisteredTool.java",
              "public final class UnregisteredTool {\n"
              "    public String getName()\n"
              "    {\n"
              "        return \"unregistered_tool\";\n"
              "    }\n"
              "}\n")
        self.assertNotIn("unregistered_tool", self.names())

    def test_a_name_only_in_a_comment_of_a_registered_class_is_not_counted(self):
        register_tool(self.root, java=(
            "package ru.aiedt.mcp.oform.tool;\n"
            "\n"
            "public final class OrdinaryFormsTool {\n"
            "    // public String getName() { return \"line_comment_name\"; }\n"
            "    /* public String getName() { return \"block_comment_name\"; } */\n"
            "    @Override\n"
            "    public String getName()\n"
            "    {\n"
            "        return \"ordinary_forms\"; //$NON-NLS-1$\n"
            "    }\n"
            "}\n"))
        names = self.names()
        self.assertIn("ordinary_forms", names)
        self.assertNotIn("line_comment_name", names)
        self.assertNotIn("block_comment_name", names)

    def test_a_registered_class_whose_only_get_name_sits_in_a_comment_stops_the_census(self):
        register_tool(self.root, java=(
            "package ru.aiedt.mcp.oform.tool;\n"
            "\n"
            "public final class OrdinaryFormsTool {\n"
            "    /* public String getName() { return \"ordinary_forms\"; } */\n"
            "}\n"))
        with self.assertRaises(SystemExit):
            self.names()

    def test_a_registered_tool_without_a_readable_name_stops_the_census(self):
        register_tool(self.root, java=(
            "package ru.aiedt.mcp.oform.tool;\n"
            "\n"
            "public final class OrdinaryFormsTool {\n"
            "    public String getName()\n"
            "    {\n"
            "        return compute();\n"
            "    }\n"
            "}\n"))
        with self.assertRaises(SystemExit):
            self.names()

    def test_a_declared_descriptor_that_is_missing_stops_the_census(self):
        bundle = self.root / "bundles" / OFORM
        write(bundle / "META-INF" / "MANIFEST.MF", REGISTERED_MANIFEST)
        write(bundle / "src" / "ru/aiedt/mcp/oform" / "placeholder.java", "")
        with self.assertRaises(SystemExit):
            self.names()

    def test_a_declared_tool_without_implementation_source_stops_the_census(self):
        bundle = self.root / "bundles" / OFORM
        write(bundle / "META-INF" / "MANIFEST.MF", REGISTERED_MANIFEST)
        write(bundle / "OSGI-INF" / "ordinary-forms.xml", TOOL_DESCRIPTOR)
        write(bundle / "OSGI-INF" / "other-service.xml", OTHER_DESCRIPTOR)
        write(bundle / "src" / "ru/aiedt/mcp/oform" / "placeholder.java", "")
        with self.assertRaises(SystemExit):
            self.names()

    def test_a_descriptor_without_an_implementation_class_stops_the_census(self):
        bundle = self.root / "bundles" / OFORM
        write(bundle / "META-INF" / "MANIFEST.MF", REGISTERED_MANIFEST)
        write(bundle / "OSGI-INF" / "ordinary-forms.xml",
              TOOL_DESCRIPTOR.replace(
                  '  <implementation class="%s"/>\n' % TOOL_CLASS, ""))
        write(bundle / "OSGI-INF" / "other-service.xml", OTHER_DESCRIPTOR)
        write(bundle / "src" / "ru/aiedt/mcp/oform" / "placeholder.java", "")
        with self.assertRaises(SystemExit):
            self.names()


class TheManifestHeaderIsFolded(unittest.TestCase):
    """A long Service-Component header is written with continuation lines."""

    def test_continuation_lines_are_one_value(self):
        manifest = ("Bundle-SymbolicName: " + OFORM + "\n"
                    "Service-Component: OSGI-INF/a.xml,\n"
                    " OSGI-INF/b.xml\n"
                    "Other-Header: value\n")
        self.assertEqual("OSGI-INF/a.xml,OSGI-INF/b.xml",
                         MODULE.manifest_header(manifest, "Service-Component"))

    def test_a_header_after_ours_ends_the_value(self):
        manifest = ("Service-Component: OSGI-INF/a.xml\n"
                    "Other-Header: one,\n"
                    " two\n")
        self.assertEqual("OSGI-INF/a.xml",
                         MODULE.manifest_header(manifest, "Service-Component"))
        self.assertEqual("one,two", MODULE.manifest_header(manifest, "Other-Header"))

    def test_a_blank_line_ends_the_main_section(self):
        manifest = ("Service-Component: OSGI-INF/a.xml\n"
                    "\n"
                    "Service-Component: OSGI-INF/b.xml\n")
        self.assertEqual("OSGI-INF/a.xml",
                         MODULE.manifest_header(manifest, "Service-Component"))

    def test_an_absent_header_answers_none(self):
        self.assertIsNone(MODULE.manifest_header("Bundle-Name: x\n", "Service-Component"))

    def test_component_descriptors_drop_properties_and_empty_entries(self):
        self.assertEqual(["OSGI-INF/a.xml", "OSGI-INF/b.xml"],
                         MODULE.component_descriptors(
                             "OSGI-INF/a.xml:key=value, ,OSGI-INF/b.xml"))


class CommentsAreStripped(unittest.TestCase):
    """A getName() inside a comment is not a method; a string literal is not a comment."""

    def test_line_and_block_comments_go(self):
        text = ("// public String getName() { return \"line\"; }\n"
                "/* public String getName() { return \"block\"; } */\n"
                "public String getName() { return \"live\"; }\n")
        stripped = MODULE.strip_java_comments(text)
        self.assertIn('return "live"', stripped)
        self.assertNotIn("line", stripped)
        self.assertNotIn("block", stripped)

    def test_a_slash_inside_a_string_literal_is_not_a_comment(self):
        text = 'String url = "http://example.com/x"; // trailing\n'
        stripped = MODULE.strip_java_comments(text)
        self.assertIn('"http://example.com/x"', stripped)
        self.assertNotIn("trailing", stripped)

    def test_an_unterminated_block_comment_is_dropped_whole(self):
        self.assertNotIn("name",
                         MODULE.strip_java_comments("/* getName() { name"))

    def test_an_escape_inside_a_literal_keeps_the_quote(self):
        text = 'String s = "\\\\"; getName()\n'
        self.assertIn("getName()", MODULE.strip_java_comments(text))


if __name__ == "__main__":
    unittest.main()
