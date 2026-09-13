#!/usr/bin/env python3
"""Read the p2 addresses the build resolves against out of the target platform.

The conformance job boots the same 1C:EDT the plugin is compiled against. Naming that EDT twice -
once in the target and once in a workflow - is how a build and its runtime drift apart: the target
moves to a new release and the job keeps booting the old one, green the whole way. So the job asks
here instead.

Prints GitHub-Actions outputs on stdout:

    edt_p2, release_p2, orbit_p2, orbit_simrel_p2   the four repositories, comma-free
    edt_ius                                          the EDT features to install
    eclipse_archive                                  the runnable platform matching release_p2

Fails loudly when an address is missing rather than printing an empty value: an empty repository
list makes the p2 director resolve against nothing and report it as a resolution error three
minutes later, which reads like a broken plugin.
"""
import pathlib
import re
import sys

TARGET = pathlib.Path(__file__).resolve().parents[2] / "mcp" / "targets" / "default" / "default.target"

# The Eclipse release train a runnable platform archive is published under. The target names the
# release repository; this maps it to the drop that can actually be unpacked and started.
PLATFORM_ARCHIVE = {
    "2023-12": "https://archive.eclipse.org/eclipse/downloads/drops4/R-4.30-202312010110/"
               "eclipse-platform-4.30-linux-gtk-x86_64.tar.gz",
    "2024-12": "https://archive.eclipse.org/eclipse/downloads/drops4/R-4.34-202412050720/"
               "eclipse-platform-4.34-linux-gtk-x86_64.tar.gz",
    "2025-12": "https://archive.eclipse.org/eclipse/downloads/drops4/R-4.38-202512010920/"
               "eclipse-platform-4.38-linux-gtk-x86_64.tar.gz",
}

# The features that make an EDT, as opposed to the platform-support features, of which the target
# lists one per 1C platform version and none is needed to answer protocol questions.
WANTED_IUS = ("com._1c.g5.v8.dt.feature.feature.group",
              "com._1c.g5.v8.dt.thirdparty.feature.group")


def locations(text):
    for block in re.findall(r"<location[^>]*>(.*?)</location>", text, re.S):
        address = re.search(r'location="([^"]+)"', block)
        if address:
            yield address.group(1).rstrip("/") + "/", re.findall(r'<unit id="([^"]+)"', block)


def main():
    # The path is an argument so the refusals below can be shown to refuse, against a target with
    # a piece taken out, without editing the one the build uses.
    target = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else TARGET
    if not target.exists():
        print("the target platform is not where it was expected: " + str(target), file=sys.stderr)
        return 2
    text = target.read_text(encoding="utf-8")

    found = {}
    ius = set()
    for address, units in locations(text):
        if "edt.1c.ru" in address:
            found["edt_p2"] = address
            ius.update(unit for unit in units if unit in WANTED_IUS)
        elif "/releases/" in address:
            found["release_p2"] = address
        elif "orbit-aggregation" in address:
            found["orbit_simrel_p2"] = address
        elif "/orbit/" in address:
            found["orbit_p2"] = address

    missing = [name for name in ("edt_p2", "release_p2", "orbit_p2", "orbit_simrel_p2")
               if name not in found]
    if missing:
        print("the target names no " + ", ".join(missing)
              + " - the job would resolve against an incomplete closure", file=sys.stderr)
        return 2
    if not ius:
        print("the target asks for none of " + ", ".join(WANTED_IUS)
              + " - there would be no EDT to boot", file=sys.stderr)
        return 2

    train = re.search(r"/releases/([^/]+)/", found["release_p2"])
    archive = PLATFORM_ARCHIVE.get(train.group(1) if train else "")
    if not archive:
        print("no runnable platform archive is recorded for release train '"
              + (train.group(1) if train else "?")
              + "'. Add it to PLATFORM_ARCHIVE, matching the Eclipse base of that train.",
              file=sys.stderr)
        return 2

    for name in ("edt_p2", "release_p2", "orbit_p2", "orbit_simrel_p2"):
        print("{}={}".format(name, found[name]))
    print("edt_ius=" + ",".join(sorted(ius)))
    print("eclipse_archive=" + archive)
    return 0


if __name__ == "__main__":
    sys.exit(main())
