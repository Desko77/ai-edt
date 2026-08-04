# Testing AI-EDT

## Architecture

The testing infrastructure consists of two layers:

### 1. Unit Tests (Tycho Surefire)

Located in `mcp/tests/ru.aiedt.mcp.server.tests/`

These are JUnit 4 tests that run inside the Eclipse/Tycho build without requiring a running EDT instance. They cover:

- **Protocol layer**: `JsonSchemaBuilder`, `JsonUtils`, `GsonProvider`, `McpConstants`
- **JSON-RPC DTOs**: `JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcError`
- **Tool results**: `ToolResult`, `ToolCallResult`, `ToolsListResult`

**Running locally:**
```bash
cd mcp
mvn clean verify
```

Unit tests run automatically during the Maven build. Results are in:
```
mcp/tests/ru.aiedt.mcp.server.tests/target/surefire-reports/
```

### 2. E2E Tests (Python HTTP client)

Located in `tests/e2e/run_e2e_tests.py`

These tests send real HTTP requests to a running MCP server and validate every tool. They require:
- A running EDT instance with the MCP plugin installed
- The `AiEdtProbe` project loaded in EDT

**Running locally:**
```bash
# Make sure EDT is running with MCP server on port 12250
python tests/e2e/run_e2e_tests.py

# Or with custom settings:
python tests/e2e/run_e2e_tests.py --host localhost --port 12250 --project AiEdtProbe

# Wait for server to start (useful for CI):
python tests/e2e/run_e2e_tests.py --wait 300

# Generate JUnit XML report:
python tests/e2e/run_e2e_tests.py --junit-xml results.xml
```

**E2E tests cover:**

| Category | Tools |
|----------|-------|
| Protocol | health, initialize, tools/list, error handling |
| Standalone | get_edt_version, list_projects, get_platform_documentation, get_check_description |
| Project | get_configuration_properties, get_metadata_objects, get_metadata_details, get_problem_summary, get_project_errors, get_tags, get_bookmarks, get_tasks |
| BSL Code | list_modules, get_module_structure, read_module_source, read_method_source, search_in_code |
| Advanced | find_references, get_applications, get_form_screenshot |

## The probe configuration

`AiEdtProbe/` is the 1C configuration the E2E suite runs against. An empty
configuration would not do: on one, every read tool answers "nothing", and the
suite cannot tell a working tool from a broken one. So each object below exists
to give some tool something to find.

It was authored end to end through this plugin's own operations rather than by
hand, which is also how it earns its keep - if the tools cannot build it, the
build fails before any test runs.

| Object | What it is there for |
|---|---|
| `CommonModule.ProbeApi` | module structure, method reading, exported vs private, two queries |
| `CommonModule.ProbeBroken` | **deliberately broken** (no `КонецПроцедуры`) - the only real compile error in the project. Do not fix it |
| `CommonModule.ProbeOrphan` | an exported method nobody calls, for dead-code search. Do not call it from anywhere |
| `Catalog.ProbeItems` | every primitive type plus an enum reference, a tabular section with a self-reference, and an item form |
| `Document.ProbeEntry` | register records, document form |
| `InformationRegister.ProbeFacts` | dimension and resource, recorder-subordinate |
| `Enum.ProbeState`, `CommonForm.ProbeForm` | an enum; a common form (a different code path from a subordinate one) |
| `Subsystem.Probe` | subsystem content and command interface |
| `Role.ProbeReader` | rights with cascaded dependencies and a row-level condition |

Identifiers are deliberately mixed latin and Cyrillic, and `ProbeApi` carries a
Russian query with no `ГДЕ` clause. Java's `\w` and `\b` are ASCII-only unless
asked otherwise, so a rule tested only on latin names silently matches nothing
on real 1C code - a fixture without Cyrillic would never catch it.

## GitHub Actions

### build.yml (automatic)
Runs unit tests on every push/PR to master. Test results are published to PR checks.

### e2e-tests.yml (manual)
Triggered via `workflow_dispatch`. Requires a running MCP server (self-hosted runner or tunnel).

### Future: Full CI Pipeline
For fully automated E2E on GitHub Actions, the plan is:
1. Build the plugin via Tycho
2. Install EDT headless (if a headless runner/Docker image becomes available)
3. Import AiEdtProbe
4. Start MCP server
5. Run E2E tests
6. Publish results

## Project Structure

```
EDT-MCP/
├── mcp/
│   ├── bundles/
│   │   └── ru.aiedt.mcp.server/        # Main plugin
│   ├── tests/
│   │   ├── pom.xml                             # Tests parent
│   │   └── ru.aiedt.mcp.server.tests/   # Unit test fragment
│   │       ├── META-INF/MANIFEST.MF
│   │       ├── pom.xml
│   │       └── src/                            # JUnit tests
│   └── pom.xml                                 # Root (includes tests module)
├── tests/
│   └── e2e/
│       └── run_e2e_tests.py                    # E2E test script
├── AiEdtProbe/                          # Test 1C configuration
│   └── src/
└── .github/workflows/
    ├── build.yml                               # CI with unit tests
    └── e2e-tests.yml                           # E2E test workflow
```
