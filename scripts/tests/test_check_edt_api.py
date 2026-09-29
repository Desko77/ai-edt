#!/usr/bin/env python3
"""The bytecode pass is checked against samples, so a javap that failed cannot pass for a clean run.

The first pass of the EDT API census reads our compiled classes with javap, and a javap that never
ran answers with an empty string - the same answer as a set of classes that refers to nothing. The
census then prints a count of zero and, having found nothing missing, goes green. That is a gate
reporting an unanswered question as an answered one, so the exit status is read and the failure
stops the run.

The samples here are the three shapes that matter: non-zero exit with a message on the error
stream, non-zero exit with nothing said, and a success whose output is parsed into the types and
members the census checks. The last one is what a failed run must not be confused with.

Run: python3 scripts/tests/test_check_edt_api.py
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

SPEC = importlib.util.spec_from_file_location(
    "check_edt_api", SCRIPTS / "check-edt-api.py")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class Finished:
    """What subprocess.run hands back, without running anything."""

    def __init__(self, returncode, stdout="", stderr=""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class OneBatch(unittest.TestCase):
    """A classes directory holding one file, and a javap answer for it."""

    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        pathlib.Path(self.folder.name, "Sample.class").write_text("", encoding="utf-8")
        self.previous_classes = CHECKER.CLASSES
        self.previous_javap = CHECKER.run_javap
        CHECKER.CLASSES = self.folder.name
        self.addCleanup(self.restore)

    def restore(self):
        CHECKER.CLASSES = self.previous_classes
        CHECKER.run_javap = self.previous_javap

    def answer_with(self, finished):
        CHECKER.run_javap = lambda arguments: finished


class AFailedJavapStopsTheRun(OneBatch):
    """Nothing about the bytecode was read, so nothing about it may be reported."""

    def test_a_nonzero_exit_is_raised(self):
        self.answer_with(Finished(1, stderr="Error: class file not found"))
        with self.assertRaises(CHECKER.DisassemblyFailed) as raised:
            CHECKER.compiled_references()
        self.assertIn("javap exited 1", str(raised.exception))
        self.assertIn("class file not found", str(raised.exception))

    def test_a_nonzero_exit_without_a_message_is_still_raised(self):
        self.answer_with(Finished(2))
        with self.assertRaises(CHECKER.DisassemblyFailed) as raised:
            CHECKER.compiled_references()
        self.assertIn("javap exited 2", str(raised.exception))

    def test_the_failure_is_not_a_clean_answer(self):
        # The defect: an empty reading and a release that still has everything we call printed the
        # same thing. This is the output half; the exit code is the one main() reads.
        self.answer_with(Finished(1, stdout=""))
        with self.assertRaises(CHECKER.DisassemblyFailed):
            CHECKER.compiled_references()

    def test_a_batch_that_fails_stops_the_whole_reading(self):
        # Boxed so len(classes) > 60: the failure is in the second batch, after the first answered.
        for index in range(70):
            pathlib.Path(self.folder.name, "Sample%d.class" % index).write_text("", encoding="utf-8")
        answers = [Finished(0, stdout=""), Finished(1, stderr="Error reading Sample70.class")]

        def one_at_a_time(_arguments):
            return answers.pop(0)

        CHECKER.run_javap = one_at_a_time
        with self.assertRaises(CHECKER.DisassemblyFailed):
            CHECKER.compiled_references()


class ASuccessfulJavapIsStillRead(OneBatch):
    """What the failure must not be mistaken for: a real answer, parsed."""

    def test_an_edt_member_called_from_the_bytecode(self):
        self.answer_with(Finished(0, stdout=(
            "public class ru.aiedt.mcp.server.Sample {\n"
            "  public void run(org.eclipse.core.resources.IProject);\n"
            "    Code:\n"
            "       0: invokeinterface #7,  2  "
            "// InterfaceMethod com/_1c/g5/v8/dt/core/platform/"
            "IConfigurationProvider.getConfiguration:"
            "(Lorg/eclipse/core/resources/IProject;)"
            "Lcom/_1c/g5/v8/dt/metadata/mdclass/Configuration;\n"
            "}\n")))
        types, members = CHECKER.compiled_references()
        self.assertIn("com._1c.g5.v8.dt.core.platform.IConfigurationProvider", types)
        self.assertIn(("com._1c.g5.v8.dt.core.platform.IConfigurationProvider", "getConfiguration"),
                      members)

    def test_a_type_used_only_in_a_signature(self):
        self.answer_with(Finished(0, stdout=(
            "public class ru.aiedt.mcp.server.Sample {\n"
            "  public void run();\n"
            "    descriptor: ()Lcom/_1c/g5/v8/dt/metadata/mdclass/Configuration;\n"
            "}\n")))
        types, _members = CHECKER.compiled_references()
        self.assertIn("com._1c.g5.v8.dt.metadata.mdclass.Configuration", types)

    def test_a_class_of_ours_that_refers_to_no_edt_type(self):
        self.answer_with(Finished(0, stdout="public class ru.aiedt.mcp.server.Sample {\n}\n"))
        types, members = CHECKER.compiled_references()
        self.assertEqual(set(), types)
        self.assertEqual(set(), members)


class TheExitCodeSaysTheRunDidNotHappen(OneBatch):
    """main() reads the failure as an error of the run, not as a finding about EDT."""

    def test_main_returns_the_error_code_when_javap_fails(self):
        def refuse():
            raise CHECKER.DisassemblyFailed("javap exited 1: Error: class file not found")

        previous = CHECKER.compiled_references
        previous_argv = sys.argv
        CHECKER.compiled_references = refuse
        sys.argv = ["check-edt-api.py", "--check", "--against", "2026.2"]
        self.addCleanup(setattr, CHECKER, "compiled_references", previous)
        self.addCleanup(setattr, sys, "argv", previous_argv)
        self.assertEqual(2, CHECKER.main())


if __name__ == "__main__":
    unittest.main(verbosity=2)
