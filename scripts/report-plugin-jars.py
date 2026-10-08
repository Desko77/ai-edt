#!/usr/bin/env python3
"""Report which builds of the plugin every EDT installation runs and which jars lie unreferenced.

Reads every ``bundles.info`` it can find for an installation, lists the plugin jars in the bundle
stores those installations load from, and names the jars no ``bundles.info`` refers to. Nothing is
deleted and nothing is written: a jar a running EDT has open must stay, so removal is a separate
step for a moment when no EDT is running.

    report-plugin-jars.py [--bundle ru.aiedt.mcp.server] [--root DIR ...]
    report-plugin-jars.py --json [--bundle ru.aiedt.mcp.server] [--root DIR ...]

Without ``--root`` the usual places are searched: the Eclipse profile area in the home directory,
the shared p2 pool, and the EDT installations of the 1C launcher and of Program Files.

``--json`` answers a different question about the same files - which installations carry the plugin
and where each of them reads it from, as data for a caller that installs into them. Every record
names the installation it belongs to, so a build recorded only in an unattributed bundles.info is
reported as such rather than silently dropped, and a bundles.info that cannot be read is reported
instead of skipped. Each installation also carries which of its records its launcher actually
loads, so a caller compares versions against the one that decides what runs.
"""
import argparse
import json
import os
from urllib.parse import urlparse
from urllib.request import url2pathname
import re
import sys
from pathlib import Path

DEFAULT_BUNDLE = "ru.aiedt.mcp.server"

# Names a 1C:EDT installation is launched by. The console launcher is first: it is the one a
# headless p2 director runs from.
LAUNCHER_NAMES = ("1cedtc.exe", "1cedt.exe")

# Inis a launcher reads its own arguments from, beside the executable it belongs to.
INI_NAMES = ("1cedt.ini", "1cedtc.ini")

# Directory holding the bundles.info of a configuration area, between the area root and the file.
DIRECTORY_NAME = "org.eclipse.equinox.simpleconfigurator"

# A profile Eclipse lays out in the user's home is named after the product, its version, the
# install-path hash and the platform triple: org.eclipse.platform_4.38.0_2023930198_win32_win32_x86_64
PROFILE_NAME = re.compile(r"^(?P<product>[^_]+)_(?P<version>[0-9][0-9.]*)_(?P<pathHash>[0-9]+)_")


def default_roots():
    """The directories searched when the caller names none."""
    home = Path.home()
    roots = [home / ".eclipse", home / ".p2" / "pool"]
    local = os.environ.get("LOCALAPPDATA")
    if local:
        roots.append(Path(local) / "1C" / "1cedtstart" / "installations")
    program_files = os.environ.get("ProgramFiles")
    if program_files:
        roots.append(Path(program_files) / "1C" / "1CE" / "components")
    return [root for root in roots if root.is_dir()]


def parse_bundles_info(text, bundle):
    """The (version, location) pairs a bundles.info text records for a bundle.

    A line is ``symbolicName,version,location,startLevel,autoStart``; lines of other bundles and
    comment lines are skipped.
    """
    found = []
    for line in text.splitlines():
        if line.startswith("#"):
            continue
        fields = line.strip().split(",")
        if len(fields) >= 3 and fields[0] == bundle:
            found.append((fields[1], fields[2]))
    return found


def jar_version(name, bundle):
    """The version in a jar file name of the bundle, or None when the name is another bundle's."""
    prefix = bundle + "_"
    if not name.startswith(prefix) or not name.endswith(".jar"):
        return None
    return name[len(prefix):-len(".jar")]


def find_files(roots, file_name, depth):
    """Every file of that name under the roots, down to a depth, without following the whole disk."""
    found = []
    for root in roots:
        base = len(root.parts)
        for directory, subdirectories, files in os.walk(root):
            if len(Path(directory).parts) - base >= depth:
                subdirectories[:] = []
            if file_name in files:
                found.append(Path(directory) / file_name)
    return found


def find_jars(roots, bundle, depth):
    """Every jar of the bundle under a ``plugins`` directory below the roots."""
    found = []
    for root in roots:
        base = len(root.parts)
        for directory, subdirectories, files in os.walk(root):
            if len(Path(directory).parts) - base >= depth:
                subdirectories[:] = []
            if Path(directory).name != "plugins":
                continue
            for name in files:
                if jar_version(name, bundle) is not None:
                    found.append(Path(directory) / name)
    return found


