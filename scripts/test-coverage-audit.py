#!/usr/bin/env python3
"""Reconciles every production class against the test suite.

The point is a full census rather than a spot check: each class in the plugin bundle is placed in
exactly one bucket, and the buckets have to add up to the total. A class counts as

  direct          - a test class named after it exists (FooTest for Foo);
  exercised       - no test of its own, but some test constructs or names it IN CODE. Comments and
                    string literals in the test sources do not count: a class mentioned only in a
                    sentence explaining something else is not being tested by that sentence, and
                    counting it was quietly inflating this bucket;
  tool-sweep      - an IMcpTool the server registers. Its declaration - name, description, schema,
                    required parameters - is checked for every registered tool at once by
                    McpToolSchemaContractTest, which walks the registry instead of naming classes;
  ui-bound        - it can only run with a real display or an Eclipse extension point behind it, so
                    a plain unit test cannot reach it (SWT/JFace widgets, handlers, label
                    providers);
  workspace-bound - its work is reading or mutating a live EDT project: an IProject, a BM model, a
                    debug session. The OSGi harness resolves those classes but cannot conjure the
                    project behind them, so coverage here comes from live verification, not units;
  untested        - plain logic nothing touches. This is the bucket to empty.

Nested and package-private helper types living inside another file are attributed to that file, so
the census counts compilation units, not every declared type.

The buckets answer WIDTH - is every class touched by something - and say nothing about how hard.
A class with one test is in the same bucket as a class with thirty, so a suite could be complete by
this census and thin everywhere. The second half of the report answers DEPTH: for every class that
has a test of its own, how many test methods that test carries, and which classes rest on one or
two. Depth is reported, not gated: a threshold on tests-per-class would be a number picked out of
the air, while the list of the thinnest is a work queue anyone can read.

Usage:
    python3 scripts/test-coverage-audit.py [--out FILE] [--check] [--thin N]

--check exits non-zero while the untested bucket is not empty, which makes the gap a build failure
rather than a note somebody has to remember.

Every production bundle under mcp/bundles is censused against its own test fragment under
mcp/tests, the fragment chosen by name (see scripts/bundles.py). A bundle that is not on
disk yet is simply absent from the run: over a tree without it the answer must be exactly
the answer over the server alone, and a zero-class reading for a bundle that IS there is a
defect of this census, not a clean result.
"""

import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bundles  # noqa: E402  (scripts/ is not a package; the path is the import)

DEFAULT_OUT = os.path.join(bundles.REPO_ROOT, "docs", "test-coverage.md")
REGISTRATION = bundles.bundle_src(bundles.SERVER) / "ru" / "aiedt" / "mcp" / "server" / "McpHttpEndpoint.java"
SWEEP_TEST = "McpToolSchemaContractTest"

# Launch targets only the server bundle carries. Scoped to that bundle so a class of the
# same name in another one is judged by what IT is, not excused by this one.
EXEMPT_BY_BUNDLE = {bundles.SERVER: {"Activator", "McpAutoStart"}}

# A class is out of reach of a plain JUnit run when it is built on widgets, on the workbench, or on
# an extension point that only the platform instantiates.
UI_IMPORTS = re.compile(
    r"^import\s+(org\.eclipse\.swt\.|org\.eclipse\.jface\.|org\.eclipse\.ui\.|"
    r"org\.eclipse\.core\.commands\.|org\.eclipse\.ltk\.|com\._1c\.g5\.v8\.dt\.ui)",
    re.M,
)
UI_SUPERTYPES = re.compile(
    r"\b(extends|implements)\b[^{]*\b("
    r"AbstractHandler|LabelProvider|ILabelProvider|ICommonLabelProvider|IContentProvider|"
    r"ITreeContentProvider|ICommonContentProvider|ViewerFilter|Dialog|TitleAreaDialog|"
    r"PreferencePage|IWorkbenchPreferencePage|ViewPart|WorkbenchWindowControlContribution|"
    r"CompoundContributionItem|IStartup|WorkbenchAdapter|IElementUpdater|IExecutableExtension"
    r")\b"
)

