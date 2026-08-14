---
name: ai-edt
description: "1C:Enterprise development through the AI-EDT MCP server - BSL analysis and editing, metadata and managed forms, query validation, project diagnostics, debugging, infobase updates and unit tests. Use when the project is a 1C:EDT workspace: BSL modules, .mdo metadata, 1C queries, managed forms. Not for 1C 7.7 sources and not for Configurator-format exports without an EDT project."
---

# AI-EDT

AI-EDT exposes a running 1C:EDT instance over MCP. Search, navigation and analysis run against
EDT's semantic model, not against file text, so references, definitions and call hierarchies are
resolved the way the IDE resolves them. Plain text and regular-expression search over the module
catalogue is available too, as one operation among others.

The tool catalogue lives in `references/`. Read the file you need for the task at hand rather
than all of them.

## When to use it

- Reading and editing BSL: modules, methods, references, call graphs, refactoring.
- Metadata: inspect, create, change, delete, rename with cascade.
- Managed forms: structure, a screenshot of the WYSIWYG editor, editing without hand-written XML.
- 1C queries: syntax and semantic validation before anything runs.
- Project problems, infobase updates, YAxUnit tests, debugging and profiling.

For BSL and 1C metadata this server beats grep-style search: it answers from the model.

## When not to use it

- **1C 7.7** sources (`.1s`, `.ert`, `1Cv7.MD`). The EDT model does not cover them.
- **Ordinary (non-managed) forms and Configurator-format exports without an EDT project.** The
  catalogue assumes managed forms and an EDT workspace.
- **Data in a running infobase, outside a suspended debug session.** Use an HTTP route into the
  running 1C application instead.
- **EDT is not running.** The tools are unavailable. Say so instead of falling back to editing
  project files by hand - a blind write into an EDT project is how work gets lost.

## Before you start

Probe with `get_edt_version`. If it does not answer, report that the server is unreachable rather
than that EDT is down: the same silence appears when the MCP server is stopped, the call queue is
stuck or the plugin is incompatible. On a connection error or timeout do not call `self_status` -
it lives on the same server and will not answer either. `self_status` is useful in the opposite
case: the server answers, but one operation fails.

Reading and analysis without the server are fine. Writing into an EDT project without it is not.

## Entry points

A facade replaces a family of related tools: one name, with the action chosen by an `operation`
parameter (`action` for the debugger). Most facades carry their own catalogue - call
`operation=help`, and `operation=help topic=<operation>` for one operation's contract.

| Facade | Use it for |
|---|---|
| `code_search` | Exploring code and model: search, references, definitions, call hierarchy, symbols. Read-only. |
| `edit_metadata` | Creating and changing metadata and forms. Bulk work through `batch=true`. |
| `diagnostics` | Project errors, summaries, revalidation, export readiness, check documentation. |
| `launch_debugger` | The whole debugger: launch and attach, breakpoints, stepping, variables, evaluation, profiling. |
| `project_admin` | Projects, configurations, subsystems, resync to disk, restarting EDT. |
| `infobase_admin` | Infobases and launching: applications, create and delete, credentials, starting a client, database update, sync control. |
| `config_io` | Import and export of the configuration and of individual artifacts, including unpacking a binary `.epf` / `.erf` into XML. |
| `insights` | Metrics, dependency graphs, configuration comparison, impact analysis. |
| `security_audit` | Role rights, RLS violations, sensitive-data scan. |
| `docs_lookup` | Platform documentation and an object's built-in help. |
| `workspace_marks` | Tags, objects by tag, bookmarks, task markers. |
| `yaxunit_tests` | YAxUnit unit tests. |

Constructors are called directly, not through a facade: `dcs_workshop` (data composition schemas),
`mxl_workshop` (spreadsheet templates), `xdto_workshop` (XDTO packages), `extension_workshop`
(extensions and borrowing), `external_object_workshop` (external data processors and reports),
`external_data_source_workshop`.

So are the everyday reading and writing tools: `write_module_source`, `read_module_source`,
`read_method_source`, `get_module_structure`, `list_modules`, `validate_query`, `ai_context`,
`diff_module`, `get_form_structure`, `get_form_screenshot`, `code_review`, `get_edt_version`.

`code_search` never writes. It only reads.

### The Canonical preset hides aliases

Many former standalone names still work as compatibility aliases, but the default **Canonical**
preset hides them from `tools/list`. Call through the facade: `diagnostics
operation=get_project_errors`, not `get_project_errors`; `launch_debugger action=launch`, not
`debug_launch`. Operation names are written in short form here for readability.

If a tool seems to be missing, do not conclude it does not exist and do not hand-roll a
replacement. Look in this order: `tools/list` for the current session, then `operation=help` on
the relevant facade, then `edit_metadata operation=help topic=availability` for what the current
EDT runtime supports.

## Rules that are not optional

1. **Write BSL through `write_module_source`**, never by editing `.bsl` files directly. A direct
   file write is invisible to EDT until a refresh, and it skips validation. Prefer the targeted
   modes (`replaceMethod`, `replaceLines`, `insertBefore` / `insertAfter`) over whole-module
   `replace`, which overwrites everything.
2. **Edit managed forms through `edit_metadata` form operations**, not by hand-writing `.form` XML.
3. **Validate every query you write or change** with `validate_query`, immediately, not in a batch
   at the end. For data composition queries pass `dcsMode=true`. Add `describeResult=true` before
   you write code against the result: it reports the columns and their types, so you stop reading
   them off the query text. Before writing the query, `insights operation=describe_db_tables` tells
   you which tables the object has and what may be selected from each.
4. **Run `validate_for_export` before writing the configuration into an infobase** and before
   building artifacts. This includes the implicit update that `yaxunit_tests` performs. Findings
   block the operation: fix them first.
5. **After edits, revalidate before reading problems.** `diagnostics operation=revalidate_objects`
   on the objects you touched, then `diagnostics operation=get_project_errors` filtered to them.
   Reading problems without revalidating reports the previous state.

## Spending context wisely

- `ai_context` with a target FQN replaces a metadata call plus a module list plus a structure call.
- `get_module_structure`, then `read_method_source` for the one method you need. This works on
  modules of tens of thousands of lines and gives exact method boundaries cheaply. Do not fall
  back to reading whole files because a module is large.
- Cache large maps (module lists, FQN catalogues, the structure of a big module) once into an
  ignored file in the project instead of asking again.
- Push heavy sweeps into a sub-agent so the raw output does not settle in the main context.

## Reference files

| You need | File |
|---|---|
| What each facade covers and which operations it exposes | `references/facades.md` |
| A ready order of calls for a concrete task | `references/workflows.md` |
| What the responses mean and how the server behaves at scale | `references/expected-behavior.md` |
