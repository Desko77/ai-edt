#!/usr/bin/env python3
"""Where the production and test code of the reactor lives.

The repository carries more than one plugin bundle: `ru.aiedt.mcp.server` and, from the
shared delivery on, `ru.aiedt.mcp.oform`. Every census in scripts/ used to hold the
server's path as a constant, so a second bundle would have been invisible to all of them
at once. This module is the one place that knows the layout:

  mcp/bundles/<symbolic-name>/src      production code of a bundle;
  mcp/tests/<symbolic-name>.tests/src  the test fragment of that bundle, by name.

A bundle that is not on disk yet answers nothing rather than an error: a census over a
tree without the ordinary-forms bundle must keep covering the server exactly as before,
and the same census over the merged tree must pick the new bundle up without another
edit. The fragment is looked up by that explicit name and never by a glob over
mcp/tests - a directory that happens to sit there is not a fragment of anything this
module knows, and a census that paired one with a bundle would pair production code with
tests that cannot execute it.
"""

from __future__ import annotations

import pathlib

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
BUNDLES_DIR = REPO_ROOT / "mcp" / "bundles"
TESTS_DIR = REPO_ROOT / "mcp" / "tests"

SERVER = "ru.aiedt.mcp.server"
OFORM = "ru.aiedt.mcp.oform"

# The bundles a shared delivery carries. A release from SHARED_DELIVERY_SINCE on installs
# both; before it the ordinary-forms bundle was not part of the public installation, and a
# profile that old must not be read as damaged for lacking it.
SHARED_DELIVERY_SINCE = "0.2.60"
SHARED_DELIVERY_BUNDLES = (SERVER, OFORM)


def production_bundles() -> list[str]:
    """The symbolic names of the bundles whose sources are on disk, sorted by name."""
    if not BUNDLES_DIR.is_dir():
        return []
    return sorted(entry.name for entry in BUNDLES_DIR.iterdir()
                  if entry.is_dir() and (entry / "src").is_dir())


def bundle_src(bundle: str) -> pathlib.Path:
    """The source root of one bundle."""
    return BUNDLES_DIR / bundle / "src"


def fragment_src(bundle: str) -> pathlib.Path:
    """The source root of the test fragment of one bundle, by the naming rule."""
    return TESTS_DIR / (bundle + ".tests") / "src"


def census_roots() -> list[tuple[str, pathlib.Path, pathlib.Path | None]]:
    """Every bundle on disk, its source root and its fragment's source root when present.

    The fragment entry is None for a bundle without one: that is a state to report (its
    classes have no tests that can execute), not a reason to skip the bundle.
    """
    roots = []
    for bundle in production_bundles():
        tests = fragment_src(bundle)
        roots.append((bundle, bundle_src(bundle), tests if tests.is_dir() else None))
    return roots


def at_least(version: str, floor: str) -> bool:
    """Whether an OSGi version, qualifier allowed, is at or above a floor release.

    Compared on the three numeric segments only: `0.2.60.<stamp>` is a build OF 0.2.60,
    not a version after it, so the qualifier never decides this question.
    """
    def numbers(value: str) -> tuple[int, int, int]:
        parts = value.strip().split(".")[:3]
        out = []
        for part in parts:
            digits = ""
            for char in part:
                if char.isdigit():
                    digits += char
                else:
                    break
            out.append(int(digits) if digits else 0)
        while len(out) < 3:
            out.append(0)
        return (out[0], out[1], out[2])

    return numbers(version) >= numbers(floor)