# The OSGi harness resolves these classes but cannot supply what they operate on: an EDT project on
# disk, a built BM model, a running debug session. What they do is drive those APIs, so a unit test
# would assert nothing beyond the guard clauses - the real check is a live call against a workspace.
WORKSPACE_IMPORTS = re.compile(
    r"^import\s+(com\._1c\.g5\.|com\.e1c\.g5\.|org\.eclipse\.core\.resources\.|"
    r"org\.eclipse\.debug\.|org\.eclipse\.emf\.ecore\.|com\.google\.inject\.)",
    re.M,
)

def java_files(root):
    for base, _dirs, names in os.walk(root):
        for name in names:
            if name.endswith(".java"):
                yield os.path.join(base, name)


def read(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)
LINE_COMMENT = re.compile(r"//[^\n]*")
STRING_LITERAL = re.compile(r'"(\\.|[^"\\\n])*"')


def code_only(text):
    """Strips comments and string literals, leaving the part of a test that actually runs.

    A class name in a javadoc paragraph, in a //-note or inside an assertion message is prose about
    the code, not use of it. Searching the raw text counted all three as coverage, which made the
    census report a wider suite than exists. Block comments go first so that a // inside one cannot
    be mistaken for the start of a line comment.

    A file containing a text block is left alone: the single-line string pattern would mis-parse it
    and start stripping code. Counting too much for one file is a known overstatement; stripping
    real code would be an invisible understatement, and this census exists to be trusted.
    """
    if '"""' in text:
        return text
    text = BLOCK_COMMENT.sub(" ", text)
    text = LINE_COMMENT.sub(" ", text)
    return STRING_LITERAL.sub('""', text)


TEST_METHOD = re.compile(r"@Test\b")


def test_method_count(text):
    """How many test methods a test class carries, ignoring the ones only talked about.

    Counted on the stripped source for the same reason the census is: an @Test written inside a
    javadoc example is documentation, and counting it would inflate exactly the number this is
    here to keep honest.
    """
    return len(TEST_METHOD.findall(code_only(text)))


def registered_tools():
    """The tool classes the server instantiates, which is exactly what the registry sweep walks."""
    if not REGISTRATION.is_file():
        return set()
    return set(re.findall(r"new\s+([A-Z]\w*)\s*\(", read(REGISTRATION)))


def classify(path, text, test_names, test_blob, swept, exempt):
    simple = os.path.splitext(os.path.basename(path))[0]
    if simple + "Test" in test_names:
        return "direct", simple
    if re.search(r"\b%s\b" % re.escape(simple), test_blob):
        return "exercised", simple
    if simple in swept and SWEEP_TEST in test_names:
        return "tool-sweep", simple
    if simple in exempt:
        return "ui-bound", simple
    if UI_IMPORTS.search(text) or UI_SUPERTYPES.search(text):
        return "ui-bound", simple
    if WORKSPACE_IMPORTS.search(text):
        return "workspace-bound", simple
    return "untested", simple


