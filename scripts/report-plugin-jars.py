#!/usr/bin/env python3
"""Report which builds of the plugin every EDT installation runs and which jars lie unreferenced.

Reads every ``bundles.info`` it can find for an installation, lists the plugin jars in the bundle
stores those installations load from, and names the jars no ``bundles.info`` refers to. Nothing is
deleted and nothing is written: a jar a running EDT has open must stay, so removal is a separate
step for a moment when no EDT is running.

    report-plugin-jars.py [--bundle ru.aiedt.mcp.server] [--root DIR ...]
    report-plugin-jars.py --json [--bundle ru.aiedt.mcp.server] [--root DIR ...]

``--bundle`` reports one bundle and keeps the machine answer a single-bundle caller reads.
Without it every bundle of the shared delivery is reported, and a bundles.info that records a
server build from the shared delivery on (0.2.60+) is checked for the bundle that joined it, at
the same full version: one build produces both jars, so an oform of another version - the 0.1.0
bundle of the closed delivery, or a build of another stamp - is not the bundle this server's
delivery installs, and the state reads as inconsistent rather than whole. A server of that
version with no ru.aiedt.mcp.oform beside it is an installation stopped half way, not a delivery
of one bundle. A server older than that carries no oform by design, and its absence is not
damage.

Without ``--root`` the usual places are searched: the Eclipse profile area in the home directory,
the shared p2 pool, and the EDT installations of the 1C launcher and of Program Files.
``--also-root`` names a directory searched beside them, for a caller that knows one more place an
installation sits; a directory another named root already covers is not searched twice.

``--json`` answers a different question about the same files - which installations carry the plugin
and where each of them reads it from, as data for a caller that installs into them. Every record
names the installation it belongs to, so a build recorded only in an unattributed bundles.info is
reported as such rather than silently dropped, and a bundles.info that cannot be read is reported
instead of skipped. Each installation also carries which of its records its launcher actually
loads, so a caller compares versions against the one that decides what runs. With no ``--bundle``
the JSON carries every bundle's payload under ``bundles`` beside the shared-delivery reading.

The JSON also carries ``p2Profiles``: for every configuration area it names, the installed state
of the p2 profile that area provisions from - the profile the area's config.ini selects (or the
one profile of the registry when it selects none), latest numeric snapshot, gzip or plain XML,
bound to the area by its configurationFolder property. That state is what p2 itself resolved as
installed, which no directory of cached jars proves either way, so it is what answers whether the
closed ordinary-forms delivery's feature is still installed in an area. A registry, snapshot or
binding the format cannot say answers ``unknown`` with the reason rather than an empty reading.
"""
import argparse
import gzip
import io
import json
import os
from urllib.parse import urlparse
from urllib.request import url2pathname
import re
import sys
import pathlib
from pathlib import Path
import xml.etree.ElementTree as ET
import zlib

DEFAULT_BUNDLE = "ru.aiedt.mcp.server"
OFORM_BUNDLE = "ru.aiedt.mcp.oform"
# The bundles a shared delivery installs, in report order.
DELIVERY_BUNDLES = (DEFAULT_BUNDLE, OFORM_BUNDLE)

# The first server version whose delivery installs the second bundle, as three numbers. A
# bundles.info recording a server at or above it and no oform is half a delivery; below it
# the oform bundle did not exist in the public installation at all.
FIRST_SHARED_VERSION = (0, 2, 60)

# The p2 engine lays its profile registry beside the configuration area it belongs to; each
# profile id is a directory of snapshots, one per p2 operation, named <timestamp>.profile.gz
# (a plain <timestamp>.profile is the same XML unzipped). The latest snapshot of the selected
# profile is the installed state p2 itself resolved - the membership a directory of cached
# jars or features never proves.
P2_REGISTRY_PARTS = ("p2", "org.eclipse.equinox.p2.engine", "profileRegistry")
SNAPSHOT_NAME = re.compile(r"^(?P<timestamp>\d+)\.profile(\.gz)?$")

# A real profile of an EDT installation measures a few megabytes decompressed; one past this
# cap is not a profile this code reads purposefully, and reading it is refused rather than
# attempted.
MAX_PROFILE_BYTES = 64 * 1024 * 1024

