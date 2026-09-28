#!/usr/bin/env python3
"""Writes the file manifest of a release: name, size and SHA-256 of every distribution archive.

Two files are written into the directory holding the archives, both from one read of the bytes:

- `release-manifest.json` - an array with one object per `AI-EDT-*.zip`: `name`, `size` in
  bytes, `sha256` as 64 lowercase hex digits;
- `SHA256SUMS` - the same hashes in the `sha256sum` format, so `sha256sum -c SHA256SUMS` checks
  the downloaded archives.

Neither output file is listed in the manifest. `--check` reads the written manifest back and
fails unless every entry matches its file on disk and the entries name exactly the archives found.

Run: python3 scripts/write-release-manifest.py --dir <directory> [--check]
"""

import argparse
import hashlib
import json
import pathlib
import sys

ARCHIVE_GLOB = "AI-EDT-*.zip"
MANIFEST_NAME = "release-manifest.json"
SUMS_NAME = "SHA256SUMS"
CHUNK = 1024 * 1024


def describe(path):
    """Measures one archive.

    Parameters:
        path: the archive file.

    Returns:
        a dict with `name`, `size` and `sha256` of the file's bytes.
    """
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while True:
            block = stream.read(CHUNK)
            if not block:
                break
            digest.update(block)
            size += len(block)
    return {"name": path.name, "size": size, "sha256": digest.hexdigest()}


def archives(directory):
    """Lists the distribution archives of a directory, sorted by name.

    Parameters:
        directory: the directory the release files were collected into.

    Returns:
        the paths matching `AI-EDT-*.zip`, files only.
    """
    return sorted(p for p in directory.glob(ARCHIVE_GLOB) if p.is_file())


def write(directory):
    """Writes the manifest and the checksum file for every archive of a directory.

    Parameters:
        directory: the directory holding the archives; both files are written into it.

    Returns:
        the manifest entries written.
    """
    entries = [describe(p) for p in archives(directory)]
    (directory / MANIFEST_NAME).write_text(
        json.dumps(entries, indent=2) + "\n", encoding="utf-8", newline="\n")
    sums = "".join("{}  {}\n".format(e["sha256"], e["name"]) for e in entries)
    (directory / SUMS_NAME).write_text(sums, encoding="utf-8", newline="\n")
    return entries


def check(directory):
    """Reads the written manifest back and compares it with the files on disk.

    Parameters:
        directory: the directory holding the archives and the manifest.

    Returns:
        the list of mismatches; empty when the manifest describes exactly the archives present.
    """
    problems = []
    entries = json.loads((directory / MANIFEST_NAME).read_text(encoding="utf-8"))
    names = [e.get("name") for e in entries]
    expected = [p.name for p in archives(directory)]
    if sorted(names) != expected:
        problems.append("manifest names {} but the directory holds {}".format(names, expected))
    for excluded in (MANIFEST_NAME, SUMS_NAME):
        if excluded in names:
            problems.append("{} must not be listed in the manifest".format(excluded))
    for entry in entries:
        path = directory / str(entry.get("name"))
        if not path.is_file():
            problems.append("{} is listed but absent".format(entry.get("name")))
            continue
        actual = describe(path)
        for key in ("size", "sha256"):
            if entry.get(key) != actual[key]:
                problems.append("{}: {} is {} in the manifest, {} on disk".format(
                    path.name, key, entry.get(key), actual[key]))
        if len(str(entry.get("sha256", ""))) != 64:
            problems.append("{}: sha256 is not 64 hex digits".format(path.name))
    return problems


def main(argv=None):
    """Entry point.

    Parameters:
        argv: command-line arguments; `sys.argv[1:]` when omitted.

    Returns:
        0 on success, 1 when there is no archive or `--check` finds a mismatch.
    """
    parser = argparse.ArgumentParser(description="Write release-manifest.json and SHA256SUMS.")
    parser.add_argument("--dir", default=".", help="directory holding the AI-EDT-*.zip archives")
    parser.add_argument("--check", action="store_true",
                        help="read the manifest back and fail on any mismatch")
    args = parser.parse_args(argv)
    directory = pathlib.Path(args.dir)
    if not archives(directory):
        print("no {} in {}".format(ARCHIVE_GLOB, directory), file=sys.stderr)
        return 1
    entries = write(directory)
    for entry in entries:
        print("{}  {}  {}".format(entry["sha256"], entry["size"], entry["name"]))
    if args.check:
        problems = check(directory)
        for problem in problems:
            print("MISMATCH: " + problem, file=sys.stderr)
        if problems:
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
