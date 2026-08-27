#!/usr/bin/env python3
"""Checks that every EDT API this plugin uses still exists in a given 1C:EDT release.

The plugin is compiled against one release and expected to run on several. Nothing in the build
notices when a later release drops a method we call: the compiler only ever sees the release in the
target platform, and the failure surfaces at run time, on the one code path that reached the missing
member. That is a defect which can live to a user and appear in a rare operation.

This answers the question without an installed environment - the p2 metadata and the bundles over
HTTP are enough - so it can run wherever the network reaches edt.1c.ru.

Two passes, because two kinds of use fail differently:

  calls       - every EDT type and member referenced from our compiled classes, read out of the
                bytecode. Complete by construction: it is whatever javac emitted.
  reflection  - members reached by name at run time (Class.forName, getMethod, getField). The
                compiler never sees these and neither does the first pass. They cannot be derived
                from the sources alone - the class a method is called on is usually not knowable
                statically - so the pairs live in a registry beside this script, and the pass both
                verifies the registry against the release AND fails when the sources name an EDT
                class the registry does not list. Without that second half the registry rots
                silently as code is added, which is the failure mode a census exists to prevent.

Usage:
    python3 scripts/check-edt-api.py                      # against every release in --against
    python3 scripts/check-edt-api.py --against 2026.2
    python3 scripts/check-edt-api.py --against 2026.1 --against 2026.2 --check

--check exits non-zero when anything we use is missing, which is what makes a removal in EDT turn a
build red instead of a note somebody has to read.
"""

import argparse
import io
import os
import re
import subprocess
import sys
import urllib.request
import zipfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET_FILE = os.path.join(REPO_ROOT, "mcp", "targets", "default", "default.target")
CLASSES = os.path.join(REPO_ROOT, "mcp", "bundles", "ru.aiedt.mcp.server", "target", "classes")
SOURCES = os.path.join(REPO_ROOT, "mcp", "bundles", "ru.aiedt.mcp.server", "src")
REGISTRY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "edt-reflection-registry.txt")
DEFAULT_CACHE = os.path.join(REPO_ROOT, ".cache", "edt-bundles")

SITE = "https://edt.1c.ru/downloads/releases/ruby/%s/"

# The namespaces that belong to 1C. Everything else on our classpath - Eclipse, EMF, Guava - is
# resolved by the target platform at build time and is not what this checks.
EDT_PACKAGES = ("com._1c.g5.", "com.e1c.g5.")

# javap writes references as "// Method owner.member:(descriptor)". InterfaceMethod and Field read
# the same way; a reference inside the current class carries no owner and is skipped by the "/".
REFERENCE = re.compile(r"//\s+(Method|InterfaceMethod|Field)\s+([\w/$]+)\.([\w$<>]+):(\S+)")
TYPE_REFERENCE = re.compile(r"//\s+class\s+([\w/$]+)")

# A type named in a descriptor rather than in an instruction: a method signature, a field type, the
# superclass of an anonymous class. javap prints these as "Lcom/_1c/...;" wherever they appear, and
# reading only the instruction comments missed every one of them - a whole class of dependency that
# could vanish from a release with the census still green.
DESCRIPTOR_TYPE = re.compile(r"L((?:com/_1c/g5|com/e1c/g5)[\w/$]+);")

# The first line of javap output for a type, from which the chain upwards is read.
DECLARATION = re.compile(r"(?:class|interface)\s+([\w.$]+)(?:<[^{]*?>)?\s*(?:extends\s+([^{]+?))?"
                         r"(?:\s+implements\s+([^{]+?))?\s*\{")

MEMBER_LINE = re.compile(r"\b([\w$]+)\s*\(|\b([\w$]+)\s*;")

# Every type has these whatever it extends, and javap does not repeat them per class.
OBJECT_MEMBERS = frozenset({"equals", "hashCode", "toString", "getClass", "notify", "notifyAll",
                            "wait", "clone", "finalize"})

# Reflective lookups as they are written in our sources.
REFLECTIVE_CLASS = re.compile(r'(?:Class\.forName|loadClass)\s*\(\s*"([\w.$]+)"')
REFLECTIVE_MEMBER = re.compile(r'get(?:Declared)?(?:Method|Field)\s*\(\s*"([\w$]+)"')
EDT_STRING = re.compile(r'"((?:com\._1c\.g5|com\.e1c\.g5)[\w.$]+)"')


def log(message):
    print(message, flush=True)