# The installed unit ids of the closed ordinary-forms delivery: its feature group and the
# artifact jar that follows the group. The ru.aiedt.mcp.oform BUNDLE id is not among them -
# the shared delivery installs that bundle too, so its presence says nothing about the closed
# one.
LEGACY_OFORM_IUS = ("ru.aiedt.mcp.oform.feature.feature.group",
                    "ru.aiedt.mcp.oform.feature.feature.jar")

CONFIGURATION_FOLDER_PROPERTY = "org.eclipse.equinox.p2.configurationFolder"
INSTALL_FOLDER_PROPERTY = "org.eclipse.equinox.p2.installFolder"
CACHE_PROPERTY = "org.eclipse.equinox.p2.cache"


def version_key(version):
    """The comparable part of a bundle version: the three numeric segments, qualifier aside.

    ``0.2.60.<stamp>`` is a build OF 0.2.60, so the qualifier never decides whether a record
    belongs to a shared delivery.
    """
    parts = []
    for segment in str(version).split(".")[:3]:
        digits = ""
        for char in segment:
            if char.isdigit():
                digits += char
            else:
                break
        parts.append(int(digits) if digits else 0)
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)


def full_version(version):
    """The whole comparable form of a bundle version: the three numbers and the qualifier.

    Unlike ``version_key``, the qualifier decides here: the shared delivery installs both
    bundles from one build, so two records name the same artifact only when their
    qualifiers are equal too - a build of another stamp with the same three numbers is a
    different jar, not this delivery's ordinary-forms bundle.
    """
    segments = str(version).split(".")
    qualifier = ".".join(segments[3:]) if len(segments) > 3 else ""
    return version_key(version), qualifier

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


def same_or_under(path, directory):
    """Whether a path is a directory itself or lies under it, spelled either way round."""
    candidate = pathlib.PurePath(os.path.normcase(os.path.normpath(str(path))))
    base = pathlib.PurePath(os.path.normcase(os.path.normpath(str(directory))))
    try:
        candidate.relative_to(base)
    except ValueError:
        return False
    return True


def merged_roots(base, extras):
    """The base search places plus the caller's, each directory once.

    A directory named twice is walked twice, and every bundles.info under it is then
    reported twice. An extra root inside a base one adds nothing, and a base root inside
    an extra one is dropped for the extra to cover it.
    """
    roots = list(base)
    for extra in extras:
        candidate = Path(extra)
        if any(same_or_under(candidate, root) for root in roots):
            continue
        roots = [root for root in roots if not same_or_under(root, candidate)]
        roots.append(candidate)
    return roots


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


def loaded_area(install_dir, areas):
    """The configuration area an installation's launcher loads bundles from, normalised.

    The order is the one ``active_record`` follows: an area the installation's ini names with
    ``-configuration``; otherwise a profile area laid out for this installation path among the areas
    found; otherwise the configuration area inside the installation directory. It is answered for an
    installation whether or not that area records the bundle, because an installation that loads an
    area another one owns records nothing there of its own and still holds it open while it runs.
    """
    named = configured_area(install_dir)
    if named is not None:
        return named
    for area in areas:
        if profile_owner(area, [install_dir]):
            return os.path.normcase(os.path.normpath(str(area)))
    return os.path.normcase(os.path.normpath(str(Path(install_dir) / "configuration")))


def consumers(roots, depth=8):
    """Every installation under the roots with the configuration area it loads, as data."""
    areas = sorted({config_area_of(info) for info in find_files(roots, "bundles.info", depth)})
    return [{"install": str(install), "launcher": str(launcher_in(install)),
             "loads": loaded_area(install, areas)}
            for install in sorted(find_installations(roots, depth))]


# --- the installed state of the p2 profile of a configuration area --------------
#
# What p2 resolved as installed is recorded as a profile: one registry beside the configuration
# area, one directory per profile id in it, one snapshot per p2 operation in that directory.
# Reading the latest snapshot of the selected profile answers by unit ids and versions, which
# no cached jar or feature directory proves either way. Every state the format cannot say -
# no registry, several profiles none selected, a latest snapshot that will not read or parse,
# a profile bound to another configuration area - answers "unknown" with the reason, and a
# caller that needs legacy absence reads that as refusal rather than absence.


