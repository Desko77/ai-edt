#!/usr/bin/env python3
"""The jar report is checked on a made-up installation, so it keeps telling a recorded jar from a stray one.

The report exists for one decision: which jars of the plugin no installation refers to. Two readings
carry it - the line of ``bundles.info`` that names the bundle, and the version in a jar file name -
and a third decides the answer, the comparison of the two by file name, because ``bundles.info``
records a location in three spellings (relative to the installation, relative through the pool, and
as a ``file:`` URL) while the jar is found by walking a directory.

Run: python3 scripts/tests/test_report_plugin_jars.py
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

SPEC = importlib.util.spec_from_file_location("report_plugin_jars", SCRIPTS / "report-plugin-jars.py")
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)

BUNDLE = "ru.aiedt.mcp.server"


class BundlesInfoIsRead(unittest.TestCase):
    """The line of the bundle is found in each spelling of its location."""

    def test_the_three_spellings_of_a_location(self):
        text = "\n".join([
            "#version=1",
            "org.eclipse.core.runtime,3.30.0,plugins/org.eclipse.core.runtime_3.30.0.jar,4,true",
            BUNDLE + ",0.2.0,plugins/" + BUNDLE + "_0.2.0.jar,4,false",
            BUNDLE + ",0.2.58.202610080711,../../pool/plugins/" + BUNDLE + "_0.2.58.202610080711.jar,4,false",
            BUNDLE + ",0.1.1,file:/C:/pool/plugins/" + BUNDLE + "_0.1.1.jar,4,false",
        ])
        recorded = REPORT.parse_bundles_info(text, BUNDLE)
        self.assertEqual(["0.2.0", "0.2.58.202610080711", "0.1.1"], [version for version, _ in recorded])

    def test_another_bundle_with_the_same_prefix_is_not_ours(self):
        text = BUNDLE + ".tests,0.2.0,plugins/" + BUNDLE + ".tests_0.2.0.jar,4,false"
        self.assertEqual([], REPORT.parse_bundles_info(text, BUNDLE))

    def test_a_comment_line_is_skipped(self):
        self.assertEqual([], REPORT.parse_bundles_info("#" + BUNDLE + ",0.2.0,plugins/x.jar,4,false", BUNDLE))


class AJarNameCarriesItsVersion(unittest.TestCase):
    """The version is read from the name, and a name of another bundle is refused."""

    def test_a_release_and_a_qualified_build(self):
        self.assertEqual("0.2.57", REPORT.jar_version(BUNDLE + "_0.2.57.jar", BUNDLE))
        self.assertEqual("0.2.58.202610080711", REPORT.jar_version(BUNDLE + "_0.2.58.202610080711.jar", BUNDLE))

    def test_another_bundle_and_another_file(self):
        self.assertIsNone(REPORT.jar_version(BUNDLE + ".tests_0.2.57.jar", BUNDLE))
        self.assertIsNone(REPORT.jar_version(BUNDLE + "_0.2.57.jar.bak", BUNDLE))


class TheReportNamesTheStrayJar(unittest.TestCase):
    """A jar no bundles.info refers to is counted, and a recorded one is not."""

    def test_one_recorded_one_stray(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            configuration = root / "edt" / "configuration" / "org.eclipse.equinox.simpleconfigurator"
            configuration.mkdir(parents=True)
            plugins = root / "edt" / "plugins"
            plugins.mkdir()
            (configuration / "bundles.info").write_text(
                BUNDLE + ",0.2.57,plugins/" + BUNDLE + "_0.2.57.jar,4,false\n", encoding="utf-8")
            (plugins / (BUNDLE + "_0.2.57.jar")).write_bytes(b"recorded")
            (plugins / (BUNDLE + "_0.2.56.202609300000.jar")).write_bytes(b"stray")
            lines, unreferenced = REPORT.report([root], BUNDLE)
        self.assertEqual(1, unreferenced)
        text = "\n".join(lines)
        self.assertIn("UNREFERENCED", text)
        self.assertIn(BUNDLE + "_0.2.56.202609300000.jar", text)
        self.assertEqual(1, sum(1 for line in lines if line.strip().startswith("recorded")))

    def test_no_installation_names_the_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            lines, unreferenced = REPORT.report([pathlib.Path(directory)], BUNDLE)
        self.assertEqual(0, unreferenced)
        self.assertIn("  none", lines)


if __name__ == "__main__":
    unittest.main()
