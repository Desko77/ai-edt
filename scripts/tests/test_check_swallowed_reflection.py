#!/usr/bin/env python3
"""The swallowed-reflection census is checked against samples, so it keeps catching what it was
written for.

The failure it exists for leaves no trace: a reflective probe that is refused looks exactly like a
member this release does not have, and both come through the catch as null. So the census has to
tell the two apart by what the catch DOES - which is the whole reason it reads the body rather than
the variable's name. The samples pin that: an empty catch named `ignored` and an empty catch named
`e` are the same finding, and a catch named `ignored` that reaches for a logger is not a finding at
all.

The other half is the boundary. A body that exits with a neutral answer while the method's own
contract states that answer is not a swallow - the reason is written, one level up. And a try that
never touches reflection is not weighed: the rule is about reflection, not about catches.

Run: python3 scripts/tests/test_check_swallowed_reflection.py
"""

import importlib.util
import pathlib
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

SPEC = importlib.util.spec_from_file_location(
    "check_swallowed_reflection", SCRIPTS / "check-swallowed-reflection.py")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


def verdicts(source: str):
    return [found["verdict"] for found in CHECKER.catches_in(source.splitlines())]


def source(body: str, tail: str = "") -> str:
    """A class holding one method whose body is the sample."""
    return ("class Sample\n"
            "{\n"
            "    Object run(Object obj)\n"
            "    {\n"
            "        " + body + "\n"
            "        return null;\n"
            "    }\n"
            + tail +
            "}\n")


EMPTY = (
    "try\n"
    "        {\n"
    "            obj.getClass().getMethod(\"getName\");\n"
    "        }\n"
    "        catch (Exception %s)\n"
    "        {\n"
    "        }")


class ABareSwallowIsNamed(unittest.TestCase):
    """A catch over a reflective probe that says nothing, whatever its variable is called."""

    def test_an_empty_catch_named_ignored(self):
        self.assertEqual(["bare"], verdicts(source(EMPTY % "ignored")))

    def test_an_empty_catch_named_e(self):
        # The finding this census was corrected for: the old selection read the variable name, so
        # every catch that did not call it `ignored` was outside the count.
        self.assertEqual(["bare"], verdicts(source(EMPTY % "e")))

    def test_a_body_holding_only_a_blank_line(self):
        self.assertEqual(["bare"], verdicts(source(EMPTY.replace(
            "        catch (Exception %s)\n        {\n        }",
            "        catch (Exception %s)\n        {\n\n        }") % "cause")))


class AnAccountedCatchIsLeftAlone(unittest.TestCase):
    """Each verdict is a different account of the same failure, and each is enough on its own."""

    def test_a_comment_states_the_reason(self):
        self.assertEqual(["stated"], verdicts(source(
            EMPTY.replace("catch (Exception %s)\n        {\n        }",
                          "catch (Exception %s)\n        {\n"
                          "            // the getter is optional, a missing one leaves the caller\n"
                          "            // with null - the same answer the probe gives\n"
                          "        }") % "ignored")))

    def test_the_failure_is_carried_on(self):
        self.assertEqual(["carried"], verdicts(source(
            EMPTY.replace("catch (Exception %s)\n        {\n        }",
                          "catch (Exception %s)\n        {\n"
                          "            throw new IllegalStateException(e);\n        }") % "e")))

    def test_the_body_calls_something_with_it(self):
        self.assertEqual(["acted"], verdicts(source(
            EMPTY.replace("catch (Exception %s)\n        {\n        }",
                          "catch (Exception %s)\n        {\n"
                          "            Activator.logWarning(\"getter failed: \" + e);\n        }")
            % "ignored")))

    def test_the_exception_itself_is_kept(self):
        self.assertEqual(["read"], verdicts(source(
            "Object last = null;\n        " + EMPTY.replace(
                "catch (Exception %s)\n        {\n        }",
                "catch (Exception %s)\n        {\n            last = caught;\n        }") % "caught"
            + "\n        return last;")))

    def test_the_contract_of_the_method_states_the_answer(self):
        documented = ("    /**\n"
                      "     * Reads a name through a getter.\n"
                      "     *\n"
                      "     * @return the name, or {@code null} when no such getter answers\n"
                      "     */\n"
                      "    String read(Object obj)\n"
                      "    {\n"
                      "        try\n"
                      "        {\n"
                      "            return obj.getClass().getMethod(\"getName\").invoke(obj).toString();\n"
                      "        }\n"
                      "        catch (Exception e)\n"
                      "        {\n"
                      "            return null;\n"
                      "        }\n"
                      "    }\n")
        self.assertEqual(["documented"], verdicts("class Sample\n{\n" + documented + "}\n"))

    def test_a_contract_that_says_nothing_about_the_answer_is_not_enough(self):
        silent = ("    /**\n"
                  "     * Reads a name through a getter.\n"
                  "     */\n"
                  "    String read(Object obj)\n"
                  "    {\n"
                  "        try\n"
                  "        {\n"
                  "            return obj.getClass().getMethod(\"getName\").invoke(obj).toString();\n"
                  "        }\n"
                  "        catch (Exception e)\n"
                  "        {\n"
                  "            return null;\n"
                  "        }\n"
                  "    }\n")
        self.assertEqual(["bare"], verdicts("class Sample\n{\n" + silent + "}\n"))


class OnlyReflectionIsWeighed(unittest.TestCase):
    """The rule is about reflection: a catch around an ordinary call is not this census's business."""

    def test_a_catch_over_a_plain_call(self):
        self.assertEqual([], verdicts(source(
            "try\n"
            "        {\n"
            "            list.add(obj);\n"
            "        }\n"
            "        catch (Exception ignored)\n"
            "        {\n"
            "        }")))

    def test_a_catch_whose_try_calls_a_named_method_on_a_known_type(self):
        # `helper.read(obj)` is not a reflective probe: the member is resolved at compile time, so
        # a failure here is not "this release does not offer it".
        self.assertEqual([], verdicts(source(
            "try\n"
            "        {\n"
            "            helper.read(obj);\n"
            "        }\n"
            "        catch (Exception e)\n"
            "        {\n"
            "        }")))

    def test_a_reflective_probe_in_the_try_is_weighed(self):
        self.assertEqual(["bare"], verdicts(source(
            "try\n"
            "        {\n"
            "            Class.forName(\"com._1c.g5.v8.dt.something.Optional\");\n"
            "        }\n"
            "        catch (ClassNotFoundException e)\n"
            "        {\n"
            "        }")))


if __name__ == "__main__":
    unittest.main(verbosity=2)