def normalised(path):
    """A path comparable across the spellings the report and its callers write it in."""
    return os.path.normcase(os.path.normpath(str(path)))


def read_config_ini_profile_id(config_area):
    """The profile id the area's config.ini names with eclipse.p2.profile, or None.

    The value decides over the registry's own order: when several profiles share a registry,
    the ini is the one place that says which of them this area provisions from. A config.ini
    that is missing, unreadable or names no profile decides nothing, and the caller falls
    back to the registry holding exactly one profile.
    """
    ini = Path(config_area) / "config.ini"
    if not ini.is_file():
        return None
    try:
        text = ini.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        if key.strip() == "eclipse.p2.profile":
            return value.strip() or None
    return None


def read_snapshot_bytes(path, cap):
    """A snapshot's bytes, decompressed when gzipped, refused once they pass the cap.

    The cap bounds what a crafted or damaged archive can make this code hold: the answer for
    anything larger is the caller's unknown, never an attempted parse.
    """
    if path.name.endswith(".gz"):
        with gzip.open(path, "rb") as stream:
            data = stream.read(cap + 1)
    else:
        with open(path, "rb") as stream:
            data = stream.read(cap + 1)
    if len(data) > cap:
        raise ValueError("larger than the %d byte cap" % cap)
    return data


def parse_profile_snapshot(data):
    """(root attributes, root properties, legacy units) of one snapshot's XML.

    Streamed and cleared element by element: a profile of a real EDT installation carries a
    few thousand unit elements with nested provision data, and holding the whole tree is
    memory nobody asked for. ElementTree resolves no external entities, and a snapshot
    carrying a DTD is refused outright - the profiles p2 writes have neither.
    """
    if b"<!DOCTYPE" in data or b"<!ENTITY" in data:
        raise ValueError("carries a DTD or entity declaration")
    root_attributes = {}
    properties = {}
    units = []
    stack = []
    for event, element in ET.iterparse(io.BytesIO(data), events=("start", "end")):
        if event == "start":
            stack.append(element)
            if len(stack) == 1:
                if element.tag != "profile":
                    raise ValueError("root element is not a profile")
                root_attributes = dict(element.attrib)
            continue
        stack.pop()
        if len(stack) == 2:
            # Direct children of root end with exactly root and their parent open; a unit's
            # own nested properties end two levels deeper and are not root properties.
            if element.tag == "property":
                properties[element.get("name")] = element.get("value")
            elif element.tag == "unit":
                unit_id = element.get("id")
                if unit_id in LEGACY_OFORM_IUS:
                    units.append({"id": unit_id, "version": element.get("version")})
        element.clear()
    if not root_attributes:
        raise ValueError("is not a profile document")
    return root_attributes, properties, units


def latest_snapshot(profile_dir):
    """The newest snapshot of a profile directory, by the timestamp in its name.

    Timestamps are numbers, not text: 10.profile.gz is newer than 9.profile.gz, and an
    order by name would read them the other way round. Only the newest is returned - the
    history beside it is what earlier operations resolved, not the installed state.
    """
    stamps = []
    try:
        for entry in Path(profile_dir).iterdir():
            if not entry.is_file():
                continue
            match = SNAPSHOT_NAME.match(entry.name)
            if match:
                stamps.append((int(match.group("timestamp")), match.group("timestamp"), entry))
    except OSError:
        return None, None, "the profile directory cannot be listed"
    if not stamps:
        return None, None, "the profile holds no snapshot"
    best = max(stamps, key=lambda item: item[0])
    return best[2], best[1], None


