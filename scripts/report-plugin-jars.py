#!/usr/bin/env python3
"""Report which builds of the plugin every EDT installation runs and which jars lie unreferenced.

Reads every ``bundles.info`` it can find for an installation, lists the plugin jars in the bundle
stores those installations load from, and names the jars no ``bundles.info`` refers to. Nothing is
deleted and nothing is written: a jar a running EDT has open must stay, so removal is a separate
step for a moment when no EDT is running.

    report-plugin-jars.py [--bundle ru.aiedt.mcp.server] [--root DIR ...]

Without ``--root`` the usual places are searched: the Eclipse profile area in the home directory,
the shared p2 pool, and the EDT installations of the 1C launcher and of Program Files.
"""
import argparse
import os
import sys
from pathlib import Path

DEFAULT_BUNDLE = "ru.aiedt.mcp.server"


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
    args = parser.parse_args(argv)
    roots = [Path(root) for root in args.root] or default_roots()
    lines, _ = report(roots, args.bundle)
    print("searched: " + ", ".join(str(root) for root in roots))
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