def audit(roots=None):
    """The census over every bundle on disk, or over the roots a caller names.

    `roots` is a list of (bundle, source root, fragment root or None) and exists for the
    tests of this script, which build fake trees; without it the layout of scripts/bundles
    decides. Direct coverage (FooTest for Foo) is read in the bundle's OWN fragment - a
    test class that cannot load a bundle's classes does not test them however it is named.
    The exercised bucket reads every fragment's code at once: a test of one fragment that
    constructs another bundle's exported class does execute it.
    """
    if roots is None:
        roots = bundles.census_roots()
    fragments = {}
    for bundle, _src, tests in roots:
        files = sorted(java_files(tests)) if tests else []
        fragments[bundle] = {
            "files": files,
            "names": {os.path.splitext(os.path.basename(p))[0] for p in files},
            "methods": {os.path.splitext(os.path.basename(p))[0]: test_method_count(read(p))
                        for p in files},
            "blob": "\n".join(code_only(read(p)) for p in files),
        }
    test_blob = "\n".join(part["blob"] for part in fragments.values())
    methods_per_test = {}
    for bundle, part in fragments.items():
        for name, count in part["methods"].items():
            # A same-named test class in the bundle's own fragment is the one that counts
            # for it; another fragment's never overrides that.
            methods_per_test.setdefault(name, count)
    test_count = sum(len(part["files"]) for part in fragments.values())

    swept = registered_tools()
    buckets = {"direct": [], "exercised": [], "tool-sweep": [], "ui-bound": [],
               "workspace-bound": [], "untested": []}
    for bundle, src, _tests in roots:
        exempt = EXEMPT_BY_BUNDLE.get(bundle, set())
        base = bundle.replace(".", "/") + "/"
        short = bundle.rsplit(".", 1)[-1]
        for path in sorted(java_files(src)):
            rel = os.path.relpath(path, src).replace(os.sep, "/")
            part = fragments.get(bundle) or {}
            bucket, simple = classify(path, read(path), part.get("names", set()),
                                      test_blob, swept, exempt)
            package = os.path.dirname(rel[len(base):]) or "(root)"
            if bundle != bundles.SERVER:
                # Two bundles share package names (ops, layout); the report names whose.
                package = "%s:%s" % (short, package)
            buckets[bucket].append((package, simple, "%s/%s" % (bundle, rel)))
    return buckets, test_count, methods_per_test


def depth_rows(buckets, methods_per_test):
    """Each directly-tested class against the number of test methods standing behind it."""
    rows = []
    for package, simple, _rel in buckets["direct"]:
        rows.append((methods_per_test.get(simple + "Test", 0), package, simple))
    return sorted(rows)