def launcher_in(directory):
    """The 1C:EDT launcher an installation directory holds, or None when the directory is not one."""
    for name in LAUNCHER_NAMES:
        candidate = Path(directory) / name
        if candidate.is_file():
            return candidate
    return None


def find_installations(roots, depth):
    """Every directory under the roots that holds a 1C:EDT launcher."""
    found = []
    for root in roots:
        base = len(root.parts)
        for directory, subdirectories, files in os.walk(root):
            if len(Path(directory).parts) - base >= depth:
                subdirectories[:] = []
            if launcher_in(directory):
                found.append(Path(directory))
    return found


def config_area_of(bundles_info):
    """The configuration area a bundles.info sits in, one level above its own directory."""
    return Path(bundles_info).parent.parent


def java_string_hash(text):
    """Java's String.hashCode of a text, as the signed 32-bit value the platform returns."""
    value = 0
    units = text.encode("utf-16-le", "surrogatepass")
    for index in range(0, len(units), 2):
        value = (31 * value + int.from_bytes(units[index:index + 2], "little")) & 0xFFFFFFFF
    return value - 0x100000000 if value >= 0x80000000 else value


def install_path_hash(install_dir):
    """The hash Eclipse puts in the name of the profile it lays out for an installation.

    A profile in the user's home is the configuration area Eclipse falls back to when an
    installation's own area cannot be written, and its directory is named
    ``org.eclipse.platform_<version>_<hash>_<ws>_<os>_<arch>`` where the hash is the absolute value
    of the Java hash of the installation path. The name therefore names the installation the profile
    was laid out for, and nothing else: a profile left behind by a removed installation carries the
    hash of a path that no longer exists and belongs to nobody.

    Measured on a machine with two installations: the profile ``..._2023930198_...`` carries the
    hash of the 2026.2 installation path and ``..._935876301_...`` the hash of a 2025.1 one.
    """
    return str(abs(java_string_hash(str(Path(install_dir)))))


def profile_owner(config_area, installation_dirs):
    """The installation whose path hash the profile directory name carries, or none.

    The profile directory is ``org.eclipse.platform_...`` and its parent is the profile area, so the
    name is read from there. Nothing is guessed: a name without a hash, or a hash of a path no
    installation sits at, leaves the profile unattributed.
    """
    match = PROFILE_NAME.match(Path(config_area).parent.name)
    if not match:
        return []
    wanted = match.group("pathHash")
    return [install for install in installation_dirs if install_path_hash(install) == wanted]


def configured_area(install_dir):
    """The configuration area an installation's own ini names, or None.

    ``-configuration`` in the launcher ini is the launcher's own instruction and decides over every
    other signal. A relative value is resolved against the installation directory, which is how the
    ini Eclipse writes records one.
    """
    for name in INI_NAMES:
        ini = Path(install_dir) / name
        if not ini.is_file():
            continue
        try:
            lines = ini.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        value = None
        for index, line in enumerate(lines):
            stripped = line.strip()
            if stripped == "-configuration" and index + 1 < len(lines):
                value = lines[index + 1].strip()
                break
            if stripped.startswith("-configuration="):
                value = stripped.split("=", 1)[1].strip()
                break
        if value:
            if value.lower().startswith("file:"):
                value = url2pathname(urlparse(value).path)
            return os.path.normcase(os.path.normpath(os.path.join(str(install_dir), value)))
    return None


def active_record(install_dir, records):
    """Which of an installation's records its launcher loads bundles from.

    The order is the one the launcher itself decides in:

      - an area its own ini names with ``-configuration`` is the area it loads. No record of that
        area means the area holds no build of the bundle, which is a different answer from the build
        being recorded somewhere the launcher ignores;
      - otherwise a profile laid out for this installation path (its name carries the hash of that
        path) is what the launcher resolves to while the installation's own area cannot be written,
        which is the case for every installation under Program Files;
      - otherwise the configuration area inside the installation directory.
    """
    named = configured_area(install_dir)
    if named is not None:
        for record in records:
            area = config_area_of(record["bundlesInfo"])
            if os.path.normcase(os.path.normpath(area)) == named:
                return record
        return None
    for kind in ("profile", "installation"):
        for record in records:
            if record["kind"] == kind:
                return record
    return None