def javap():
    """The disassembler to run.

    Taken from JAVA_HOME when it is set, because the shells this is run from on a developer machine
    often have a JDK configured that way and nothing on PATH.
    """
    home = os.environ.get("JAVA_HOME")
    if home:
        for candidate in (os.path.join(home, "bin", "javap.exe"), os.path.join(home, "bin", "javap")):
            if os.path.exists(candidate):
                return candidate
    return "javap"


def target_release():
    """The release the plugin is built against, read from the target platform definition."""
    with open(TARGET_FILE, encoding="utf-8") as handle:
        found = re.search(r"/downloads/releases/ruby/([\d.]+)/", handle.read())
    return found.group(1) if found else None


def fetch(url, cache_path):
    """Downloads once and reuses. Bundles are large and a census is run repeatedly."""
    if os.path.exists(cache_path) and os.path.getsize(cache_path) > 0:
        return cache_path
    os.makedirs(os.path.dirname(cache_path), exist_ok=True)
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    with open(cache_path, "wb") as handle:
        handle.write(data)
    return cache_path


def package_index(release, cache_dir):
    """Maps an exported package to the bundle that provides it, for one release.

    Read from the p2 content metadata rather than by guessing bundle names from package names: the
    two diverge often enough that guessing would report a package missing when it has merely moved.
    """
    content = fetch(SITE % release + "content.jar",
                    os.path.join(cache_dir, release, "content.jar"))
    with zipfile.ZipFile(content) as archive:
        xml = archive.read("content.xml").decode("utf-8", "replace")

    index = {}
    bundles = {}
    for unit in re.finditer(r"<unit\b.*?</unit>", xml, re.S):
        body = unit.group(0)
        bundle = re.search(r"<provided namespace='osgi\.bundle' name='([\w.\-]+)' version='([\w.\-]+)'",
                           body)
        if not bundle:
            continue
        name, version = bundle.group(1), bundle.group(2)
        bundles.setdefault(name, version)
        for package in re.finditer(r"<provided namespace='java\.package' name='([\w.$]+)'", body):
            index.setdefault(package.group(1), (name, version))
    return index, bundles


def candidate_bundles(fqn, index, bundles):
    """Every bundle that might hold a type, best guess first.

    The exported-package index answers most of it outright. What it cannot answer is the x-internal
    classes - not exported, so absent from the index - and those are precisely the ones the audit
    called the quietest to break: package-private {@code getInjector()} reached by reflection. For
    them the bundle is found by name instead: an internal package is conventionally the bundle's
    own name with ".internal" inserted, so removing that segment names the bundle, and progressively
    shorter prefixes cover the rest.
    """
    seen = []
    package = fqn.rsplit(".", 1)[0]

    if package in index:
        seen.append(index[package])

    names = []
    if ".internal." in package or package.endswith(".internal"):
        names.append(package.replace(".internal.", ".").removesuffix(".internal"))
    names.append(package)
    trimmed = package
    while "." in trimmed:
        trimmed = trimmed.rsplit(".", 1)[0]
        names.append(trimmed)
        if ".internal." in trimmed or trimmed.endswith(".internal"):
            names.append(trimmed.replace(".internal.", ".").removesuffix(".internal"))

    for name in names:
        if name in bundles:
            candidate = (name, bundles[name])
            if candidate not in seen:
                seen.append(candidate)
    return seen


def bundle_jar(release, bundle, cache_dir):
    name, version = bundle
    filename = "%s_%s.jar" % (name, version)
    try:
        return fetch(SITE % release + "plugins/" + filename,
                     os.path.join(cache_dir, release, "plugins", filename))
    except Exception:
        return None