def depth_section(buckets, methods_per_test, thin):
    """The depth half of the report: the distribution, and the thinnest named."""
    rows = depth_rows(buckets, methods_per_test)
    total_methods = sum(methods_per_test.values())
    lines = [
        "## Depth",
        "",
        "Width says every class is touched. Depth says how hard. Below: for each class with a test",
        "of its own, how many test methods that test carries. A class resting on one test is as",
        "covered as one resting on thirty by the buckets above, and that is the blind spot this",
        "closes.",
        "",
        "| Test methods | Classes |",
        "|---:|---:|",
    ]
    banding = [(1, "1"), (2, "2"), (4, "3-4"), (8, "5-8"), (16, "9-16"), (10 ** 9, "17+")]
    lower = 0
    for upper, label in banding:
        count = sum(1 for methods, _p, _s in rows if lower < methods <= upper)
        lines.append("| %s | %d |" % (label, count))
        lower = upper
    without = sum(1 for methods, _p, _s in rows if methods == 0)
    if without:
        lines.append("| none found | %d |" % without)
    median = rows[len(rows) // 2][0] if rows else 0
    lines += [
        "",
        "%d test methods across %d test classes; %d classes have a test of their own, median %d "
        "methods each." % (total_methods, len(methods_per_test), len(rows), median),
        "",
        "Read the total against the width table, not on its own. Where a class has a named test it "
        "is not thin, but only %d of %d classes have one: %d are covered by the registry-wide "
        "contract sweep and %d need a live EDT project, so their assurance comes from live "
        "verification rather than from this number. Comparing the total with another suite compares "
        "how much is unit-testable as much as how much is tested."
        % (len(rows), sum(len(v) for v in buckets.values()), len(buckets["tool-sweep"]),
           len(buckets["workspace-bound"])),
        "",
    ]
    thinnest = [(methods, package, simple) for methods, package, simple in rows if methods <= thin]
    lines += ["### Resting on %d test method(s) or fewer (%d)" % (thin, len(thinnest)), ""]
    if not thinnest:
        lines += ["_none_", ""]
    else:
        for methods, package, simple in thinnest:
            lines.append("- %s/%s - %d" % (package, simple, methods))
        lines.append("")
    return lines


def write_report(buckets, test_count, out_path, methods_per_test=None, thin=2):
    total = sum(len(v) for v in buckets.values())
    lines = [
        "# Test coverage census",
        "",
        "Generated by `scripts/test-coverage-audit.py`. Every compilation unit under every plugin",
        "bundle lands in exactly one bucket; the buckets add up to the total. Packages of a bundle",
        "other than the server are named with the bundle in front (`oform:ops`).",
        "",
        "| Bucket | Classes | Meaning |",
        "|---|---:|---|",
        "| direct | %d | a test class named after it |" % len(buckets["direct"]),
        "| exercised | %d | reached by some other test |" % len(buckets["exercised"]),
        "| tool-sweep | %d | declaration checked by the registry-wide contract sweep |"
        % len(buckets["tool-sweep"]),
        "| ui-bound | %d | needs a display or an extension point |" % len(buckets["ui-bound"]),
        "| workspace-bound | %d | drives a live EDT project or debug session |"
        % len(buckets["workspace-bound"]),
        "| untested | %d | plain logic nothing touches |" % len(buckets["untested"]),
        "| **total** | **%d** | across %d test classes |" % (total, test_count),
        "",
    ]
    for bucket in ("untested", "workspace-bound", "ui-bound", "tool-sweep", "exercised",
                   "direct"):
        rows = buckets[bucket]
        lines += ["## %s (%d)" % (bucket, len(rows)), ""]
        if not rows:
            lines += ["_none_", ""]
            continue
        current = None
        for package, simple, _rel in sorted(rows):
            if package != current:
                current = package
                lines.append("")
                lines.append("**%s**" % package)
            lines.append("- %s" % simple)
        lines.append("")
    if methods_per_test is not None:
        lines += depth_section(buckets, methods_per_test, thin)
    with open(out_path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines).replace("\n\n\n", "\n\n") + "\n")


def main():
    parser = argparse.ArgumentParser(description="Reconcile production classes against tests")
    parser.add_argument("--out", default=DEFAULT_OUT, help="markdown report path")
    parser.add_argument("--check", action="store_true",
                        help="exit non-zero while anything sits in the untested bucket")
    parser.add_argument("--json", action="store_true", help="print the counters as JSON")
    parser.add_argument("--thin", type=int, default=2,
                        help="name the classes resting on this many test methods or fewer")
    args = parser.parse_args()

    buckets, test_count, methods_per_test = audit()
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    write_report(buckets, test_count, args.out, methods_per_test, args.thin)

    counters = {name: len(rows) for name, rows in buckets.items()}
    counters["total"] = sum(counters.values())
    counters["production_bundles"] = len(bundles.production_bundles())
    counters["test_classes"] = test_count
    counters["test_methods"] = sum(methods_per_test.values())
    rows = depth_rows(buckets, methods_per_test)
    counters["median_methods_per_direct_class"] = rows[len(rows) // 2][0] if rows else 0
    counters["thin_direct_classes"] = sum(1 for m, _p, _s in rows if m <= args.thin)
    if args.json:
        print(json.dumps(counters, indent=2))
    else:
        for name in ("direct", "exercised", "tool-sweep", "ui-bound", "workspace-bound",
                     "untested", "total", "production_bundles", "test_classes",
                     "test_methods", "median_methods_per_direct_class",
                     "thin_direct_classes"):
            print("%-32s %d" % (name, counters[name]))
        print("\nreport written to %s" % args.out)

    if args.check and buckets["untested"]:
        print("\nuntested classes remain:")
        for package, simple, _rel in sorted(buckets["untested"]):
            print("  %s/%s" % (package, simple))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
