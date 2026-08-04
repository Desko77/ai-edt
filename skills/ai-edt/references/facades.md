# Facades and their operations

Each facade takes an `operation` parameter (`action` for the debugger, `mode` for the test
runner). Operation names accept both `snake_case` and `camelCase`.

This file is a map, not a contract. The authoritative catalogue is the server itself: call
`operation=help` on a facade for its operations, and `operation=help topic=<operation>` for the
parameters of one. When this file and the server disagree, the server is right.

## Read-only

| Facade | Operations |
|---|---|
| `code_search` | `text_search`, `object_references`, `method_references`, `resolve_symbol`, `call_hierarchy`, `symbol_info`, `content_assist`, `outgoing_structures`, `help` |
| `insights` | `project_metrics`, `dependency_graph`, `compare_configurations`, `detect_query_anti_patterns`, `generate_health_snapshot`, `impact_analysis`, `object_summary`, `semantic_metadata_search`, `help` |
| `security_audit` | `audit_role_rights`, `find_rls_violations`, `sensitive_data_scan`, `help` |
| `docs_lookup` | `get_platform_documentation`, `get_object_help`, `help` |
| `workspace_marks` | `get_tags`, `get_objects_by_tags`, `get_bookmarks`, `get_tasks`, `help` |

## Diagnostics

| Operation | Notes |
|---|---|
| `get_project_errors` | Problems for a project, filterable by object, severity and check. |
| `get_problem_summary` | Aggregated counts instead of a full listing. |
| `revalidate_objects` | Recomputes problems for the given objects. Run this after edits, before reading errors. |
| `clean_project` | Full rebuild of derived state. The remedy when validation results look stale rather than wrong. |
| `validate_for_export` | Pre-flight for writing into an infobase or building artifacts. Findings block the operation. |
| `get_check_description` | What a specific validation check means. |

## Changing the model

`edit_metadata` is the single constructor for metadata, forms, command interface, services,
templates, extensions and data composition schemas. It carries far more operations than are worth
listing here - call `operation=help` for the catalogue by group, and
`operation=help topic=availability` for what the current EDT runtime supports.

Two things worth knowing before the first call:

- `batch=true` with an `operations` array does many creations in one call. Thirty-eight roles are
  one call, not thirty-eight. Validation is not interleaved between operations and the batch is
  not atomic, so read `batchResults[]` afterwards, redo the failed entries individually, then
  revalidate. Do not use a batch where each step must be checked before the next one.
- `dryRun=true` previews a change without writing it.

`edit_form` exposes the same form operations under a smaller surface. Prefer `edit_metadata` when
form edits are chained with other metadata edits.

## Infobase and launching

| Operation | Notes |
|---|---|
| `get_applications` | Launch configurations known to the project. |
| `create_infobase`, `delete_infobase` | Infobase lifecycle. |
| `set_infobase_credentials` | Stored credentials for a launch configuration. |
| `create_launch_config` | A new launch configuration. |
| `update_database` | Writes the configuration into the infobase. Validate for export first. |
| `sync_control` | Inspects and controls EDT-to-infobase synchronization. See the safety rule in `expected-behavior.md`. |

## Configuration import and export

`config_io`: `export_configuration_to_xml`, `import_configuration_from_xml`, `export_object`
(an `.epf` or `.erf`), `export_common_picture`, `export_configuration_to_cf`.

Operations that drive the thick client run their own designer process. Do not start a batch
Configurator against an infobase that EDT manages - the two compete for the same lock and the
manual run hangs. Go through these operations instead.

## Debugging

`launch_debugger` takes `action`, not `operation`: `launch`, `add_breakpoint`, `remove_breakpoint`,
`list_breakpoints`, `set_exception_breakpoint`, `run_to_line`, `wait_for_break`, `get_state`,
`debug_status`, stepping, variable inspection, `evaluate`, profiling, `terminate`.

Names differ from the former standalone tools: `set_breakpoint` is `action=add_breakpoint`,
`evaluate_expression` is `action=evaluate`, `terminate_launch` is `action=terminate`. Call
`action=help` for the current list.

## Tests

`yaxunit_tests` with `mode=run` or `mode=debug`. Filters: `extensions`, `modules`, `tests`,
`suites`, `tags`, `contexts`. `updateBeforeLaunch` defaults to true, so a test run implies an
infobase update - which is why `validate_for_export` matters here too.

`vanessa` drives scenario UI tests from the outside and complements the unit tests.

## Constructors

| Tool | Builds |
|---|---|
| `dcs_workshop` | Data composition schemas. Validates query text and expressions before writing. |
| `mxl_workshop` | Spreadsheet templates. Coordinates are 1-based. |
| `xdto_workshop` | XDTO package schemas. Create the package with `edit_metadata` first. |
| `extension_workshop` | Extension projects, borrowing objects and members, deployment, comparison. |
| `external_object_workshop` | External data processor and report projects, which are standalone DT projects rather than configuration objects. |
| `external_data_source_workshop` | Tables, fields and functions of an external data source. |
