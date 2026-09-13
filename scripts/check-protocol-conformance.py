#!/usr/bin/env python3
"""Check this server against the official MCP conformance suite.

The other ten gates read the source. This one asks a real MCP client, written by the people who
write the specification, whether the server on the wire behaves as the specification says: the
handshake, version and capability negotiation, the session header, Accept and Content-Type, isError,
ping, the SSE streams, and the DNS-rebinding protection.

It needs a LIVE server, which is what separates it from the others: there is no way to answer the
question from the source. When nothing answers, this refuses rather than passing - a gate that goes
green because it checked nothing is worse than no gate, and this project has paid for that lesson
more than once.

    python scripts/check-protocol-conformance.py                 # localhost:12250
    python scripts/check-protocol-conformance.py --url http://127.0.0.1:<port>/mcp
    python scripts/check-protocol-conformance.py --capture       # print a fresh baseline

Needs npx on PATH. The suite is fetched on demand and not vendored.
"""
import argparse
import json
import pathlib
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

SPEC_VERSION = "2025-11-25"
DEFAULT_URL = "http://127.0.0.1:12250/mcp"
BASELINE = pathlib.Path(__file__).with_name("conformance-baseline.yml")
SUMMARY = re.compile(r"^([✓✗])\s+(\S+):\s+(\d+) passed,\s+(\d+) failed")
TOTAL = re.compile(r"^Total:\s+(\d+) passed,\s+(\d+) failed")


def serverAnswers(url):
    """Whether something is listening, asked before the suite spends a minute finding out."""
    health = url.rsplit("/mcp", 1)[0] + "/health"
    try:
        with urllib.request.urlopen(health, timeout=5) as answer:
            return json.loads(answer.read().decode("utf-8", "replace"))
    except (urllib.error.URLError, OSError, ValueError):
        return None


def run(url, baseline):
    command = ["npx", "--yes", "@modelcontextprotocol/conformance@latest", "server",
               "--url", url, "--spec-version", SPEC_VERSION]
    if baseline:
        command += ["--expected-failures", str(BASELINE)]
    finished = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                              errors="replace", shell=(sys.platform == "win32"))
    return finished.returncode, (finished.stdout or "") + (finished.stderr or "")


def scenariosIn(output):
    passed, failed = [], []
    for line in output.splitlines():
        hit = SUMMARY.match(line.strip())
        if hit:
            (failed if hit.group(1) == "✗" else passed).append(hit.group(2))
    return passed, failed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default=DEFAULT_URL, help="the server's /mcp endpoint")
    parser.add_argument("--capture", action="store_true",
                        help="print the failing scenarios instead of checking them")
    options = parser.parse_args()

    if not shutil.which("npx"):
        print("FAIL: npx is not on PATH, and the suite is fetched with it. "
              "Nothing was checked.")
        return 2

    health = serverAnswers(options.url)
    if health is None:
        print("FAIL: nothing answered at " + options.url.rsplit("/mcp", 1)[0] + "/health. "
              "This gate needs a running server; it does not pass by default.")
        return 2
    print("server: {} | EDT {} | {}".format(health.get("instance", "?"),
                                            health.get("edt_version", "?"),
                                            health.get("phase", "?")))

    code, output = run(options.url, baseline=not options.capture)
    passed, failed = scenariosIn(output)
    total = TOTAL.search(output)
    if total:
        print("scenarios: {} passed, {} failed".format(total.group(1), total.group(2)))

    if options.capture:
        print("\n# failing scenarios, for conformance-baseline.yml")
        print("server:")
        for name in failed:
            print("  - " + name)
        return 0

    if not passed and not failed:
        # The suite ran and reported nothing at all. Reading that as success would be the same
        # mistake this gate exists to prevent.
        print("FAIL: the suite reported no scenarios. Output follows.")
        print(output[-2000:])
        return 2

    if code == 0:
        print("OK: every scenario outside the baseline passed.")
        return 0

    print("FAIL: the run did not match the baseline. Either a scenario outside it failed - a real "
          "protocol defect - or one inside it started passing, and the entry should come out.")
    print(output[-4000:])
    return 1


if __name__ == "__main__":
    sys.exit(main())
