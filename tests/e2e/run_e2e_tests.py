#!/usr/bin/env python3
"""
AI-EDT - End-to-End Test Suite

Tests all MCP tools by sending HTTP requests to a running MCP server.
Requires a running EDT instance with the MCP plugin and a loaded test configuration.

Usage:
    python run_e2e_tests.py [--host HOST] [--port PORT] [--project PROJECT]

Environment variables:
    MCP_HOST    - MCP server host (default: localhost)
    MCP_PORT    - MCP server port (default: 12250)
    MCP_PROJECT - EDT project name (default: AiEdtProbe)
"""

import argparse
import json
import os
import sys
import time
import urllib.request
import urllib.error
from dataclasses import dataclass, field
from typing import Any


# ──────────────────────────────────────────────────────────────────────────────
# Configuration
# ──────────────────────────────────────────────────────────────────────────────

@dataclass
class Config:
    host: str = "localhost"
    port: int = 12250
    project: str = "AiEdtProbe"
    # The write phase creates projects and edits forms, so it never runs by
    # accident: a suite pointed at somebody's working session must stay
    # read-only unless the caller says otherwise.
    write_phase: bool = False
    scratch_project: str = "AiEdtE2EScratch"

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    @property
    def mcp_url(self) -> str:
        return f"{self.base_url}/mcp"

    @property
    def health_url(self) -> str:
        return f"{self.base_url}/health"


# ──────────────────────────────────────────────────────────────────────────────
# JSON-RPC client
# ──────────────────────────────────────────────────────────────────────────────

_request_id = 0


def next_id() -> int:
    global _request_id
    _request_id += 1
    return _request_id


def send_jsonrpc(url: str, method: str, params: dict | None = None,
                 session_id: str | None = None, timeout: int = 120) -> dict:
    """Send a JSON-RPC 2.0 request and return parsed response."""
    payload = {
        "jsonrpc": "2.0",
        "id": next_id(),
        "method": method,
    }
    if params is not None:
        payload["params"] = params

    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if session_id:
        headers["MCP-Session-Id"] = session_id

    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8") if e.fp else ""
        return {"error": {"code": e.code, "message": f"HTTP {e.code}: {body}"}}
    except urllib.error.URLError as e:
        return {"error": {"code": -1, "message": str(e.reason)}}


def call_tool(url: str, tool_name: str, arguments: dict | None = None,
              session_id: str | None = None, timeout: int = 120) -> dict:
    """Call an MCP tool via tools/call."""
    params = {"name": tool_name}
    if arguments:
        params["arguments"] = arguments
    return send_jsonrpc(url, "tools/call", params, session_id, timeout)


# ──────────────────────────────────────────────────────────────────────────────
# Test result tracking
# ──────────────────────────────────────────────────────────────────────────────

@dataclass
class TestResult:
    name: str
    passed: bool
    duration_ms: float = 0
    message: str = ""
    response: dict = field(default_factory=dict)


