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

The installed state of an area - what p2 itself resolved, as unit ids - is read from the p2 profile
registry beside the configuration area, and is checked on made-up registries: gzip and plain
snapshots, numeric timestamp order, a corrupt latest snapshot an older clean one must not answer
for, the profile an area's config.ini names against several, a profile bound to another area, and
the exact legacy unit ids of the closed ordinary-forms delivery.

Run: python3 scripts/tests/test_report_plugin_jars.py
"""

import contextlib
import gzip
import importlib.util
import io
import json
import os
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
                    REPORT.main(["--json", "--bundle", BUNDLE, "--root", str(root)])
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
                REPORT.main(["--json", "--bundle", BUNDLE, "--root", str(root)])
        text = out.getvalue()
        self.assertTrue(all(ord(character) < 128 for character in text), "the JSON is not ASCII")
        payload = json.loads(text)
        self.assertEqual(BUNDLE, payload["bundle"])
        self.assertEqual([str(install)], [entry["install"] for entry in payload["installations"]])
        self.assertEqual(["0.2.57"], payload["installations"][0]["records"][0]["versions"])
        self.assertEqual([], payload["unclaimed"])


class EveryInstallationNamesTheAreaItLoads(unittest.TestCase):
    """An installation that loads an area another one owns records nothing there and still holds it.

    The records of a bundles.info go to one owner, so the installation whose ini points at that
    area is absent from the installations list. The consumers list names it with the area it loads,
    which is what a caller installing into that area compares against.
    """

    def test_an_ini_pointing_at_another_installations_area_is_a_consumer_of_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            owner = make_installation(root / "installs" / "edt-a")
            borrower = make_installation(root / "installs" / "edt-b")
            write_bundles_info(owner / "configuration",
                               [BUNDLE + ",0.2.60,plugins/" + BUNDLE + "_0.2.60.jar,4,false"])
            write_ini(borrower, ["-configuration", str(owner / "configuration")])
            found, _, _ = REPORT.installations([root], BUNDLE)
            listed = {entry["install"]: entry["loads"] for entry in REPORT.consumers([root])}
        self.assertEqual([str(owner)], [entry["install"] for entry in found])
        self.assertEqual(listed[str(owner)], listed[str(borrower)])

    def test_a_profile_laid_out_for_an_installation_is_the_area_it_loads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "installs" / "edt")
            write_bundles_info(install / "configuration", ["other.bundle,1.0,plugins/o.jar,4,false"])
            profile = write_profile(root, install, "4.38.0",
                                    [BUNDLE + ",0.2.58,plugins/" + BUNDLE + "_0.2.58.jar,4,false"])
            listed = REPORT.consumers([root])
        self.assertEqual(os.path.normcase(os.path.normpath(str(profile / "configuration"))),
                         listed[0]["loads"])

    def test_an_installation_without_a_profile_loads_its_own_area(self):
        with tempfile.TemporaryDirectory() as directory:
            install = make_installation(pathlib.Path(directory) / "edt")
            listed = REPORT.consumers([pathlib.Path(directory)])
        self.assertEqual(os.path.normcase(os.path.normpath(str(install / "configuration"))),
                         listed[0]["loads"])

    def test_the_json_report_carries_the_consumers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            make_installation(root / "edt")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                REPORT.main(["--json", "--bundle", BUNDLE, "--root", str(root)])
        payload = json.loads(out.getvalue())
        self.assertEqual(1, len(payload["consumers"]))
        self.assertEqual({"install", "launcher", "loads"}, set(payload["consumers"][0]))


class TheSharedDeliveryIsToldApartFromTheOldOne(unittest.TestCase):
    """A server of the shared delivery carries a second bundle; an old one never did.

    From 0.2.60 on the delivery installs ru.aiedt.mcp.server and ru.aiedt.mcp.oform at one
    version. A bundles.info recording such a server and no oform is an installation stopped
    half way; a server older than that never carried oform, and reading its profile as
    damaged would be wrong in the other direction.
    """

    SERVER = "ru.aiedt.mcp.server"
    OFORM = "ru.aiedt.mcp.oform"

    def states_for(self, *lines):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            make_installation(root / "edt")
            write_bundles_info(root / "edt" / "configuration", list(lines))
            return REPORT.shared_delivery_states([root])

    def line(self, bundle, version):
        return "%s,%s,plugins/%s_%s.jar,4,false" % (bundle, version, bundle, version)

    def test_an_old_server_without_oform_is_not_damage(self):
        states = self.states_for(self.line(self.SERVER, "0.2.35"))
        self.assertEqual(1, len(states))
        self.assertIsNone(states[0]["sharedDelivery"],
                          "before the shared delivery the oform bundle did not exist in the "
                          "public installation")
        self.assertNotIn("WITHOUT", "\n".join(REPORT.delivery_lines(states)))

    def test_a_shared_delivery_with_both_bundles_is_whole(self):
        states = self.states_for(self.line(self.SERVER, "0.2.60"),
                                 self.line(self.OFORM, "0.2.60"))
        self.assertIs(True, states[0]["sharedDelivery"])
        self.assertIn("carries both bundles", "\n".join(REPORT.delivery_lines(states)))

    def test_a_server_of_the_shared_delivery_without_oform_is_half(self):
        states = self.states_for(self.line(self.SERVER, "0.2.60"))
        self.assertIs(False, states[0]["sharedDelivery"])
        self.assertIn("WITHOUT", "\n".join(REPORT.delivery_lines(states)))

    def test_a_qualified_build_of_the_shared_delivery_counts_as_it(self):
        states = self.states_for(self.line(self.SERVER, "0.2.60.v202610171200"))
        self.assertIs(False, states[0]["sharedDelivery"],
                      "a build OF 0.2.60 belongs to the shared delivery, qualifier or not")

    def test_the_highest_server_version_decides(self):
        states = self.states_for(self.line(self.SERVER, "0.2.35"),
                                 self.line(self.SERVER, "0.2.60"),
                                 self.line(self.OFORM, "0.2.60"))
        self.assertIs(True, states[0]["sharedDelivery"])

    def test_an_oform_of_the_closed_delivery_is_not_the_new_bundle(self):
        states = self.states_for(self.line(self.SERVER, "0.2.60"),
                                 self.line(self.OFORM, "0.1.0"))
        self.assertIs(False, states[0]["sharedDelivery"],
                      "oform 0.1.0 is the closed delivery's bundle, not the one a "
                      "0.2.60 server installs beside itself")
        self.assertEqual(["0.1.0"], states[0]["oformVersions"])
        self.assertIn("0.1.0", states[0]["sharedDeliveryIssue"])
        text = "\n".join(REPORT.delivery_lines(states))
        self.assertNotIn("carries both bundles", text)
        self.assertIn("0.1.0", text)

    def test_a_qualifier_of_another_build_is_not_a_match(self):
        states = self.states_for(self.line(self.SERVER, "0.2.60.202610171200"),
                                 self.line(self.OFORM, "0.2.60.202610170900"))
        self.assertIs(False, states[0]["sharedDelivery"],
                      "one build produces both jars; another stamp is another artifact")
        self.assertIn("0.2.60.202610170900", states[0]["sharedDeliveryIssue"])

    def test_matching_qualified_builds_are_one_delivery(self):
        states = self.states_for(self.line(self.SERVER, "0.2.60.202610171200"),
                                 self.line(self.OFORM, "0.2.60.202610171200"))
        self.assertIs(True, states[0]["sharedDelivery"])

    def test_an_old_oform_beside_a_matching_one_still_counts(self):
        states = self.states_for(self.line(self.SERVER, "0.2.60"),
                                 self.line(self.OFORM, "0.1.0"),
                                 self.line(self.OFORM, "0.2.60"))
        self.assertIs(True, states[0]["sharedDelivery"])

    def test_a_matching_older_oform_does_not_hide_a_newer_mismatch(self):
        """The highest ordinary-forms bundle determines delivery consistency."""
        states = self.states_for(self.line(self.SERVER, "0.2.60"),
                                 self.line(self.OFORM, "0.2.60"),
                                 self.line(self.OFORM, "0.2.61"))
        self.assertIs(False, states[0]["sharedDelivery"])

    def test_a_newer_server_qualifier_cannot_match_an_older_bundle(self):
        """Server qualifiers participate in choosing the effective version."""
        states = self.states_for(self.line(self.SERVER, "0.2.60.202610170900"),
                                 self.line(self.SERVER, "0.2.60.202610171200"),
                                 self.line(self.OFORM, "0.2.60.202610170900"))
        self.assertIs(False, states[0]["sharedDelivery"])

    def test_the_highest_qualified_versions_can_match(self):
        """Matching highest versions remain consistent in the presence of an older record."""
        states = self.states_for(self.line(self.SERVER, "0.2.60.202610170900"),
                                 self.line(self.SERVER, "0.2.60.202610171200"),
                                 self.line(self.OFORM, "0.2.60.202610171200"))
        self.assertIs(True, states[0]["sharedDelivery"])

    def test_the_version_key_reads_numbers_not_text(self):
        self.assertLess(REPORT.version_key("0.2.9"), REPORT.version_key("0.2.60"))
        self.assertGreater(REPORT.version_key("0.2.61"), REPORT.version_key("0.2.60"))
        self.assertEqual(REPORT.version_key("0.2.60.v1"), REPORT.version_key("0.2.60"))


class ACallerNamesAPlaceBesideTheUsualOnes(unittest.TestCase):
    """--also-root adds a directory to the places searched, without searching any twice.

    A caller that knows one more place an installation sits - the installer of one running
    session, which holds that installation's own directory - still needs the usual places,
    because the area the installation loads can be the shared profile in the user's home.
    A directory named twice would be walked twice, and every bundles.info under it would
    then be reported twice.
    """

    def test_an_extra_root_beside_the_base_ones_is_searched(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            usual = root / "usual"
            elsewhere = root / "elsewhere"
            install = make_installation(elsewhere / "edt")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.60,plugins/" + BUNDLE + "_0.2.60.jar,4,false"])
            found, _, _ = REPORT.installations(
                REPORT.merged_roots([usual], [elsewhere]), BUNDLE)
        self.assertEqual([str(install)], [entry["install"] for entry in found])

    def test_the_json_report_carries_the_extra_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            usual = root / "usual"
            elsewhere = root / "elsewhere"
            install = make_installation(elsewhere / "edt")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.57,plugins/" + BUNDLE + "_0.2.57.jar,4,false"])
            out = io.StringIO()
            with unittest.mock.patch.object(REPORT, "default_roots", return_value=[usual]):
                with contextlib.redirect_stdout(out):
                    REPORT.main(["--json", "--bundle", BUNDLE, "--also-root", str(elsewhere)])
        payload = json.loads(out.getvalue())
        self.assertEqual([str(install)], [entry["install"] for entry in payload["installations"]])

    def test_an_extra_root_equal_to_a_base_one_is_not_searched_twice(self):
        base = [pathlib.Path("C:/usual")]
        self.assertEqual(1, len(REPORT.merged_roots(base, [pathlib.Path("C:/usual")])))

    def test_an_extra_root_inside_a_base_one_adds_nothing(self):
        base = [pathlib.Path("C:/usual")]
        self.assertEqual(base, REPORT.merged_roots(base, [pathlib.Path("C:/usual/edt")]))

    def test_an_extra_root_covering_a_base_one_takes_over(self):
        base = [pathlib.Path("C:/usual/inside")]
        self.assertEqual([pathlib.Path("C:/usual")],
                         REPORT.merged_roots(base, [pathlib.Path("C:/usual")]))


LEGACY_GROUP_IU = "ru.aiedt.mcp.oform.feature.feature.group"
LEGACY_JAR_IU = "ru.aiedt.mcp.oform.feature.feature.jar"


def write_p2_profile(area_root, units=(), profile_id="DefaultProfile", timestamp="1791574315314",
                     plain=False, config_folder=None, config_ini=None, root_id=None):
    """A p2 profile snapshot in the registry a configuration area's root carries.

    The snapshot is the XML p2 writes - root profile element, root properties binding it to
    the area, units as id and version - gzipped the way p2 stores it, or plain for the
    unzipped spelling. ``root_id`` writes a root profile id other than the directory's
    (a snapshot of another profile); ``config_ini`` writes an eclipse.p2.profile line into
    the area's configuration when given.
    """
    profile_dir = (pathlib.Path(area_root) / "p2" / "org.eclipse.equinox.p2.engine" /
                   "profileRegistry" / (profile_id + ".profile"))
    profile_dir.mkdir(parents=True, exist_ok=True)
    folder = config_folder if config_folder is not None else pathlib.Path(area_root) / "configuration"
    unit_lines = "".join("<unit id='%s' version='%s'/>" % (uid, version) for uid, version in units)
    xml = ("<?xml version='1.0' encoding='UTF-8'?>"
           "<?profile version='1.0.0'?>"
           "<profile id='%s' timestamp='%s'>"
           "<properties size='2'>"
           "<property name='org.eclipse.equinox.p2.configurationFolder' value='%s'/>"
           "<property name='org.eclipse.equinox.p2.installFolder' value='C:/edt-install'/>"
           "</properties>"
           "<units size='%d'>%s</units>"
           "</profile>") % (root_id or profile_id, timestamp, folder, len(units), unit_lines)
    name = "%s.profile%s" % (timestamp, "" if plain else ".gz")
    target = profile_dir / name
    if plain:
        target.write_text(xml, encoding="utf-8")
    else:
        with gzip.open(target, "wb") as stream:
            stream.write(xml.encode("utf-8"))
    if config_ini is not None:
        configuration = pathlib.Path(area_root) / "configuration"
        configuration.mkdir(parents=True, exist_ok=True)
        (configuration / "config.ini").write_text(config_ini, encoding="utf-8")
    return target


class TheInstalledStateIsReadFromTheP2Profile(unittest.TestCase):
    """Installed membership is what the area's p2 profile resolved, not what a directory caches.

    The profile is the registry beside the configuration area: one directory per profile id,
    one snapshot per p2 operation, and the latest snapshot of the selected profile answers
    by unit ids. A feature jar under features/ or plugins/ proves nothing either way, and
    the reading here never looks at one - which is the point.
    """

    def status_for(self, area_root, config_ini=None):
        if config_ini is not None:
            configuration = pathlib.Path(area_root) / "configuration"
            configuration.mkdir(parents=True, exist_ok=True)
            (configuration / "config.ini").write_text(config_ini, encoding="utf-8")
        return REPORT.p2_profile_status(pathlib.Path(area_root) / "configuration")

    def test_a_gzip_snapshot_with_the_legacy_units_is_known(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area, units=[(LEGACY_GROUP_IU, "0.1.0.202609212242"),
                                          (LEGACY_JAR_IU, "0.1.0.202609212242")])
            status = self.status_for(area)
        self.assertEqual("known", status["status"])
        self.assertEqual("DefaultProfile", status["profileId"])
        self.assertEqual("1791574315314", status["snapshot"])
        self.assertEqual([{"id": LEGACY_GROUP_IU, "version": "0.1.0.202609212242"},
                          {"id": LEGACY_JAR_IU, "version": "0.1.0.202609212242"}],
                         status["legacyUnits"])

    def test_a_plain_snapshot_is_the_same_reading(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area, units=[(LEGACY_GROUP_IU, "0.1.0")], plain=True)
            status = self.status_for(area)
        self.assertEqual("known", status["status"])
        self.assertEqual([LEGACY_GROUP_IU], [unit["id"] for unit in status["legacyUnits"]])

    def test_the_latest_snapshot_decides_not_the_history_beside_it(self):
        """Timestamps order as numbers: 10 is newer than 9, and a text order would flip them."""
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area, timestamp="9", units=[(LEGACY_GROUP_IU, "0.1.0")])
            write_p2_profile(area, timestamp="10")
            status = self.status_for(area)
        self.assertEqual("known", status["status"])
        self.assertEqual([], status["legacyUnits"],
                         "an older snapshot holding the legacy unit does not install it now")

    def test_a_legacy_unit_in_the_latest_snapshot_is_detected_without_any_cached_file(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area, timestamp="9")
            write_p2_profile(area, timestamp="10", units=[(LEGACY_GROUP_IU, "0.1.0")])
            status = self.status_for(area)
        self.assertEqual([LEGACY_GROUP_IU], [unit["id"] for unit in status["legacyUnits"]])

    def test_a_latest_snapshot_that_will_not_read_is_unknown_not_the_older_clean_one(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area, timestamp="9")
            profile_dir = (area / "p2" / "org.eclipse.equinox.p2.engine" /
                           "profileRegistry" / "DefaultProfile.profile")
            (profile_dir / "10.profile.gz").write_bytes(b"this is not a gzip stream")
            status = self.status_for(area)
        self.assertEqual("unknown", status["status"])
        self.assertIn("10.profile.gz", status["reason"])
        self.assertNotIn("legacyUnits", status,
                         "a snapshot that will not read is not answered from an older one")

    def test_a_latest_snapshot_that_is_not_a_profile_document_is_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            profile_dir = (area / "p2" / "org.eclipse.equinox.p2.engine" /
                           "profileRegistry" / "DefaultProfile.profile")
            profile_dir.mkdir(parents=True)
            with gzip.open(profile_dir / "1.profile.gz", "wb") as stream:
                stream.write(b"this is not xml")
            status = self.status_for(area)
        self.assertEqual("unknown", status["status"])
        self.assertIn("parsed", status["reason"])

    def test_a_well_formed_document_with_a_non_profile_root_is_unknown(self):
        """A matching id and area do not make another XML document an installed profile."""
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            snapshot = write_p2_profile(area, plain=True)
            content = snapshot.read_bytes().replace(b"<profile ", b"<other ", 1)
            snapshot.write_bytes(content.replace(b"</profile>", b"</other>"))
            status = self.status_for(area)
        self.assertEqual("unknown", status["status"])
        self.assertNotIn("legacyUnits", status)

    def test_a_profile_bound_to_another_area_is_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            other = pathlib.Path(directory) / "other-area"
            write_p2_profile(area, units=[(LEGACY_GROUP_IU, "0.1.0")],
                             config_folder=other / "configuration")
            status = self.status_for(area)
        self.assertEqual("unknown", status["status"])
        self.assertIn("bound", status["reason"])

    def test_a_profile_that_names_no_configuration_area_is_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            profile_dir = (area / "p2" / "org.eclipse.equinox.p2.engine" /
                           "profileRegistry" / "DefaultProfile.profile")
            profile_dir.mkdir(parents=True)
            xml = ("<?xml version='1.0'?><profile id='DefaultProfile' timestamp='1'>"
                   "<properties size='0'/><units size='0'/></profile>")
            with gzip.open(profile_dir / "1.profile.gz", "wb") as stream:
                stream.write(xml.encode("utf-8"))
            status = self.status_for(area)
        self.assertEqual("unknown", status["status"])
        self.assertIn("does not name", status["reason"])

    def test_the_profile_the_config_ini_names_is_selected(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area)
            write_p2_profile(area, profile_id="AppProfile", timestamp="2",
                             units=[(LEGACY_GROUP_IU, "0.1.0")])
            selected = self.status_for(
                area, config_ini="eclipse.p2.profile=AppProfile\nosgi.bundles.defaultStartLevel=4\n")
        self.assertEqual("known", selected["status"])
        self.assertEqual("AppProfile", selected["profileId"])
        self.assertEqual([LEGACY_GROUP_IU], [unit["id"] for unit in selected["legacyUnits"]])

    def test_the_ini_name_decides_over_the_default_id(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area, timestamp="2", units=[(LEGACY_GROUP_IU, "0.1.0")])
            write_p2_profile(area, profile_id="AppProfile", timestamp="2")
            selected = self.status_for(area, config_ini="eclipse.p2.profile=AppProfile\n")
        self.assertEqual([], selected["legacyUnits"],
                         "the profile the ini names is read even when DefaultProfile "
                         "sits beside it holding the legacy unit")

    def test_an_ini_naming_a_profile_the_registry_lacks_is_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area)
            status = self.status_for(area, config_ini="eclipse.p2.profile=Gone\n")
        self.assertEqual("unknown", status["status"])
        self.assertIn("Gone", status["reason"])

    def test_several_profiles_with_none_named_are_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area)
            write_p2_profile(area, profile_id="AppProfile", timestamp="2")
            status = self.status_for(area)
        self.assertEqual("unknown", status["status"])
        self.assertIn("2 profiles", status["reason"])

    def test_the_one_profile_of_a_registry_without_an_ini_is_used(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area, profile_id="OnlyOne", units=[(LEGACY_JAR_IU, "0.1.0")])
            status = self.status_for(area)
        self.assertEqual("known", status["status"])
        self.assertEqual("OnlyOne", status["profileId"])

    def test_an_area_without_a_registry_is_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            status = self.status_for(pathlib.Path(directory) / "area")
        self.assertEqual("unknown", status["status"])
        self.assertIn("no p2 profile registry", status["reason"])

    def test_a_profile_without_snapshots_is_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            (area / "p2" / "org.eclipse.equinox.p2.engine" / "profileRegistry" /
             "DefaultProfile.profile").mkdir(parents=True)
            status = self.status_for(area)
        self.assertEqual("unknown", status["status"])
        self.assertIn("no snapshot", status["reason"])

    def test_a_snapshot_of_another_profile_is_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area, root_id="NotThisOne")
            status = self.status_for(area, config_ini="eclipse.p2.profile=DefaultProfile\n")
        self.assertEqual("unknown", status["status"])
        self.assertIn("NotThisOne", status["reason"])

    def test_a_snapshot_with_a_dtd_is_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            profile_dir = (area / "p2" / "org.eclipse.equinox.p2.engine" /
                           "profileRegistry" / "DefaultProfile.profile")
            profile_dir.mkdir(parents=True)
            xml = ("<?xml version='1.0'?><!DOCTYPE profile [<!ENTITY x 'y'>]>"
                   "<profile id='DefaultProfile' timestamp='1'></profile>")
            with gzip.open(profile_dir / "1.profile.gz", "wb") as stream:
                stream.write(xml.encode("utf-8"))
            status = self.status_for(area)
        self.assertEqual("unknown", status["status"])
        self.assertIn("DTD", status["reason"])

    def test_a_snapshot_past_the_read_cap_is_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area, units=[(LEGACY_GROUP_IU, "0.1.0")])
            with unittest.mock.patch.object(REPORT, "MAX_PROFILE_BYTES", 32):
                status = self.status_for(area)
        self.assertEqual("unknown", status["status"])
        self.assertIn("cap", status["reason"])

    def test_the_oform_bundle_unit_is_not_a_legacy_unit(self):
        """The ru.aiedt.mcp.oform bundle id is the shared delivery's bundle too."""
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            write_p2_profile(area, units=[("ru.aiedt.mcp.oform", "0.2.60"),
                                          ("ru.aiedt.mcp.server.feature.feature.group", "0.2.60")])
            status = self.status_for(area)
        self.assertEqual("known", status["status"])
        self.assertEqual([], status["legacyUnits"])

    def test_a_units_own_properties_do_not_read_as_root_properties(self):
        """Real snapshots nest properties inside every unit; only root properties bind the area."""
        with tempfile.TemporaryDirectory() as directory:
            area = pathlib.Path(directory) / "area"
            profile_dir = (area / "p2" / "org.eclipse.equinox.p2.engine" /
                           "profileRegistry" / "DefaultProfile.profile")
            profile_dir.mkdir(parents=True)
            xml = ("<?xml version='1.0' encoding='UTF-8'?>"
                   "<profile id='DefaultProfile' timestamp='1'>"
                   "<properties size='1'>"
                   "<property name='org.eclipse.equinox.p2.configurationFolder' value='%s'/>"
                   "</properties>"
                   "<units size='1'>"
                   "<unit id='some.bundle' version='1.0.0'>"
                   "<properties size='1'>"
                   "<property name='org.eclipse.equinox.p2.configurationFolder' value='C:/elsewhere'/>"
                   "</properties></unit>"
                   "</units></profile>") % (area / "configuration").as_posix()
            with gzip.open(profile_dir / "1.profile.gz", "wb") as stream:
                stream.write(xml.encode("utf-8"))
            status = self.status_for(area)
        self.assertEqual("known", status["status"])


