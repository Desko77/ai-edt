#!/usr/bin/env python3
"""The jar report is checked on a made-up installation, so it keeps telling a recorded jar from a stray one.

The report exists for one decision: which jars of the plugin no installation refers to. Two readings
carry it - the line of ``bundles.info`` that names the bundle, and the version in a jar file name -
and a third decides the answer, the comparison of the two by file name, because ``bundles.info``
records a location in three spellings (relative to the installation, relative through the pool, and
as a ``file:`` URL) while the jar is found by walking a directory.

The same files answer the other question the script is asked - which installations carry the bundle -
and that answer decides where an install goes. It is checked on a made-up set of installations: one
reading the bundles.info inside itself, one whose profile in the user's home is named after the hash
of its path, a profile left behind by a removed installation, and an installation whose ini names the
area it loads. Which of an installation's records its launcher actually reads is checked on the same
set, and so is a bundles.info that cannot be read at all.

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
import unittest.mock

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


def make_installation(directory, launcher="1cedtc.exe"):
    """An installation directory: a launcher, which is what marks the directory as one."""
    directory.mkdir(parents=True, exist_ok=True)
    (directory / launcher).write_bytes(b"")
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


def write_profile(root, install, version, lines):
    """A profile in the user's home named the way Eclipse names it for that installation path.

    The name carries the platform version and the hash of the installation path, which is the only
    thing that ties a profile to its installation once the installations are laid out anywhere.
    """
    profile = (pathlib.Path(root) / "home" / ".eclipse" /
               ("org.eclipse.platform_%s_%s_win32_win32_x86_64"
                % (version, REPORT.install_path_hash(install))))
    write_bundles_info(profile / "configuration", lines)
    return profile


def write_ini(install, lines):
    """The launcher ini of an installation, beside the executable it belongs to."""
    target = pathlib.Path(install) / "1cedt.ini"
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return target


class AnInstallationOwnsItsConfigurationArea(unittest.TestCase):
    """The bundles.info inside an installation belongs to that installation."""

    def test_the_record_is_attributed_to_the_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            install = make_installation(pathlib.Path(directory) / "edt")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.57,plugins/" + BUNDLE + "_0.2.57.jar,4,false"])
            found, unclaimed, _ = REPORT.installations([pathlib.Path(directory)], BUNDLE)
        self.assertEqual([], unclaimed)
        self.assertEqual(1, len(found))
        self.assertEqual(str(install), found[0]["install"])
        self.assertEqual(str(install / "1cedtc.exe"), found[0]["launcher"])
        self.assertEqual(["0.2.57"], found[0]["records"][0]["versions"])
        self.assertEqual("installation", found[0]["records"][0]["kind"])


class AProfileIsTiedToItsInstallationByTheHashOfItsPath(unittest.TestCase):
    """A profile in the user's home is owned by the installation whose path hash its name carries.

    The path itself is not enough to decide: a relative ``-configuration`` resolves from every
    installation at the same depth, so a single surviving installation would take the profile of a
    removed one. The name carries the hash of the path the profile was laid out for.
    """

    def test_the_hash_names_one_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            first = make_installation(root / "installs" / "edt-a")
            second = make_installation(root / "installs" / "edt-b")
            profile = write_profile(root, first, "4.38.0",
                                    [BUNDLE + ",0.2.58,plugins/" + BUNDLE + "_0.2.58.jar,4,false"])
            found, unclaimed, _ = REPORT.installations([root], BUNDLE)
        self.assertEqual([], unclaimed)
        by_install = {entry["install"]: entry for entry in found}
        self.assertEqual([str(first)], list(by_install))
        self.assertEqual(["profile"], [record["kind"] for record in by_install[str(first)]["records"]])
        self.assertTrue(by_install[str(first)]["records"][0]["bundlesInfo"].startswith(str(profile)))
        self.assertNotIn(str(second), by_install)

    def test_a_profile_of_a_removed_installation_belongs_to_nobody(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            surviving = make_installation(root / "installs" / "edt")
            removed = root / "installs" / "edt-removed"
            profile = write_profile(root, removed, "4.30.0",
                                    [BUNDLE + ",0.2.0,plugins/" + BUNDLE + "_0.2.0.jar,4,false"])
            found, unclaimed, _ = REPORT.installations([root], BUNDLE)
        self.assertEqual([], found)
        self.assertEqual(1, len(unclaimed))
        self.assertEqual("unclaimed", unclaimed[0]["kind"])
        self.assertTrue(unclaimed[0]["bundlesInfo"].startswith(str(profile)))
        self.assertNotEqual(REPORT.install_path_hash(surviving), REPORT.install_path_hash(removed))

    def test_a_name_without_a_hash_is_not_attributed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            make_installation(root / "installs" / "edt")
            write_bundles_info(root / "home" / ".eclipse" / "org.eclipse.platform_4.38.0" / "configuration",
                               [BUNDLE + ",0.2.0,plugins/" + BUNDLE + "_0.2.0.jar,4,false"])
            found, unclaimed, _ = REPORT.installations([root], BUNDLE)
        self.assertEqual([], found)
        self.assertEqual(1, len(unclaimed))


class TheActiveRecordIsTheOneTheLauncherLoads(unittest.TestCase):
    """An installation can record the bundle twice; only one of the two decides what it runs.

    An installation under Program Files cannot write its own configuration area, so Eclipse lays out
    a profile in the user's home and the launcher reads that. The area inside the installation stays
    behind with whatever it held. Comparing the higher of the two is right only by accident, and
    wrong as soon as the stale one is the higher.
    """

    def test_the_profile_decides_over_the_area_inside_the_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "installs" / "edt")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.59.202610090000,plugins/" + BUNDLE + "_0.2.59.jar,4,false"])
            profile = write_profile(root, install, "4.38.0",
                                    [BUNDLE + ",0.2.58,plugins/" + BUNDLE + "_0.2.58.jar,4,false"])
            found, _, _ = REPORT.installations([root], BUNDLE)
        records = {record["kind"]: record for record in found[0]["records"]}
        self.assertTrue(records["profile"]["active"])
        self.assertFalse(records["installation"]["active"])
        self.assertTrue(records["profile"]["bundlesInfo"].startswith(str(profile)))

    def test_the_area_named_by_the_ini_decides(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "installs" / "edt")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.60,plugins/" + BUNDLE + "_0.2.60.jar,4,false"])
            write_profile(root, install, "4.38.0",
                          [BUNDLE + ",0.2.58,plugins/" + BUNDLE + "_0.2.58.jar,4,false"])
            write_ini(install, ["-configuration", "configuration", "-vmargs", "-Xmx4096m"])
            found, _, _ = REPORT.installations([root], BUNDLE)
        records = {record["kind"]: record for record in found[0]["records"]}
        self.assertTrue(records["installation"]["active"])
        self.assertFalse(records["profile"]["active"])

    def test_the_area_named_as_a_file_url_decides(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "installs" / "edt")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.60,plugins/" + BUNDLE + "_0.2.60.jar,4,false"])
            write_profile(root, install, "4.38.0",
                          [BUNDLE + ",0.2.58,plugins/" + BUNDLE + "_0.2.58.jar,4,false"])
            write_ini(install, ["-configuration", (install / "configuration").as_uri(), "-vmargs"])
            found, _, _ = REPORT.installations([root], BUNDLE)
        records = {record["kind"]: record for record in found[0]["records"]}
        self.assertTrue(records["installation"]["active"])
        self.assertFalse(records["profile"]["active"])

    def test_the_hash_counts_utf16_units(self):
        self.assertEqual(REPORT.java_string_hash("abc"), 96354)
        self.assertEqual(REPORT.java_string_hash(chr(0x1F600)), 31 * 0xD83D + 0xDE00)

    def test_an_ini_naming_an_area_that_records_nothing_leaves_no_active_record(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "installs" / "edt")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.57,plugins/" + BUNDLE + "_0.2.57.jar,4,false"])
            write_ini(install, ["-configuration", "elsewhere", "-vmargs", "-Xmx4096m"])
            found, _, _ = REPORT.installations([root], BUNDLE)
        self.assertEqual([False], [record["active"] for record in found[0]["records"]])

    def test_an_installation_recording_the_bundle_once_has_that_record_active(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "installs" / "edt")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.57,plugins/" + BUNDLE + "_0.2.57.jar,4,false"])
            found, _, _ = REPORT.installations([root], BUNDLE)
        self.assertTrue(found[0]["records"][0]["active"])


class ABundlesInfoThatCannotBeReadIsReported(unittest.TestCase):
    """A bundles.info that cannot be read is named with its reason, not skipped.

    Skipping it answers "this installation records nothing", and a caller that installs would then
    report the machine consistent while a file it could not read still says otherwise.
    """

    def installations_with_a_swallowed_file(self, root, swallowed):
        """The report of a root whose named bundles.info raises when read."""
        original = pathlib.Path.read_text

        def failing(self, *args, **kwargs):
            if self == swallowed:
                raise PermissionError(13, "Access is denied")
            return original(self, *args, **kwargs)

        with unittest.mock.patch.object(pathlib.Path, "read_text", failing):
            return REPORT.installations([root], BUNDLE)

    def test_the_report_names_the_file_and_the_reason(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "installs" / "edt")
            info = write_bundles_info(install / "configuration",
                                      [BUNDLE + ",0.2.57,plugins/" + BUNDLE + "_0.2.57.jar,4,false"])
            found, unclaimed, unreadable = self.installations_with_a_swallowed_file(root, info)
        self.assertEqual([], found)
        self.assertEqual([], unclaimed)
        self.assertEqual([str(info)], [entry["bundlesInfo"] for entry in unreadable])
        self.assertIn("denied", unreadable[0]["reason"])

    def test_the_json_report_carries_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "installs" / "edt")
            info = write_bundles_info(install / "configuration",
                                      [BUNDLE + ",0.2.57,plugins/" + BUNDLE + "_0.2.57.jar,4,false"])
            original = pathlib.Path.read_text

            def failing(self, *args, **kwargs):
                if self == info:
                    raise PermissionError(13, "Access is denied")
                return original(self, *args, **kwargs)

            out = io.StringIO()
            with unittest.mock.patch.object(pathlib.Path, "read_text", failing):
                with contextlib.redirect_stdout(out):
                    REPORT.main(["--json", "--root", str(root)])
        payload = json.loads(out.getvalue())
        self.assertEqual([str(info)], [entry["bundlesInfo"] for entry in payload["unreadable"]])
        self.assertIn("denied", payload["unreadable"][0]["reason"])


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
