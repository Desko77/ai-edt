# CLAUDE.md

Guidance for Claude Code (claude.ai/code) and other AI agents working in this repository.
Machine-specific paths, ports and workspace names belong in a local `CLAUDE.local.md`, which is
gitignored - keep them out of this file.

## Project Overview

AI-EDT is an Eclipse RCP plugin for 1C:EDT that implements the Model Context Protocol (MCP), so AI
assistants can work against the live EDT model instead of scraping project files. Java 17, OSGi.

- **Plugin ID:** `ru.aiedt.mcp.server`
- **Version:** poms carry the next release as a snapshot - one micro above the newest tag, so a tree
  at `0.2.36-SNAPSHOT` sits on top of `v0.2.35`. The shipped version comes from the release tag
  (tycho-versions rewrites every pom during the release run), so what the tree carries never reaches
  a release; it exists so a local build outranks the release it will replace. Bump it once, right
  after a release. Between releases only the timestamp qualifier moves.
- **Target platform:** EDT 2026.1 (pulled from `edt.1c.ru` by `mcp/targets/default/default.target`)
- **License:** AGPL-3.0-or-later. See `LICENSE`, the per-file headers, and `docs/PROVENANCE.md`.
- **Origin:** the project began as a fork of `DitriXNew/EDT-MCP` and has since been fully
  de-derived; `docs/PROVENANCE.md` records the history and the retained notices.

## Critical Rules

- **ALWAYS BUILD WHEN CODE CHANGES ARE COMPLETE.** After finishing a set of Java changes, run the
  Maven/Tycho build yourself - do not ask for approval and do not defer it.
- **ALL CODE AND INTERFACE MUST BE IN ENGLISH.**
- Before committing, verify `git diff --cached` carries no private data: real project or customer
  names, absolute local paths, IP addresses, credentials, non-default dev ports.
- **A CHANGE IS NOT DELIVERED UNTIL IT IS DESCRIBED AND RELEASED.** When work lands on `main`,
  five things follow before the task is done: the tree version is bumped one micro above the
  newest tag; `README.md` and `README.en.md` say what the user can now do, both or neither;
  `CHANGELOG.md` gains a row for the release, and `docs/tools/README.ru.md` gains the arguments
  a caller has to know; the skill under `skills/ai-edt/` learns the new tool names, operations
  and arguments, and so do the copies of it that live outside this repository, which
  `CLAUDE.local.md` lists; and the announcement is written. Code that only a reader of the diff
  knows about has not been shipped, and a skill that still describes the old behaviour teaches
  the agent the new one does not exist.
- **PARITY BY FUNCTION, NOT BY NAME.** When adding a capability another plugin also has, implement
  it under our own name at every visible layer - the MCP tool name, the operation name inside a
  facade, and the Java class name. Another product's name is admissible only as a hidden
  compatibility alias, and only when a concrete client depends on it. A collision in a new public
  identifier (tool, operation, response tag, preference key) is a defect to rename, not to ship.
  Rationale: every name-level parity pass regrows structural similarity with the upstream this
  project separated from. Design new mechanisms from our own architecture.

## Build System

Maven + Tycho 4.0.5. Modules under `mcp/`:

```
mcp/
  bom/          Bill of Materials, parent POM with build plugin versions
  bundles/      main plugin bundle (ru.aiedt.mcp.server)
  tests/        JUnit fragment of the bundle
  features/     Eclipse feature for installation
  targets/      target platform definition
  repositories/ p2 update site output
```

`build.cmd [EDT_INSTALL_DIR]` autodetects EDT, injects the Directory location into the target, runs
Maven and restores the target. It needs Maven 3.9+ and JDK 17 on PATH.

### Agent build procedure

`build.cmd` is unreliable when driven from an agent shell. Do the three steps manually:

1. **Inject** a Directory location before `</locations>` in `mcp/targets/default/default.target`,
   pointing at a local 1C:EDT component directory. That directory is only a donor for `felix.scr`
   during the build; the target platform itself still resolves EDT from `edt.1c.ru`.