def installations(roots, bundle, depth=8):
    """The installations recording the bundle, and the records belonging to none or unreadable.

    Each installation is ``{install, launcher, records}`` and each record is
    ``{bundlesInfo, versions, locations, kind, active}`` with kind one of:

      installation - the configuration area inside the installation directory;
      profile      - a profile area laid out for that installation path;
      unclaimed    - a profile area laid out for no installation that is present.

    ``active`` marks the record of the area the installation's launcher loads bundles from, the one
    a caller has to compare and update. The other records are what an installation stopped reading
    from; a build recorded only there is not what runs.

    An unclaimed record is not dropped: a build recorded there is installed somewhere, and a caller
    that ignores it would report an installation as up to date while this file still says otherwise.
    A bundles.info that cannot be read is reported in the third list rather than skipped - a caller
    that reads only the installations would call the machine consistent on partial evidence.
    """
    installation_dirs = find_installations(roots, depth)
    records = {}
    unclaimed = []
    unreadable = []
    for info in sorted(find_files(roots, "bundles.info", depth)):
        try:
            recorded = parse_bundles_info(info.read_text(encoding="utf-8", errors="replace"), bundle)
        except OSError as error:
            unreadable.append({"bundlesInfo": str(info), "reason": str(error)})
            continue
        if not recorded:
            continue
        config_area = config_area_of(info)
        kind = "unclaimed"
        owner = None
        inside = config_area.parent
        if launcher_in(inside) and inside in installation_dirs:
            owner, kind = inside, "installation"
        else:
            owners = profile_owner(config_area, installation_dirs)
            if len(owners) == 1:
                owner, kind = owners[0], "profile"
        entry = {
            "bundlesInfo": str(info),
            "versions": [version for version, _ in recorded],
            "locations": [location for _, location in recorded],
            "kind": kind,
            "active": False,
        }
        if owner is None:
            unclaimed.append(entry)
        else:
            records.setdefault(owner, []).append(entry)
    found = []
    for install in sorted(records):
        active = active_record(install, records[install])
        for record in records[install]:
            record["active"] = record is active
        found.append({
            "install": str(install),
            "launcher": str(launcher_in(install)),
            "records": records[install],
        })
    return found, unclaimed, unreadable


def installation_report(roots, bundle):
    """The installations recording the bundle as data, for a caller that installs into them."""
    found, unclaimed, unreadable = installations(roots, bundle)
    return {
        "bundle": bundle,
        "roots": [str(root) for root in roots],
        "installations": found,
        "unclaimed": unclaimed,
        "unreadable": unreadable,
    }


def report(roots, bundle):
    """Build the report lines and the count of unreferenced jars."""
    lines = []
    referenced = set()
    infos = find_files(roots, "bundles.info", 8)
    lines.append("bundles.info files that name %s:" % bundle)
    named = 0
    for info in sorted(infos):
        try:
            recorded = parse_bundles_info(info.read_text(encoding="utf-8", errors="replace"), bundle)
        except OSError as error:
            lines.append("  %s: cannot be read (%s)" % (info, error))
            continue
        for version, location in recorded:
            named += 1
            referenced.add(Path(location).name)
            lines.append("  %s -> %s (%s)" % (info, version, location))
    if named == 0:
        lines.append("  none")
    jars = sorted(find_jars(roots, bundle, 8))
    lines.append("jars of %s on disk: %d" % (bundle, len(jars)))
    unreferenced = 0
    for jar in jars:
        used = jar.name in referenced
        if not used:
            unreferenced += 1
        lines.append("  %s %s (%d bytes)" % ("recorded    " if used else "UNREFERENCED", jar, jar.stat().st_size))
    lines.append("unreferenced jars: %d" % unreferenced)
    return lines, unreferenced


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bundle", default=DEFAULT_BUNDLE)
    parser.add_argument("--root", action="append", default=[])
    # For a caller that installs, so it reads the same directory rules this report does. The default
    # escaping keeps the document ASCII whatever a directory is called: PowerShell 5.1 reads the
    # output of a native command in the console code page, where a path outside it arrives mangled.
    parser.add_argument("--json", action="store_true", help="print installations as JSON")
    args = parser.parse_args(argv)
    roots = [Path(root) for root in args.root] or default_roots()
    if args.json:
        print(json.dumps(installation_report(roots, args.bundle), indent=2))
        return 0
    lines, _ = report(roots, args.bundle)
    print("searched: " + ", ".join(str(root) for root in roots))
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