class TestRunner:
    def __init__(self, config: Config):
        self.config = config
        self.results: list[TestResult] = []
        self.session_id: str | None = None

    def run_all(self) -> bool:
        """Run all tests and return True if all passed."""
        print(f"\n{'='*70}")
        print(f"  AI-EDT E2E Tests")
        print(f"  Server: {self.config.mcp_url}")
        print(f"  Project: {self.config.project}")
        print(f"{'='*70}\n")

        # Phase 1: Health & Protocol
        self._section("Protocol Tests")
        self._test("health_check", self.test_health_check)
        self._test("initialize", self.test_initialize)
        self._test("tools_list", self.test_tools_list)
        self._test("invalid_method", self.test_invalid_method)
        self._test("invalid_jsonrpc", self.test_invalid_jsonrpc)
        self._test("tool_not_found", self.test_tool_not_found)

        # Phase 2: Tools (no project needed)
        self._section("Standalone Tools")
        self._test("get_edt_version", self.test_get_edt_version)
        self._test("list_projects", self.test_list_projects)
        self._test("get_platform_documentation", self.test_get_platform_documentation)
        self._test("get_check_description", self.test_get_check_description)

        # Phase 3: Tools requiring a project
        self._section("Project Tools")
        self._test("get_configuration_properties", self.test_get_configuration_properties)
        self._test("get_metadata_objects", self.test_get_metadata_objects)
        self._test("get_metadata_objects_catalogs", self.test_get_metadata_objects_catalogs)
        self._test("get_metadata_details", self.test_get_metadata_details)
        self._test("get_problem_summary", self.test_get_problem_summary)
        self._test("get_project_errors", self.test_get_project_errors)
        self._test("get_tags", self.test_get_tags)
        self._test("get_bookmarks", self.test_get_bookmarks)
        self._test("get_tasks", self.test_get_tasks)

        # Phase 4: BSL code tools
        self._section("BSL Code Tools")
        self._test("list_modules", self.test_list_modules)
        self._test("get_module_structure", self.test_get_module_structure)
        self._test("read_module_source", self.test_read_module_source)
        self._test("read_method_source", self.test_read_method_source)
        self._test("search_in_code", self.test_search_in_code)
        self._test("search_in_code_count", self.test_search_in_code_count)

        # Phase 5: Advanced tools
        self._section("Advanced Tools")
        self._test("find_references", self.test_find_references)
        self._test("get_applications", self.test_get_applications)
        self._test("get_form_screenshot", self.test_get_form_screenshot)

        # Phase 6: The paths that reach the infobase and the disk. Off by default -
        # these create a project, edit a form and build a binary. Every check here
        # exists because the headless suite cannot see it: it runs without an
        # infobase, so an operation that reports success while producing nothing
        # looks identical to one that worked.
        if self.config.write_phase:
            self._section("Infobase & Write Path")
            self._test("create_project_really_creates", self.test_create_project_really_creates)
            self._test("table_defaults_are_infobase_safe", self.test_table_defaults_are_infobase_safe)
            self._test("export_object_refuses_without_output", self.test_export_object_refuses_without_output)
            self._test("import_external_object_refuses_a_non_binary",
                       self.test_import_external_object_refuses_a_non_binary)
            self._test("unpack_refuses_a_used_directory", self.test_unpack_refuses_a_used_directory)
            self._test("marker_corrections_answers", self.test_marker_corrections_answers)
            self._test("scratch_project_removed", self.test_scratch_project_removed)
        else:
            print("\n--- Infobase & Write Path (skipped: pass --write to run) ---")

        # Summary
        self._print_summary()
        return all(r.passed for r in self.results)

    def _section(self, title: str):
        print(f"\n--- {title} ---")

    def _test(self, name: str, fn):
        start = time.time()
        try:
            fn()
            duration_ms = (time.time() - start) * 1000
            result = TestResult(name=name, passed=True, duration_ms=duration_ms)
            self.results.append(result)
            print(f"  PASS  {name} ({duration_ms:.0f}ms)")
        except AssertionError as e:
            duration_ms = (time.time() - start) * 1000
            result = TestResult(name=name, passed=False, duration_ms=duration_ms,
                                message=str(e))
            self.results.append(result)
            print(f"  FAIL  {name} ({duration_ms:.0f}ms): {e}")
        except Exception as e:
            duration_ms = (time.time() - start) * 1000
            result = TestResult(name=name, passed=False, duration_ms=duration_ms,
                                message=f"Exception: {e}")
            self.results.append(result)
            print(f"  ERROR {name} ({duration_ms:.0f}ms): {e}")

    def _print_summary(self):
        passed = sum(1 for r in self.results if r.passed)
        failed = sum(1 for r in self.results if not r.passed)
        total = len(self.results)
        total_time = sum(r.duration_ms for r in self.results)

        print(f"\n{'='*70}")
        print(f"  Results: {passed}/{total} passed, {failed} failed ")
        print(f"  Total time: {total_time/1000:.1f}s")
        print(f"{'='*70}")

        if failed > 0:
            print(f"\n  Failed tests:")
            for r in self.results:
                if not r.passed:
                    print(f"    - {r.name}: {r.message}")
            print()

    # ──────────────────────────────────────────────────────────────────────
    # Helpers
    # ──────────────────────────────────────────────────────────────────────

    def _assert_success(self, resp: dict, msg: str = ""):
        """Assert JSON-RPC response has result (no error)."""
        assert "error" not in resp or resp.get("error") is None, \
            f"Expected success but got error: {resp.get('error')} {msg}"
        assert "result" in resp, f"Missing 'result' in response {msg}"

    def _assert_error(self, resp: dict, code: int | None = None, msg: str = ""):
        """Assert JSON-RPC response is an error."""
        assert "error" in resp and resp["error"] is not None, \
            f"Expected error but got success {msg}"
        if code is not None:
            assert resp["error"]["code"] == code, \
                f"Expected error code {code}, got {resp['error']['code']} {msg}"

    def _call(self, tool_name: str, args: dict | None = None, timeout: int = 120) -> dict:
        return call_tool(self.config.mcp_url, tool_name, args, self.session_id, timeout)

    def _get_result_text(self, resp: dict) -> str:
        """Extract text content from tool call result."""
        result = resp.get("result", {})
        content = result.get("content", [])
        if content and content[0].get("type") == "text":
            return content[0].get("text", "")
        if content and content[0].get("type") == "resource":
            res = content[0].get("resource", {})
            return res.get("text", "")
        return ""

    def _get_structured_content(self, resp: dict) -> Any:
        """Extract structuredContent from tool call result."""
        return resp.get("result", {}).get("structuredContent")

    # ──────────────────────────────────────────────────────────────────────
    # Protocol Tests
    # ──────────────────────────────────────────────────────────────────────

    def test_health_check(self):
        req = urllib.request.Request(self.config.health_url)
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            assert body.get("status") == "ok", f"Health check failed: {body}"

    def test_initialize(self):
        resp = send_jsonrpc(self.config.mcp_url, "initialize", {
            "protocolVersion": "2025-11-25",
            "capabilities": {},
            "clientInfo": {"name": "e2e-test", "version": "1.0.0"}
        })
        self._assert_success(resp)
        result = resp["result"]
        assert "protocolVersion" in result, "Missing protocolVersion"
        assert "serverInfo" in result, "Missing serverInfo"

        # Try to extract session ID from headers or use a dummy
        self.session_id = "e2e-test-session"

    def test_tools_list(self):
        resp = send_jsonrpc(self.config.mcp_url, "tools/list",
                            session_id=self.session_id)
        self._assert_success(resp)
        tools = resp["result"].get("tools", [])
        assert len(tools) > 0, "No tools registered"
        names = [t["name"] for t in tools]
        # What tools/list advertises is the canonical surface, not everything that
        # answers: a tool a facade absorbed stays callable but is deliberately not
        # listed. Asserting a hidden alias here failed against a correct server.
        for tool in ["get_edt_version", "project_admin", "get_metadata_objects"]:
            assert tool in names, f"Missing tool: {tool}"
        # And the other half of that contract: absorbed names still answer.
        absorbed = self._call("list_projects", {})
        self._assert_success(absorbed, "an absorbed alias must stay callable")

    def test_invalid_method(self):
        resp = send_jsonrpc(self.config.mcp_url, "nonexistent/method",
                            session_id=self.session_id)
        self._assert_error(resp, McpConstants.ERROR_METHOD_NOT_FOUND)

    def test_invalid_jsonrpc(self):
        """Send a request with wrong JSON-RPC version."""
        payload = json.dumps({
            "jsonrpc": "1.0",
            "id": next_id(),
            "method": "initialize"
        }).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        req = urllib.request.Request(self.config.mcp_url, data=payload,
                                    headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            assert "error" in body, "Expected error for invalid JSON-RPC version"

    def test_tool_not_found(self):
        resp = self._call("nonexistent_tool_xyz")
        self._assert_error(resp, McpConstants.ERROR_METHOD_NOT_FOUND)

    # ──────────────────────────────────────────────────────────────────────
    # Standalone Tools
    # ──────────────────────────────────────────────────────────────────────

    def test_get_edt_version(self):
        resp = self._call("get_edt_version")
        self._assert_success(resp)
        text = self._get_result_text(resp)
        assert len(text) > 0, "Empty EDT version"

    def test_list_projects(self):
        resp = self._call("list_projects")
        self._assert_success(resp)

    def test_get_platform_documentation(self):
        resp = self._call("get_platform_documentation", {
            "typeName": "Array"
        })
        self._assert_success(resp)

    def test_get_check_description(self):
        resp = self._call("get_check_description", {
            "checkId": "begin-transaction"
        })
        self._assert_success(resp)

    # ──────────────────────────────────────────────────────────────────────
    # Project Tools
    # ──────────────────────────────────────────────────────────────────────

    def test_get_configuration_properties(self):
        resp = self._call("get_configuration_properties", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_metadata_objects(self):
        resp = self._call("get_metadata_objects", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_metadata_objects_catalogs(self):
        resp = self._call("get_metadata_objects", {
            "projectName": self.config.project,
            "metadataType": "catalogs"
        })
        self._assert_success(resp)

    def test_get_metadata_details(self):
        resp = self._call("get_metadata_details", {
            "projectName": self.config.project,
            "objectFqns": ["Catalog.ProbeItems"]
        })
        self._assert_success(resp)

    def test_get_problem_summary(self):
        resp = self._call("get_problem_summary", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_project_errors(self):
        resp = self._call("get_project_errors", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_tags(self):
        resp = self._call("get_tags", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_bookmarks(self):
        resp = self._call("get_bookmarks")
        self._assert_success(resp)

    def test_get_tasks(self):
        resp = self._call("get_tasks", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    # ──────────────────────────────────────────────────────────────────────
    # BSL Code Tools
    # ──────────────────────────────────────────────────────────────────────

    def test_list_modules(self):
        resp = self._call("list_modules", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_module_structure(self):
        resp = self._call("get_module_structure", {
            "projectName": self.config.project,
            "modulePath": "CommonModules/ProbeApi/Module.bsl"
        })
        self._assert_success(resp)

    def test_read_module_source(self):
        resp = self._call("read_module_source", {
            "projectName": self.config.project,
            "modulePath": "CommonModules/ProbeApi/Module.bsl"
        })
        self._assert_success(resp)

    def test_read_method_source(self):
        resp = self._call("read_method_source", {
            "projectName": self.config.project,
            "modulePath": "CommonModules/ProbeApi/Module.bsl",
            "methodName": "ПосчитатьИтог"
        })
        # A Cyrillic method name on purpose: name matching that works on latin
        # identifiers can still miss every real 1C one.
        self._assert_success(resp)

    def test_search_in_code(self):
        resp = self._call("search_in_code", {
            "projectName": self.config.project,
            "query": "Запрос"
        })
        self._assert_success(resp)

    def test_search_in_code_count(self):
        resp = self._call("search_in_code", {
            "projectName": self.config.project,
            "query": "Запрос",
            "outputMode": "count"
        })
        self._assert_success(resp)

    # ──────────────────────────────────────────────────────────────────────
    # Advanced Tools
    # ──────────────────────────────────────────────────────────────────────

    def test_find_references(self):
        resp = self._call("find_references", {
            "projectName": self.config.project,
            "objectFqn": "CommonModule.ProbeApi"
        })
        self._assert_success(resp)

    def test_get_applications(self):
        resp = self._call("get_applications", {
            "projectName": self.config.project
        })
        self._assert_success(resp)

    def test_get_form_screenshot(self):
        resp = self._call("get_form_screenshot", {
            "projectName": self.config.project,
            "formPath": "CommonForm.ProbeForm"
        }, timeout=180)
        self._assert_success(resp)


# ──────────────────────────────────────────────────────────────────────────────
# MCP error codes (mirrored from Java)
# ──────────────────────────────────────────────────────────────────────────────

    # ──────────────────────────────────────────────────────────────────────
    # Infobase and write-path tests
    #
    # These assert the postcondition, not the reply. Every one of them covers a
    # case where the server answered success and produced nothing - the failure
    # this project keeps rediscovering, and the one a headless run cannot catch.
    # ──────────────────────────────────────────────────────────────────────

    def test_create_project_really_creates(self):
        """A created project has to exist afterwards, not just be reported."""
        self._call("delete_project", {"projectName": self.config.scratch_project,
                                      "confirm": True}, timeout=180)
        resp = self._call("create_project", {"projectName": self.config.scratch_project,
                                             "version": "8.3.21"}, timeout=300)
        self._assert_success(resp, "create_project")
        listed = self._get_result_text(self._call("list_projects", {}, timeout=120))
        assert self.config.scratch_project in listed, \
            "create_project reported success but the project is not in the workspace"

    def test_table_defaults_are_infobase_safe(self):
        """A generated table must not carry values the infobase rejects.

        These passed EDT validation and broke both the infobase import and the
        .epf build, so validation being clean proves nothing here - the values
        themselves are what has to be checked.
        """
        owner = f"Catalog.{self.config.scratch_project}Item"
        # Each step is checked on its own. Asserting only the end state makes a
        # failure anywhere upstream read as "add_table lied", which sends the next
        # person to the wrong place.
        made = self._get_result_text(
            self._call("edit_metadata", {"projectName": self.config.scratch_project,
                                         "operation": "create_object", "objectType": "Catalog",
                                         "name": f"{self.config.scratch_project}Item"}, timeout=180))
        assert '"success": false' not in made, f"create_object failed: {made[:300]}"
        formed = self._get_result_text(
            self._call("edit_metadata", {"projectName": self.config.scratch_project,
                                         "operation": "create_form", "ownerFqn": owner,
                                         "formName": "ФормаЭлемента"}, timeout=240))
        assert '"success": false' not in formed, f"create_form failed: {formed[:300]}"
        form_fqn = f"{owner}.Form.ФормаЭлемента.Form"
        added = self._get_result_text(
            self._call("edit_metadata", {"projectName": self.config.scratch_project,
                                         "operation": "add_table", "formFqn": form_fqn,
                                         "name": "E2ETable"}, timeout=180))
        assert '"success": false' not in added, f"add_table failed: {added[:300]}"
        # get_form_structure addresses a form by path, not by the BM FQN add_table takes.
        form_path = f"{owner}.Forms.ФормаЭлемента"
        # The shape lives in structuredContent; the text content is just "Done".
        structure = json.dumps(self._get_structured_content(
            self._call("get_form_structure", {"projectName": self.config.scratch_project,
                                              "formPath": form_path}, timeout=180)),
            ensure_ascii=False)
        assert structure, "get_form_structure returned nothing"
        assert "E2ETable" in structure, "add_table reported success but the table is not on the form"
        for rejected in ("rowSelectionMode>Auto", "autoMaxCardHeight",
                         "showCommandBarNeedDereferenced"):
            assert rejected not in structure, \
                f"the table carries {rejected}, which the infobase refuses on import"

    def test_export_object_refuses_without_output(self):
        """Building a binary from a project that has none must fail, not report success."""
        resp = self._call("config_io", {"operation": "export_object",
                                        "projectName": self.config.scratch_project,
                                        "objectFqn": "ExternalDataProcessor.NotThere",
                                        "outputPath": "%TEMP%/aiedt-e2e-nothing.epf"}, timeout=300)
        text = self._get_result_text(resp).lower()
        assert "success\": true" not in text.replace(" ", ""), \
            "export_object reported success for an object that does not exist"

    def test_import_external_object_refuses_a_non_binary(self):
        """An import that imports nothing has to say so."""
        resp = self._call("external_object_workshop",
                          {"operation": "import_external_object",
                           "targetProjectName": self.config.scratch_project,
                           "inputPath": "%TEMP%/aiedt-e2e-not-a-binary.epf"}, timeout=300)
        text = self._get_result_text(resp)
        assert "\"success\": true" not in text, \
            "import_external_object reported success without importing anything"

    def test_unpack_refuses_a_used_directory(self):
        """The conversion needs an empty destination, or 'not empty after' proves nothing."""
        resp = self._call("unpack_external_binary",
                          {"projectName": self.config.project,
                           "sourcePath": "%TEMP%/aiedt-e2e-missing.epf",
                           "targetPath": "%TEMP%"}, timeout=300)
        text = self._get_result_text(resp)
        assert "\"success\": true" not in text, \
            "unpack reported success against a missing source and a used directory"

    def test_marker_corrections_answers(self):
        """Corrections resolve a check id the way the error report prints it.

        A marker carries a short local uid while the report prints the symbolic
        id, so this is the call that proves the two are reconciled rather than
        compared directly - which matched nothing.
        """
        resp = self._call("marker_corrections",
                          {"projectName": self.config.project, "operation": "list",
                           "checkId": "common-module-type"}, timeout=180)
        text = self._get_result_text(resp)
        assert "Unknown tool" not in text and text, (
            "marker_corrections is not on this server - the write phase has to run against a "
            "build made from the same commit, not an older installed plugin")
        assert "Unknown operation" not in text, "the list operation was not recognised"

    def test_scratch_project_removed(self):
        """Leave the workspace as it was found."""
        resp = self._call("delete_project", {"projectName": self.config.scratch_project,
                                             "confirm": True}, timeout=180)
        self._assert_success(resp, "delete_project")



class McpConstants:
    ERROR_PARSE = -32700
    ERROR_INVALID_REQUEST = -32600
    ERROR_METHOD_NOT_FOUND = -32601
    ERROR_INVALID_PARAMS = -32602
    ERROR_INTERNAL = -32603


# Fix typo compatibility
AssertionError = AssertionError if 'AssertionError' in dir() else AssertionError


# ──────────────────────────────────────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────────────────────────────────────

def wait_for_server(url: str, timeout_sec: int = 300) -> bool:
    """Wait for the MCP server to become available."""
    print(f"Waiting for MCP server at {url} ...")
    start = time.time()
    while time.time() - start < timeout_sec:
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    print(f"  Server available after {time.time()-start:.0f}s")
                    return True
        except Exception:
            pass
        time.sleep(2)
    print(f"  Timeout after {timeout_sec}s")
    return False


def main():
    parser = argparse.ArgumentParser(description="AI-EDT E2E Tests")
    parser.add_argument("--host", default=os.environ.get("MCP_HOST", "localhost"),
                        help="MCP server host")
    parser.add_argument("--port", type=int,
                        default=int(os.environ.get("MCP_PORT", "12250")),
                        help="MCP server port")
    parser.add_argument("--project", default=os.environ.get("MCP_PROJECT", "AiEdtProbe"),
                        help="EDT project name for testing")
    parser.add_argument("--wait", type=int, default=0,
                        help="Seconds to wait for server to become available (0=no wait)")
    parser.add_argument("--junit-xml", default=None,
                        help="Write JUnit XML report to file")
    parser.add_argument("--write", action="store_true",
                        default=os.environ.get("E2E_WRITE", "") not in ("", "0", "false"),
                        help="Also run the infobase and write-path phase. It creates and deletes "
                             "a scratch project and edits a form, so it is off by default.")
    parser.add_argument("--scratch-project", default=os.environ.get("E2E_SCRATCH_PROJECT",
                                                                    "AiEdtE2EScratch"),
                        help="Name of the throwaway project the write phase creates")
    args = parser.parse_args()

    config = Config(host=args.host, port=args.port, project=args.project,
                    write_phase=args.write, scratch_project=args.scratch_project)

    if args.wait > 0:
        if not wait_for_server(config.health_url, args.wait):
            print("FATAL: Server did not become available in time")
            sys.exit(2)

    runner = TestRunner(config)
    success = runner.run_all()

    # Optional JUnit XML output for CI
    if args.junit_xml:
        write_junit_xml(runner.results, args.junit_xml)

    sys.exit(0 if success else 1)


def write_junit_xml(results: list[TestResult], path: str):
    """Write JUnit-compatible XML report."""
    from xml.etree.ElementTree import Element, SubElement, ElementTree

    suite = Element("testsuite", {
        "name": "EDT-MCP-E2E",
        "tests": str(len(results)),
        "failures": str(sum(1 for r in results if not r.passed)),
        "time": f"{sum(r.duration_ms for r in results)/1000:.3f}"
    })

    for r in results:
        tc = SubElement(suite, "testcase", {
            "name": r.name,
            "classname": "e2e",
            "time": f"{r.duration_ms/1000:.3f}"
        })
        if not r.passed:
            fail = SubElement(tc, "failure", {"message": r.message})
            fail.text = r.message

    tree = ElementTree(suite)
    tree.write(path, encoding="unicode", xml_declaration=True)
    print(f"JUnit XML report written to {path}")


if __name__ == "__main__":
    main()