def p2_profile_status(config_area):
    """The installed legacy state of one configuration area's p2 profile, as data.

    ``{"status": "known", ...}`` answers from the latest snapshot of the selected profile:
    the id the area's config.ini names, or the one profile the registry holds when the ini
    names none. ``{"status": "unknown", "reason": ...}`` is every state the format cannot
    say, and the known payload carries the unit ids of the closed delivery's feature with
    their versions, the snapshot's timestamp and the folders the profile names.
    """
    registry = Path(config_area).parent.joinpath(*P2_REGISTRY_PARTS)
    if not registry.is_dir():
        return {"status": "unknown", "reason": "the area holds no p2 profile registry"}
    try:
        profile_dirs = [entry for entry in registry.iterdir()
                        if entry.is_dir() and entry.name.endswith(".profile")]
    except OSError as error:
        return {"status": "unknown", "reason": "the p2 registry cannot be listed (%s)" % error}
    named = read_config_ini_profile_id(config_area)
    if named:
        if not any(entry.name == named + ".profile" for entry in profile_dirs):
            return {"status": "unknown",
                    "reason": "config.ini names the %s profile and the registry holds no such one" % named}
        selected = named
    elif len(profile_dirs) == 1:
        selected = profile_dirs[0].name[:-len(".profile")]
    elif not profile_dirs:
        return {"status": "unknown", "reason": "the p2 registry holds no profile"}
    else:
        return {"status": "unknown",
                "reason": "the p2 registry holds %d profiles and the area's config.ini names none"
                          % len(profile_dirs)}
    snapshot, stamp, reason = latest_snapshot(registry / (selected + ".profile"))
    if snapshot is None:
        return {"status": "unknown", "reason": reason}
    try:
        data = read_snapshot_bytes(snapshot, MAX_PROFILE_BYTES)
    except (OSError, EOFError, ValueError, zlib.error) as error:
        return {"status": "unknown",
                "reason": "the latest profile snapshot (%s) cannot be read: %s" % (snapshot.name, error)}
    try:
        root_attributes, properties, units = parse_profile_snapshot(data)
    except (ET.ParseError, ValueError) as error:
        return {"status": "unknown",
                "reason": "the latest profile snapshot (%s) cannot be parsed: %s" % (snapshot.name, error)}
    if root_attributes.get("id") != selected:
        return {"status": "unknown",
                "reason": "the latest snapshot is of the %s profile, not the selected %s"
                          % (root_attributes.get("id"), selected)}
    bound = properties.get(CONFIGURATION_FOLDER_PROPERTY)
    if not bound:
        return {"status": "unknown",
                "reason": "the profile does not name the configuration area it belongs to"}
    if normalised(bound) != normalised(config_area):
        return {"status": "unknown",
                "reason": "the profile is bound to %s, not this configuration area" % bound}
    return {
        "status": "known",
        "profileId": selected,
        "snapshot": stamp,
        "installFolder": properties.get(INSTALL_FOLDER_PROPERTY),
        "cache": properties.get(CACHE_PROPERTY),
        "legacyUnits": units,
    }


def p2_profile_statuses(config_areas):
    """The p2 status of every configuration area, keyed by its area root as normalised.

    The key is the root the registry sits in - the parent of the configuration directory -
    spelled the way a caller normalises the area it loads, so both sides meet without one
    re-deriving the other's paths.
    """
    statuses = {}
    for area in {normalised(area) for area in config_areas}:
        statuses[normalised(Path(area).parent)] = p2_profile_status(area)
    return statuses


