#!/usr/bin/env python3
"""The jar report is checked on a made-up installation, so it keeps telling a recorded jar from a stray one.

The report exists for one decision: which jars of the plugin no installation refers to. Two readings
carry it - the line of ``bundles.info`` that names the bundle, and the version in a jar file name -
and a third decides the answer, the comparison of the two by file name, because ``bundles.info``
records a location in three spellings (relative to the installation, relative through the pool, and
as a ``file:`` URL) while the jar is found by walking a directory.

The same files answer the other question the script is asked - which installations carry the bundle -
and that answer decides where an install goes. It is checked on a made-up set of installations: one
reading the bundles.info inside itself, one reading a shared profile in the user's home, and a set of
installations at equal depth that the relative ``-configuration`` alone cannot tell apart.

Run: python3 scripts/tests/test_report_plugin_jars.py
"""

import contextlib
import importlib.util
import io
import json
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


def make_installation(directory, launcher="1cedtc.exe", platform=None):
    """An installation directory: a launcher, and optionally the platform bundle it carries."""
    directory.mkdir(parents=True, exist_ok=True)
    (directory / launcher).write_bytes(b"")
    if platform:
        plugins = directory / "plugins"
        plugins.mkdir(exist_ok=True)
        (plugins / ("org.eclipse.platform_%s.v20250101-0000" % platform)).mkdir()
    return directory


def write_bundles_info(config_area, lines):
    """A bundles.info in a configuration area, made of the lines of the bundle that is recorded."""
    target = pathlib.Path(config_area) / "org.eclipse.equinox.simpleconfigurator" / "bundles.info"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return target


class AnInstallationIsFoundByItsLauncher(unittest.TestCase):
    """The installed directories are the ones carrying a launcher, not every directory holding files."""

    def test_a_launcher_marks_the_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "installs" / "edt")
            (root / "installs" / "not-an-edt").mkdir()
            found = REPORT.find_installations([root], 8)
        self.assertEqual([install], found)

    def test_the_console_launcher_is_preferred(self):
        with tempfile.TemporaryDirectory() as directory:
            install = make_installation(pathlib.Path(directory) / "edt", launcher="1cedt.exe")
            (install / "1cedtc.exe").write_bytes(b"")
            launcher = REPORT.launcher_in(install)
        self.assertEqual("1cedtc.exe", launcher.name)


class AnInstallationOwnsItsConfigurationArea(unittest.TestCase):
    """The bundles.info inside an installation belongs to that installation."""

    def test_the_record_is_attributed_to_the_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            install = make_installation(pathlib.Path(directory) / "edt")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.57,plugins/" + BUNDLE + "_0.2.57.jar,4,false"])
            found, unclaimed = REPORT.installations([pathlib.Path(directory)], BUNDLE)
        self.assertEqual([], unclaimed)
        self.assertEqual(1, len(found))
        self.assertEqual(str(install), found[0]["install"])
        self.assertEqual(str(install / "1cedtc.exe"), found[0]["launcher"])
        self.assertEqual(["0.2.57"], found[0]["records"][0]["versions"])
        self.assertEqual("installation", found[0]["records"][0]["kind"])


class ASharedProfileBelongsToOneInstallation(unittest.TestCase):
    """A profile outside every installation is tied to one by the -configuration the launcher recorded.

    The profile of a read-only installation sits in the user's home, and the relative path in that
    record resolves from every installation at the same depth - so equal-depth installations are a
    tie, decided by the platform version the profile is named after.
    """

    def build(self, root, platforms):
        installs = {}
        for name, platform in platforms.items():
            installs[name] = make_installation(root / "installs" / name, platform=platform)
        profile = root / "home" / ".eclipse" / "org.eclipse.platform_4.38.0_2023930198_win32_win32_x86_64"
        write_bundles_info(profile / "configuration",
                           [BUNDLE + ",0.2.58.202610060526,plugins/" + BUNDLE + "_0.2.58.jar,4,false"])
        relative = pathlib.Path("..", "..", "home", ".eclipse", profile.name, "configuration")
        (profile / "configuration" / "eclipse.ini.ignored").write_text(
            "-configuration\n%s\n" % relative.as_posix(), encoding="utf-8")
        return installs, profile

    def test_the_platform_version_separates_equal_depth_installations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            installs, profile = self.build(root, {"edt-2026": "4.38.0", "edt-2025": "4.30.0"})
            found, unclaimed = REPORT.installations([root], BUNDLE)
        self.assertEqual([], unclaimed)
        by_install = {entry["install"]: entry for entry in found}
        records = by_install[str(installs["edt-2026"])]["records"]
        self.assertEqual(["profile"], [record["kind"] for record in records])
        self.assertTrue(records[0]["bundlesInfo"].startswith(str(profile)))
        self.assertNotIn(str(installs["edt-2025"]), by_install)

    def test_an_unbreakable_tie_is_reported_rather_than_guessed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            installs, profile = self.build(root, {"edt-a": None, "edt-b": None})
            found, unclaimed = REPORT.installations([root], BUNDLE)
        self.assertEqual([], found)
        self.assertEqual(1, len(unclaimed))
        self.assertEqual("unclaimed", unclaimed[0]["kind"])
        self.assertTrue(unclaimed[0]["bundlesInfo"].startswith(str(profile)))


class TheJsonReportCarriesTheInstallations(unittest.TestCase):
    """--json prints the same answer as data, in ASCII whatever the directories are called."""

    def test_ascii_json_of_a_cyrillic_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "edt Демо")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.57,../../pool/plugins/" + BUNDLE + "_0.2.57.jar,4,false"])
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                REPORT.main(["--json", "--root", str(root)])
        text = out.getvalue()
        self.assertTrue(all(ord(character) < 128 for character in text), "the JSON is not ASCII")
        payload = json.loads(text)
        self.assertEqual(BUNDLE, payload["bundle"])
        self.assertEqual([str(install)], [entry["install"] for entry in payload["installations"]])
        self.assertEqual(["0.2.57"], payload["installations"][0]["records"][0]["versions"])
        self.assertEqual([], payload["unclaimed"])


if __name__ == "__main__":
    unittest.main()
