"""Regression tests for execution-order checks in the facade census."""
import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "check-facade-gates.py"
SPEC = importlib.util.spec_from_file_location("check_facade_gates", SCRIPT)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class GateOrderTests(unittest.TestCase):
    def test_deferred_anonymous_callback_before_entry_is_not_a_pre_step(self):
        source = '''class Example {
            static final Port PORT = new Port() {
                public void create() { Helper.createProject(); }
            };
            public String execute(Map<String, String> params) {
                gateIfPresetDisabled("write");
                return PORT.createProject();
            }
        }'''
        self.assertIsNone(CHECK.gate_comes_first(source))

    def test_invoked_preparation_inside_entry_still_fails(self):
        source = '''class Example {
            public String execute(Map<String, String> params) {
                Helper.createProject();
                gateIfPresetDisabled("write");
                return "done";
            }
        }'''
        self.assertIn("runs a pre-step", CHECK.gate_comes_first(source))

    def test_helper_gate_before_entry_does_not_hide_late_entry_gate(self):
        source = '''class Example {
            private void helper() { gateIfPresetDisabled("write"); }
            public String execute(Map<String, String> params) {
                Helper.installEngine();
                gateIfPresetDisabled("write");
            }
        }'''
        self.assertIn("runs a pre-step", CHECK.gate_comes_first(source))

    def test_braces_in_literals_and_comments_do_not_end_entry(self):
        source = '''class Example {
            public String execute(Map<String, String> params)
                throws Exception {
                String text = "} escaped \\\" {";
                char brace = '}'; // }
                /* } */ Helper.updateDatabase();
                gateIfPresetDisabled("write");
            }
        }'''
        self.assertIn("runs a pre-step", CHECK.gate_comes_first(source))

    def test_delegation_before_gate_inside_entry_still_fails(self):
        source = '''public String execute(Map<String, String> params) {
            new ExampleTool().execute(params);
            gateIfPresetDisabled("write");
        }'''
        self.assertIn("hands work to a tool", CHECK.gate_comes_first(source))

    def test_standalone_snippet_without_entry_keeps_order_check(self):
        self.assertIn("runs a pre-step", CHECK.gate_comes_first(
            'ensureEngine(); gateIfPresetDisabled("write");'))

    def test_standalone_gated_snippet_passes(self):
        self.assertIsNone(CHECK.gate_comes_first(
            'gateIfPresetDisabled("write"); ensureEngine();'))


if __name__ == "__main__":
    unittest.main()