class TheJsonReportCarriesTheProfileStates(unittest.TestCase):
    """The installer's payload gains the p2 reading beside the fields it already had."""

    def payload_for(self, root, bundle=BUNDLE):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            REPORT.main(["--json", "--bundle", bundle, "--root", str(root)])
        return json.loads(out.getvalue())

    def test_the_single_bundle_shape_gains_p2_profiles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "installs" / "edt")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.60,plugins/" + BUNDLE + "_0.2.60.jar,4,false"])
            write_p2_profile(install, units=[(LEGACY_GROUP_IU, "0.1.0.202609212242")])
            payload = self.payload_for(root)
        self.assertEqual({"bundle", "roots", "installations", "unclaimed", "unreadable",
                          "consumers", "p2Profiles"}, set(payload))
        key = REPORT.normalised(install)
        self.assertEqual(["known"], [payload["p2Profiles"][key]["status"]])
        self.assertEqual([LEGACY_GROUP_IU],
                         [unit["id"] for unit in payload["p2Profiles"][key]["legacyUnits"]])

    def test_an_area_loaded_without_a_bundles_info_is_still_read(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "installs" / "edt")
            elsewhere = root / "elsewhere" / "area"
            write_ini(install, ["-configuration", str(elsewhere / "configuration")])
            write_p2_profile(elsewhere)
            payload = self.payload_for(root)
        self.assertIn(REPORT.normalised(elsewhere), payload["p2Profiles"])
        self.assertEqual("known", payload["p2Profiles"][REPORT.normalised(elsewhere)]["status"])

    def test_the_full_shape_carries_it_per_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            install = make_installation(root / "edt")
            write_bundles_info(install / "configuration",
                               [BUNDLE + ",0.2.57,plugins/" + BUNDLE + "_0.2.57.jar,4,false"])
            write_p2_profile(install)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                REPORT.main(["--json", "--root", str(root)])
        payload = json.loads(out.getvalue())
        for bundle_report in payload["bundles"]:
            self.assertIn("p2Profiles", bundle_report)


if __name__ == "__main__":
    unittest.main()