2. **Build**: `mvn clean verify` in `mcp/`, with JDK 17 as `JAVA_HOME`. Takes several minutes and
   runs the whole test suite in a real headless OSGi runtime. Never pass `-DskipTests`: it hides
   every test. Always confirm `BUILD SUCCESS` in the output.
3. **Restore** the target: `git checkout mcp/targets/default/default.target`.

Output: `mcp/repositories/ru.aiedt.mcp.server.repository/target/repository/` plus a `.zip` archive.

### Testing

`mcp/tests/ru.aiedt.mcp.server.tests` is a JUnit 4 fragment of the plugin bundle, executed by
tycho-surefire inside a headless OSGi runtime (`useUIHarness=false`). `mvn verify` runs it and a red
suite fails the build.

The runtime needs the SWT binary fragment named explicitly (the `swt.fragment` property plus
os-activated profiles in the tests pom). Without it `org.eclipse.ui.workbench` dies while activating
with `NoClassDefFoundError: org/eclipse/swt/SWTError`, which takes the whole launch down with
`error code 13`, because this bundle's Activator extends `AbstractUIPlugin`. On Linux the same
runtime also needs an X display, which is why CI runs Maven under `xvfb-run`. When a launch fails,
the explanation is in `mcp/tests/ru.aiedt.mcp.server.tests/target/work/configuration/*.log` - Maven
itself only prints the exit code. Test counts are most reliably read from
`mcp/tests/**/surefire-reports/*.xml`.

Supporting scripts:

- `scripts/run-unit-tests.sh` - fast local runner on a plain classpath, no OSGi. Convenient while
  iterating, but it cannot resolve platform classes and reports those tests as `missing-runtime`.
  `mvn verify` is the authority.
- `scripts/test-coverage-audit.py` - census of every production class against the suite
  (`direct` / `exercised` / `tool-sweep` / `ui-bound` / `workspace-bound` / `untested`), report in
  `docs/test-coverage.md`. `--check` fails while anything sits in `untested`, so a new class
  without a test breaks the census instead of sliding in unnoticed. CI runs it.

## Architecture

### Core server

- **Activator** (`Activator.java`) - OSGi entry point. Opens the EDT service trackers and creates
  the server singleton. Two CLI-API services are tracked by service-name string and typed `Object`
  on purpose: a typed tracker would add a compile dependency on `com._1c.g5.v8.dt.cli.api` and the
  plugin would fail to resolve on EDT installs without it. `Activator.logDebug` routes to an opt-in,
  size-capped trace file in the plugin state location, gated by a preference that defaults to off.
- **McpHttpEndpoint** (`McpHttpEndpoint.java`) - embedded HTTP server (`com.sun.net.httpserver`).
  Endpoints: `POST /mcp` (JSON-RPC), `GET /mcp` (info), `GET /health`. Its `registerTools()` builds
  the tool list at startup. Tracks the running tool call and supports operator interrupt signals.
- **McpAutoStart** (`McpAutoStart.java`) - `IStartup` hook that starts the server with EDT.
- **McpRequestRouter** (`wire/McpRequestRouter.java`) - MCP JSON-RPC 2.0 processing: `initialize`,
  `tools/list`, `tools/call`, with Streamable HTTP and SSE.

### Tool system

- **IMcpTool** (`toolkit/IMcpTool.java`) - every tool implements `getName()`, `getDescription()`,
  `getInputSchema()`, `execute(params)`, `getResponseType()` (TEXT, JSON or MARKDOWN).
- **McpToolCatalog** (`toolkit/McpToolCatalog.java`) - thread-safe registry singleton. Registration
  and enablement are separate questions: a tool is registered at startup and may still be switched
  off by preferences. Each tool owns a capability id; a duplicate capability is a programming error.
- **Tool implementations** live in `toolkit/ops/`. There are 129 callable tool names (the count
  `scripts/check-tool-catalog.py` reconciles between `ToolCategory` and the catalog). Under the
  default Canonical preset `tools/list` advertises 46 of them: the facades plus the tools no facade
  covers. The rest stay hidden but callable as backward-compatible aliases. These three numbers go
  stale on every tool added - read them off the script and a live `tools/list` rather than trusting
  what is written here.
