#!/usr/bin/env python3
"""The release manifest describes exactly the archives it sits beside, and the release job
attests those archives and publishes the manifest.

Run: python3 scripts/tests/test_write_release_manifest.py
"""

import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location(
    "write_release_manifest", SCRIPTS / "write-release-manifest.py")
MANIFEST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MANIFEST)

RELEASE_WORKFLOW = SCRIPTS.parent / ".github" / "workflows" / "release.yml"


class TheManifestDescribesTheArchives(unittest.TestCase):
    """What the script writes and what --check accepts."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)
        self.versioned = self.dir / "AI-EDT-0.2.56.zip"
        self.fixed = self.dir / "AI-EDT-update-site.zip"
        self.versioned.write_bytes(b"versioned archive bytes")
        self.fixed.write_bytes(b"fixed-name archive bytes, a little longer")
        (self.dir / "notes.txt").write_text("not a release archive", encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def test_each_archive_has_its_size_and_hash(self):
        self.assertEqual(0, MANIFEST.main(["--dir", str(self.dir), "--check"]))
        entries = json.loads((self.dir / "release-manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(["AI-EDT-0.2.56.zip", "AI-EDT-update-site.zip"],
                         [e["name"] for e in entries])
        for entry in entries:
            data = (self.dir / entry["name"]).read_bytes()
            self.assertEqual(len(data), entry["size"])
            self.assertEqual(hashlib.sha256(data).hexdigest(), entry["sha256"])
            self.assertRegex(entry["sha256"], r"^[0-9a-f]{64}$")

    def test_the_checksum_file_uses_the_sha256sum_format(self):
        MANIFEST.main(["--dir", str(self.dir)])
        lines = (self.dir / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
        expected = ["{}  {}".format(hashlib.sha256(p.read_bytes()).hexdigest(), p.name)
                    for p in (self.versioned, self.fixed)]
        self.assertEqual(expected, lines)

    def test_a_second_run_does_not_list_its_own_outputs(self):
        MANIFEST.main(["--dir", str(self.dir)])
        self.assertEqual(0, MANIFEST.main(["--dir", str(self.dir), "--check"]))
        names = [e["name"] for e in
                 json.loads((self.dir / "release-manifest.json").read_text(encoding="utf-8"))]
        self.assertNotIn("release-manifest.json", names)
        self.assertNotIn("SHA256SUMS", names)
        self.assertNotIn("notes.txt", names)

    def test_a_manifest_that_disagrees_with_the_bytes_is_reported(self):
        MANIFEST.main(["--dir", str(self.dir)])
        path = self.dir / "release-manifest.json"
        entries = json.loads(path.read_text(encoding="utf-8"))
        entries[0]["size"] += 1
        entries[1]["sha256"] = "0" * 64
        path.write_text(json.dumps(entries), encoding="utf-8")
        problems = MANIFEST.check(self.dir)
        self.assertEqual(2, len(problems), problems)

    def test_a_directory_without_archives_fails(self):
        empty = self.dir / "empty"
        empty.mkdir()
        self.assertEqual(1, MANIFEST.main(["--dir", str(empty), "--check"]))


class TheReleaseJobAttestsAndPublishes(unittest.TestCase):
    """What the release workflow must carry, read from the file without a run."""

    @classmethod
    def setUpClass(cls):
        cls.text = RELEASE_WORKFLOW.read_text(encoding="utf-8")

    def test_the_job_may_sign_attestations(self):
        self.assertIn("id-token: write", self.text)
        self.assertIn("attestations: write", self.text)

    def test_the_archives_are_attested(self):
        self.assertIn("actions/attest-build-provenance@", self.text)
        self.assertIn("subject-path: 'AI-EDT-*.zip'", self.text)

    def test_the_manifest_is_written_and_published(self):
        self.assertIn("scripts/write-release-manifest.py", self.text)
        self.assertIn("release-manifest.json", self.text)
        self.assertIn("SHA256SUMS", self.text)


if __name__ == "__main__":
    unittest.main()