def installation_report(roots, bundle):
    """The installations recording the bundle as data, for a caller that installs into them.

    ``consumers`` names every installation found with the area it loads, including those that
    record nothing: two installations loading one area hold one profile, and a caller installing
    into it has to know about the other one. ``p2Profiles`` reads the installed state of the p2
    profile of every area those two name, so the caller that must not install beside the closed
    delivery's feature asks the same report for it.
    """
    found, unclaimed, unreadable = installations(roots, bundle)
    consumer_list = consumers(roots)
    areas = {config_area_of(entry["bundlesInfo"]) for entry in unclaimed}
    for entry in found:
        for record in entry["records"]:
            areas.add(config_area_of(record["bundlesInfo"]))
    for consumer in consumer_list:
        areas.add(Path(consumer["loads"]))
    return {
        "bundle": bundle,
        "roots": [str(root) for root in roots],
        "installations": found,
        "unclaimed": unclaimed,
        "unreadable": unreadable,
        "consumers": consumer_list,
        "p2Profiles": p2_profile_statuses(areas),
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


def shared_delivery_states(roots, depth=8):
    """Per bundles.info that records the server, whether it carries the bundle that joined later.

    A server build from the shared delivery on installs ``ru.aiedt.mcp.oform`` beside itself,
    at the same full version - one build produces both jars. A bundles.info recording such a
    server and no oform is an installation stopped half way: a caller comparing the server
    line alone would read a whole delivery where there is half of one. An oform of another
    version is not the bundle this delivery installs either: the reading is inconsistent
    rather than whole, and ``sharedDeliveryIssue`` says which versions were found.
    ``sharedDelivery`` is None for a server older than the shared delivery - that
    installation never carried oform and is not damaged for lacking it.
    """
    states = []
    for info in sorted(find_files(roots, "bundles.info", depth)):
        try:
            text = info.read_text(encoding="utf-8", errors="replace")
        except OSError as error:
            states.append({"bundlesInfo": str(info), "error": str(error)})
            continue
        recorded = parse_bundles_info(text, DEFAULT_BUNDLE)
        if not recorded:
            continue
        server_versions = [version for version, _ in recorded]
        best = max(server_versions, key=full_version)
        entry = {"bundlesInfo": str(info), "serverVersions": server_versions}
        if version_key(best) < FIRST_SHARED_VERSION:
            entry["sharedDelivery"] = None
            states.append(entry)
            continue
        oform = parse_bundles_info(text, OFORM_BUNDLE)
        entry["oformVersions"] = [version for version, _ in oform]
        effective_oform = max(entry["oformVersions"], key=full_version, default=None)
        if effective_oform is not None and full_version(effective_oform) == full_version(best):
            entry["sharedDelivery"] = True
        elif oform:
            entry["sharedDelivery"] = False
            entry["sharedDeliveryIssue"] = (
                "%s %s does not match server %s - one build of the shared delivery installs "
                "both bundles at one version" % (OFORM_BUNDLE,
                                                 ", ".join(entry["oformVersions"]), best))
        else:
            entry["sharedDelivery"] = False
            entry["sharedDeliveryIssue"] = (
                "carries server %s WITHOUT %s - a shared delivery installs both"
                % (best, OFORM_BUNDLE))
        states.append(entry)
    return states


def delivery_lines(states):
    """The shared-delivery reading as report lines."""
    lines = ["shared delivery (server %s and %s from version %s on):"
             % (DEFAULT_BUNDLE, OFORM_BUNDLE, ".".join(str(n) for n in FIRST_SHARED_VERSION))]
    counted = [state for state in states if state.get("sharedDelivery") is not None]
    if not counted:
        lines.append("  no bundles.info records a server of the shared delivery or later")
        return lines
    for state in counted:
        if state["sharedDelivery"]:
            lines.append("  %s: carries both bundles (server %s, oform %s)"
                         % (state["bundlesInfo"], ", ".join(state["serverVersions"]),
                            ", ".join(state["oformVersions"])))
        else:
            lines.append("  %s: %s" % (state["bundlesInfo"], state["sharedDeliveryIssue"]))
    return lines


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--bundle", default=None,
                        help="one bundle to report; without it, every bundle of the delivery")
    parser.add_argument("--root", action="append", default=[])
    # For a caller that installs, so it reads the same directory rules this report does. The default
    # escaping keeps the document ASCII whatever a directory is called: PowerShell 5.1 reads the
    # output of a native command in the console code page, where a path outside it arrives mangled.
    parser.add_argument("--also-root", action="append", default=[],
                        help="a directory searched beside the usual places or the named roots")
    parser.add_argument("--json", action="store_true", help="print installations as JSON")
    args = parser.parse_args(argv)
    roots = merged_roots([Path(root) for root in args.root] or default_roots(),
                         [Path(root) for root in args.also_root])
    if args.json:
        if args.bundle:
            # The single-bundle shape a caller installs by: unchanged, so a reader of one
            # payload does not have to read into a wrapper.
            print(json.dumps(installation_report(roots, args.bundle), indent=2))
        else:
            print(json.dumps({
                "bundles": [installation_report(roots, bundle) for bundle in DELIVERY_BUNDLES],
                "sharedDelivery": shared_delivery_states(roots),
            }, indent=2))
        return 0
    print("searched: " + ", ".join(str(root) for root in roots))
    for bundle in ([args.bundle] if args.bundle else list(DELIVERY_BUNDLES)):
        lines, _ = report(roots, bundle)
        print("")
        print("\n".join(lines))
    if not args.bundle:
        print("")
        print("\n".join(delivery_lines(shared_delivery_states(roots))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
