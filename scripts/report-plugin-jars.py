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
reported as such rather than silently dropped.
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

DEFAULT_BUNDLE = "ru.aiedt.mcp.server"

# Names a 1C:EDT installation is launched by. The console launcher is first: it is the one a
# headless p2 director runs from.
LAUNCHER_NAMES = ("1cedtc.exe", "1cedt.exe")

# Directory holding the bundles.info of a configuration area, between the area root and the file.
DIRECTORY_NAME = "org.eclipse.equinox.simpleconfigurator"

# A profile Eclipse lays out in the user's home is named after the product, its version, the
# install-path hash and the platform triple: org.eclipse.platform_4.38.0_2023930198_win32_win32_x86_64
PROFILE_NAME = re.compile(r"^(?P<product>[^_]+)_(?P<version>[0-9][0-9.]*)_")

# The bundle carrying the platform version of an installation.
PLATFORM_BUNDLE = "org.eclipse.platform_"


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


def installation_platform_version(install_dir):
    """The org.eclipse.platform version an installation carries, or None.

    The bundle is a directory in some installations and a jar in others, and its version carries a
    qualifier the profile name does not.
    """
    plugins = Path(install_dir) / "plugins"
    if not plugins.is_dir():
        return None
    for entry in sorted(plugins.iterdir()):
        if not entry.name.startswith(PLATFORM_BUNDLE):
            continue
        rest = entry.name[len(PLATFORM_BUNDLE):]
        return rest.split(".v", 1)[0] if ".v" in rest else rest
    return None


def profile_platform_version(config_area):
    """The platform version a shared profile directory is named after, or None."""
    match = PROFILE_NAME.match(Path(config_area).parent.name)
    return match.group("version") if match else None


def profile_owner(config_area, installation_dirs):
    """The installation whose launcher resolves its configuration area to this one.

    A configuration area outside every installation is a shared profile - the directory an
    installation reads its bundles from while being laid out somewhere else, which is how a
    read-only installation keeps a writable profile in the user's home. The launcher records the
    ``-configuration`` it resolved in ``eclipse.ini.ignored`` beside that area, as a path relative to
    the installation directory, and that record is what ties the two together.

    Several installations resolve to the same path when they sit at the same depth, so the relative
    path alone does not decide between them. The profile name carries the platform version its
    installation is built on, which separates them; without that, a tie is reported to the caller as
    no owner rather than as one of the candidates.
    """
    ignored = Path(config_area) / "eclipse.ini.ignored"
    if not ignored.is_file():
        return []
    try:
        lines = ignored.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []
    value = None
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped == "-configuration" and index + 1 < len(lines):
            value = lines[index + 1].strip()
            break
        if stripped.startswith("-configuration="):
            value = stripped.split("=", 1)[1].strip()
            break
    if not value:
        return []
    wanted = os.path.normcase(os.path.normpath(config_area))
    owners = []
    for install in installation_dirs:
        resolved = os.path.normcase(os.path.normpath(os.path.join(str(install), value)))
        if resolved == wanted:
            owners.append(install)
    if len(owners) > 1:
        version = profile_platform_version(config_area)
        if version:
            owners = [install for install in owners
                      if installation_platform_version(install) == version]
    return owners


def installations(roots, bundle, depth=8):
    """The installations recording the bundle, and the bundles.info files recording it that belong to none.

    Each installation is ``{install, launcher, records}`` and each record is
    ``{bundlesInfo, versions, locations, kind}`` with kind one of:

      installation - the configuration area inside the installation directory;
      profile      - a shared profile area that installation resolves to;
      unclaimed    - a profile area no single installation resolves to.

    An unclaimed record is not dropped: a build recorded there is installed somewhere, and a caller
    that ignores it would report an installation as up to date while this file still says otherwise.
    """
    installation_dirs = find_installations(roots, depth)
    records = {}
    unclaimed = []
    for info in sorted(find_files(roots, "bundles.info", depth)):
        try:
            recorded = parse_bundles_info(info.read_text(encoding="utf-8", errors="replace"), bundle)
        except OSError:
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
        }
        if owner is None:
            unclaimed.append(entry)
        else:
            records.setdefault(owner, []).append(entry)
    found = []
    for install in sorted(records):
        found.append({
            "install": str(install),
            "launcher": str(launcher_in(install)),
            "records": records[install],
        })
    return found, unclaimed


def installation_report(roots, bundle):
    """The installations recording the bundle as data, for a caller that installs into them."""
    found, unclaimed = installations(roots, bundle)
    return {
        "bundle": bundle,
        "roots": [str(root) for root in roots],
        "installations": found,
        "unclaimed": unclaimed,
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