class Release:
    """One EDT release, answering whether a type and its members are there."""

    def __init__(self, name, cache_dir):
        self.name = name
        self.cache_dir = cache_dir
        self.index, self.bundles = package_index(name, cache_dir)
        self.jars = {}
        self.located = {}
        self.declared = {}
        self.missing_bundles = set()

    def download(self, bundle):
        if bundle not in self.jars:
            self.jars[bundle] = bundle_jar(self.name, bundle, self.cache_dir)
            if self.jars[bundle] is None:
                self.missing_bundles.add(bundle)
        return self.jars[bundle]

    def jar_for(self, fqn):
        """The jar that actually holds a type, or None.

        A candidate is only accepted once the class file is seen inside it. Accepting the first
        plausible bundle instead would report a type present because something else lived in the
        same place, which is the kind of answer a census must never give.
        """
        if fqn in self.located:
            return self.located[fqn]
        self.located[fqn] = None
        entry = fqn.replace(".", "/") + ".class"
        for bundle in candidate_bundles(fqn, self.index, self.bundles):
            jar = self.download(bundle)
            if jar is None:
                continue
            with zipfile.ZipFile(jar) as archive:
                if entry in archive.namelist():
                    self.located[fqn] = jar
                    break
        return self.located[fqn]

    def has_type(self, fqn):
        return self.jar_for(fqn) is not None

    def members(self, fqn):
        """Every member name a type declares or inherits, and whether the chain was fully read.

        Names only, not signatures. A member whose signature changed is a different question from a
        member that is gone, and only the second is answerable this cheaply; claiming to check the
        first would be a census that lies.

        The walk upwards does NOT stop at the edge of 1C. Most model types extend EMF, most
        exceptions extend Throwable, every enumeration extends Enum - so half of what we call is
        declared outside 1C, and a walk that stopped at the boundary reported all of it missing.
        What the walk cannot do is read a supertype it has no jar for: EMF and LTK live in other
        repositories. Such a chain comes back marked incomplete, and a member not found in it is
        reported as inherited from outside rather than as gone.

        @param fqn the type
        @return the member names, and whether every supertype could be read
        """
        if fqn in self.declared:
            return self.declared[fqn]
        self.declared[fqn] = (set(), True)
        jar = self.jar_for(fqn)
        classpath = os.pathsep.join(path for path in self.jars.values() if path)
        try:
            result = subprocess.run([javap(), "-p", "-classpath", classpath, fqn],
                                    capture_output=True, text=True, timeout=120)
        except Exception:
            self.declared[fqn] = (set(), False)
            return self.declared[fqn]
        if result.returncode != 0 or not result.stdout.strip():
            # Not readable here: a type from EMF, LTK or anything else outside the 1C repositories.
            self.declared[fqn] = (set(), False)
            return self.declared[fqn]

        names = set()
        supertypes = []
        for line in result.stdout.splitlines():
            declaration = DECLARATION.search(line)
            if declaration:
                for group in (declaration.group(2), declaration.group(3)):
                    if not group:
                        continue
                    for parent in group.split(","):
                        parent = parent.split("<")[0].strip()
                        if parent and parent != "java.lang.Object":
                            supertypes.append(parent)
                continue
            member = MEMBER_LINE.search(line)
            if member:
                names.add(member.group(1) or member.group(2))
        names |= OBJECT_MEMBERS

        complete = jar is not None
        for parent in supertypes:
            inherited, parent_complete = self.members(parent)
            names |= inherited
            complete = complete and parent_complete
        self.declared[fqn] = (names, complete)
        return self.declared[fqn]

    def has_member(self, fqn, member):
        """Whether a member is reachable on a type: declared, inherited, or beyond our reach.

        @param fqn the type
        @param member the member name
        @return "yes", "no", or "outside" when the chain leaves the repositories we can read
        """
        if member in ("<init>", "<clinit>"):
            return "yes" if self.has_type(fqn) else "no"
        names, complete = self.members(fqn)
        if member in names:
            return "yes"
        return "no" if complete else "outside"


def compiled_references():
    """Every EDT type and member our compiled classes refer to.

    Taken from the bytecode rather than the sources: this is what the class files actually call, so
    nothing an import or a wildcard hides can be missed.
    """
    if not os.path.isdir(CLASSES):
        return None, None
    classes = []
    for base, _dirs, names in os.walk(CLASSES):
        classes.extend(os.path.join(base, name) for name in names if name.endswith(".class"))
    if not classes:
        return None, None

    types = set()
    members = set()
    for batch in (classes[i:i + 60] for i in range(0, len(classes), 60)):
        # -s prints the JVM descriptor of every declared method and field. Without it a type used
        # only in a signature - a parameter, a return, a field - appears solely in the human-readable
        # declaration, which this does not parse, and so escaped the census entirely.
        output = subprocess.run([javap(), "-p", "-c", "-s"] + batch,
                                capture_output=True, text=True).stdout
        for line in output.splitlines():
            for descriptor in DESCRIPTOR_TYPE.finditer(line):
                types.add(descriptor.group(1).replace("/", "."))
            declaration = DECLARATION.search(line)
            if declaration:
                for group in (declaration.group(2), declaration.group(3)):
                    if not group:
                        continue
                    for parent in group.split(","):
                        parent = parent.split("<")[0].strip()
                        if parent.startswith(EDT_PACKAGES):
                            types.add(parent)
            reference = REFERENCE.search(line)
            if reference:
                owner = reference.group(2).replace("/", ".")
                if owner.startswith(EDT_PACKAGES):
                    types.add(owner)
                    members.add((owner, reference.group(3)))
                continue
            plain = TYPE_REFERENCE.search(line)
            if plain:
                owner = plain.group(1).replace("/", ".")
                if owner.startswith(EDT_PACKAGES):
                    types.add(owner)
    return types, members