- **Facades** - `code_search`, `launch_debugger`, `edit_metadata`, `diagnostics`, `project_admin`,
  `infobase_admin`, `config_io`, `insights`, `security_audit`, `docs_lookup`, `workspace_marks`,
  `extension_workshop`, `yaxunit_tests` and others expose many operations behind one tool name.
- **Naming convention**: MCP tool names and operation names are `snake_case`; JSON parameter keys
  stay `camelCase`; Java methods stay `camelCase`.

### Features

- **Markers** (`labels/`) - persistent metadata tagging stored in `.settings/aiedt-markers.yaml`.
  Service, dialogs, keyboard shortcuts, Navigator decorator, refactoring sync.
- **Clusters** (`folders/`) - custom folder hierarchy in the Navigator per metadata collection,
  stored in `.settings/aiedt-clusters.yaml`. Service interface with an internal implementation,
  content and label providers, Navigator filter.
- **Operator signals** (`OperatorSignal.java`, `workbench/`) - status bar contribution showing live
  tool execution, letting the user interrupt a call (cancel, retry, background, expert, custom).

### Preferences

Eclipse preference store; keys in `settings/PrefKeys.java` (default port 12250, auto-start, checks
folder, plain-text mode for client compatibility, default and maximum result limits). Tool presets
live in `settings/ToolProfile.java`, the preference pages in `settings/`.

### Bundled libraries

In `mcp/bundles/ru.aiedt.mcp.server/lib/`: `snakeyaml-2.2.jar` (YAML storage), `jsoup-1.17.2.jar`
(HTML processing), `copy-down-1.1.jar` (HTML to Markdown).

## Source Layout

All Java source sits under `mcp/bundles/ru.aiedt.mcp.server/src/ru/aiedt/mcp/server/`:

| Package | Purpose |
|---------|---------|
| `(root)` | Activator, McpHttpEndpoint, McpAutoStart, McpHistory, OperatorSignal, RunningToolCall |
| `toolkit/` | IMcpTool, McpToolCatalog |
| `toolkit/ops/` | tool implementations, including every facade |
| `toolkit/mdreport/` | metadata report formatting |
| `wire/` | MCP request routing, schema composition, JSON and result plumbing |
| `wire/jsonrpc/` | JSON-RPC request, response and handshake DTOs |
| `settings/` | preference keys, tool presets, preference pages |
| `support/` | shared helpers: project state, lifecycle, BSL modules, markdown, reflection, EDT model access |
| `labels/` | marker service, storage, model, refactoring hooks |
| `labels/ui/`, `labels/handlers/` | marker dialogs, decorator, commands |
| `folders/` | cluster service, repository, model, refactoring hooks |
| `folders/ui/`, `folders/handlers/` | cluster dialogs and commands |
| `navigation/` | Navigator tree commands |
| `workbench/` | status bar contribution and operator dialogs |
| `session/` | session change tracking |

## Adding a New MCP Tool

1. Create a class implementing `IMcpTool` in `toolkit/ops/`.
2. Implement `getName()`, `getDescription()`, `getInputSchema()`, `execute()`.
3. Override `getResponseType()` if the default MARKDOWN is wrong for it.
4. Register it in `McpHttpEndpoint.registerTools()`.
5. Add a test. `scripts/test-coverage-audit.py --check` fails on an untested production class.
6. Check the new public name against the rule in Critical Rules before merging.

## Check Descriptions

`mcp/bundles/ru.aiedt.mcp.server/checks/` holds markdown descriptions of EDT validation checks (for
example `bsl-variable-name-invalid.md`), packaged into the plugin jar through `build.properties`.
The `get_check_description` tool reads them as a bundle resource unless an external checks folder is
configured in preferences.

## Client Connection

Default server URL: `http://localhost:12250/mcp`. Client configuration examples are in
`docs/clients.md`; `.vscode/mcp.json` and `.mcp.json.example` are ready to copy.