def registry_entries():
    """The reflective targets, as (kind, class, member) rows.

    Kind is "type" for something we depend on, "probe" for one spelling among several tried in turn,
    and "id" for a string that is not a class at all. A member of "*" means only the class itself is
    reached by name.
    """
    if not os.path.exists(REGISTRY):
        return None
    entries = []
    with open(REGISTRY, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            parts = [part.strip() for part in line.split()]
            kind = "type"
            if parts[0] in ("probe", "id"):
                kind, parts = parts[0], parts[1:]
            if not parts:
                continue
            fqn = parts[0]
            members = parts[1].split(",") if len(parts) > 1 else ["*"]
            for member in members:
                entries.append((kind, fqn, member))
    return entries


def looks_like_a_class(name):
    """Whether an EDT identifier names a type rather than a bundle or a package.

    Our sources carry both, written the same way: "com._1c.g5.v8.dt.rcp" is a bundle id and
    "com._1c.g5.v8.dt.search.core.TextSearcher" is a class. Only the second can be looked for in a
    jar, and treating the first as a missing class would fill the census with findings that are not
    defects - the surest way to make people stop reading it. Told apart by the last segment starting
    with a capital, which is the convention both sides follow.

    Bundle and extension-point identifiers are therefore NOT covered by this census. A renamed
    bundle id breaks us just as silently, and answering that needs the p2 index rather than a jar.

    @param name the identifier as written in the source
    @return whether to treat it as a type
    """
    last = name.rsplit(".", 1)[-1]
    return bool(last) and last[0].isupper()


# A constant holding a class name, and the two ways a variable is given one.
CONSTANT = re.compile(r'\b([A-Z][A-Z0-9_]*)\s*=\s*"((?:com\._1c\.g5|com\.e1c\.g5)[\w.$]+)"')
CLASS_INTO_VARIABLE = re.compile(
    r'\b(\w+)\s*=[^;]*?(?:Class\.forName|loadClass)\s*\(\s*([\w."$]+?)\s*[,)]', re.S)
MEMBER_ON_VARIABLE = re.compile(r'\b(\w+)\.get(?:Declared)?(?:Method|Field)\s*\(\s*"([\w$]+)"')
# The same lookup written on an instance rather than on a class variable. The class is whatever the
# instance turns out to be at run time, so it cannot be paired from the sources - but it has to be
# COUNTED, which the pattern above cannot do: it needs a bare word before the dot, and here the dot
# follows a closing parenthesis. Measured 2026-08-27: 201 lookups in 39 files were reaching neither
# the pairs nor the unattached count, among them the getItems that three form command interface
# operations fail on under EDT 2026.2.
MEMBER_ON_INSTANCE = re.compile(
    r'\b(\w+)\s*\.\s*getClass\s*\(\s*\)\s*\.\s*get(?:Declared)?(?:Method|Field)\s*\(\s*"([\w$]+)"')
# The lookup chained straight onto the literal, with no variable in between. The class IS knowable
# here, so this pairs rather than merely counts. The optional comment is for the NON-NLS marker the
# sources carry between the two calls.
MEMBER_ON_FORNAME = re.compile(
    r'(?:Class\.forName|loadClass)\s*\(\s*"([\w.$]+)"\s*\)\s*(?://[^\n]*)?\s*'
    r'\.\s*get(?:Declared)?(?:Method|Field)\s*\(\s*"([\w$]+)"')
# Every lookup, however written. What this finds and the patterns above do not is invisibility
# itself: a spelling nobody anticipated would otherwise be added and say nothing.
ANY_MEMBER_LOOKUP = re.compile(r'\.\s*get(?:Declared)?(?:Method|Field)\s*\(\s*"([\w$]+)"')


def reflective_pairs_in_sources():
    """The (class, member) pairs that CAN be established by reading the sources.

    The same way the audit of 2026-08-06 did it by hand: find where a variable was given a class -
    from a literal or from a constant holding one - and attach the lookups made on that variable.
    Keyed on the variable name rather than on nearness, so a lookup is only attributed to a class
    the code actually put in that variable.

    What this does not reach: a class arriving as a parameter, or from a value computed at run time.
    Those are counted and reported rather than guessed at, because a wrong pairing would send the
    census looking for a member on the wrong type and report a defect that is not there.

    @return the pairs, the member names that could not be attached to any class, and the lookups no
            pattern here accounted for at all
    """
    pairs = set()
    unattached = set()
    unseen = set()
    for base, _dirs, names in os.walk(SOURCES):
        for name in names:
            if not name.endswith(".java"):
                continue
            with io.open(os.path.join(base, name), encoding="utf-8", errors="replace") as handle:
                text = handle.read()

            constants = {match.group(1): match.group(2) for match in CONSTANT.finditer(text)}
            holders = {}
            for match in CLASS_INTO_VARIABLE.finditer(text):
                source = match.group(2).strip()
                if source.startswith('"'):
                    fqn = source.strip('"')
                else:
                    fqn = constants.get(source)
                if fqn and fqn.startswith(EDT_PACKAGES):
                    holders[match.group(1)] = fqn

            # Where in the file each pattern read a member name. Kept so the leftover below is the
            # lookups themselves, not name collisions between two spellings of the same call.
            accounted = set()

            for match in MEMBER_ON_VARIABLE.finditer(text):
                accounted.add(match.span(2))
                variable, member = match.group(1), match.group(2)
                if variable in holders:
                    pairs.add((holders[variable], member))
                else:
                    unattached.add((name, member))

            # Written on an instance: the class is not knowable here, so it joins the unattached
            # rather than being guessed at. Counting it is the point - it used to vanish.
            for match in MEMBER_ON_INSTANCE.finditer(text):
                accounted.add(match.span(2))
                unattached.add((name, match.group(2)))

            # Chained onto the literal: the class is right there, so this pairs.
            for match in MEMBER_ON_FORNAME.finditer(text):
                accounted.add(match.span(2))
                fqn = match.group(1)
                if fqn.startswith(EDT_PACKAGES):
                    pairs.add((fqn, match.group(2)))
                else:
                    unattached.add((name, match.group(2)))

            # Whatever neither pattern reached, by position. Recorded so a spelling nobody
            # anticipated shows up as a number instead of as silence.
            for match in ANY_MEMBER_LOOKUP.finditer(text):
                if match.span(1) not in accounted:
                    unseen.add((name, match.group(1)))
    return pairs, unattached, unseen


def reflective_classes_in_sources():
    """EDT class names our sources reach by name, however they are written."""
    found = set()
    for base, _dirs, names in os.walk(SOURCES):
        for name in names:
            if not name.endswith(".java"):
                continue
            with io.open(os.path.join(base, name), encoding="utf-8", errors="replace") as handle:
                text = handle.read()
            for pattern in (REFLECTIVE_CLASS, EDT_STRING):
                for match in pattern.finditer(text):
                    value = match.group(1)
                    if value.startswith(EDT_PACKAGES) and looks_like_a_class(value):
                        found.add(value)
    return found


def check_release(release, types, members, entries, cache_dir):
    """Resolves everything against one release and returns the findings."""
    log("  reading %s" % release)
    target = Release(release, cache_dir)
    findings = []

    for fqn in sorted(types):
        if not target.has_type(fqn):
            findings.append(("calls", fqn, "", "type not found"))
    unjudged = 0
    absent_types = {f[1] for f in findings}
    for fqn, member in sorted(members):
        if fqn in absent_types:
            continue
        verdict = target.has_member(fqn, member)
        if verdict == "no":
            findings.append(("calls", fqn, member, "member not found"))
        elif verdict == "outside":
            unjudged += 1

    absent_probes = []
    for kind, fqn, member in entries:
        if kind == "id":
            # Not a class; nothing in a jar answers for it. Counted, never judged.
            continue
        if not target.has_type(fqn):
            if kind == "probe":
                absent_probes.append(fqn)
            else:
                findings.append(("reflection", fqn, "", "type not found"))
            continue
        if member == "*":
            continue
        verdict = target.has_member(fqn, member)
        if verdict == "no":
            findings.append(("reflection", fqn, member, "member not found"))
        elif verdict == "outside":
            unjudged += 1

    return target, findings, unjudged, absent_probes


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--against", action="append", metavar="RELEASE",
                        help="release to check against; repeatable. Defaults to the one built against.")
    parser.add_argument("--cache", default=DEFAULT_CACHE, help="where downloaded bundles are kept")
    parser.add_argument("--check", action="store_true",
                        help="exit non-zero when anything we use is missing")
    parser.add_argument("--registry-only", action="store_true",
                        help="only check that the registry still covers the sources; no network, no "
                             "compiled classes. This is the half that changes when WE commit, so it "
                             "belongs in the ordinary build; the rest changes when 1C publishes.")
    args = parser.parse_args()

    releases = args.against or [target_release()]
    releases = [release for release in releases if release]
    if not releases:
        log("no release to check against, and none readable from the target definition")
        return 2

    entries = registry_entries()
    if entries is None:
        log("no reflection registry at %s" % REGISTRY)
        return 2

    registered = {fqn for _kind, fqn, _member in entries}
    registered_pairs = {(fqn, member) for _kind, fqn, member in entries}
    # A probe may legitimately resolve nowhere and an id is not a class, so neither can be asked to
    # account for its members. Demanding that would make the guard red over the very cases the
    # registry marks as expected-absent.
    unverifiable = {fqn for kind, fqn, _member in entries if kind in ("probe", "id")}
    unregistered = sorted(reflective_classes_in_sources() - registered)
    pairs, unattached, unseen = reflective_pairs_in_sources()
    unregistered_pairs = sorted(pair for pair in pairs
                                if pair not in registered_pairs and pair[0] not in unverifiable)

    if args.registry_only:
        if not unregistered and not unregistered_pairs:
            log("every EDT class and member reached by name is registered (%d entries, %d of them "
                "member rows)" % (len(entries), len(registered_pairs) - len(registered)))
            if unattached:
                log("%d member lookups could not be attached to a class and are not checked"
                    % len(unattached))
            if unseen:
                log("%d member lookups matched no pattern here and were not even counted"
                    % len(unseen))
            return 0
        for fqn in unregistered:
            log("    reached by name, not registered: %s" % fqn)
        for fqn, member in unregistered_pairs:
            log("    member reached by name, not registered: %s.%s" % (fqn, member))
        log("add them to %s so the census checks them too"
            % os.path.relpath(REGISTRY, REPO_ROOT))
        return 1

    types, members = compiled_references()
    if types is None:
        log("no compiled classes at %s - build the plugin first" % CLASSES)
        return 2

    log("using %d types and %d members from the bytecode, %d registered reflective targets"
        % (len(types), len(members), len(entries)))

    # A reflective target the sources reach but nobody registered is the way this census goes stale:
    # the code grows, the registry does not, and the pass keeps reporting a clean count of the wrong
    # set. Caught here rather than in a release.
    failed = False
    for release in releases:
        target, findings, unjudged, absent_probes = check_release(
            release, types, members, entries, args.cache)
        if target.missing_bundles:
            log("  %s: %d bundles could not be downloaded" % (release, len(target.missing_bundles)))
        if findings:
            failed = True
            log("  %s: %d references do not resolve" % (release, len(findings)))
            for kind, fqn, member, why in findings:
                log("    [%s] %s%s - %s" % (kind, fqn, "." + member if member else "", why))
        else:
            log("  %s: everything we use is there" % release)
        if absent_probes:
            log("  %s: %d probed spellings resolve nowhere (ordinary - that is what probing is for)"
                % (release, len(absent_probes)))
        if unjudged:
            # Said out loud rather than folded into the clean count: these are members declared
            # above the 1C boundary, in EMF or LTK, which this cannot read and does not claim to.
            log("  %s: %d members inherited from outside 1C, not judged" % (release, unjudged))

    if unregistered or unregistered_pairs:
        failed = True
        for fqn in unregistered:
            log("    reached by name, not registered: %s" % fqn)
        for fqn, member in unregistered_pairs:
            log("    member reached by name, not registered: %s.%s" % (fqn, member))
        log("add them to %s so the census checks them too" % os.path.relpath(REGISTRY, REPO_ROOT))
    if unattached:
        # Stated rather than folded into the clean count: a lookup whose class arrives as a
        # parameter or from a computed value cannot be attributed to a type by reading the source,
        # and guessing would send the census looking for a member on the wrong one.
        log("%d member lookups could not be attached to a class and are not checked" % len(unattached))

    return 1 if (failed and args.check) else 0


if __name__ == "__main__":
    sys.exit(main())
