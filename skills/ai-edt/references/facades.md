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
| `insights` | `project_metrics`, `dependency_graph`, `compare_configurations`, `compare_three_way`, `detect_query_anti_patterns`, `generate_health_snapshot`, `impact_analysis`, `object_summary`, `describe_db_tables`, `semantic_metadata_search`, `help` |
| `security_audit` | `audit_role_rights`, `find_rls_violations`, `sensitive_data_scan`, `help` |
| `support_registry` | `status`, `list_objects`, `object_mode`, `snapshot_modes`, `restore_modes`, `help` |

`detect_query_anti_patterns` and `find_rls_violations` narrow to a module (`moduleFqn`) and a method
(`methodName`), `project_metrics` to a subsystem (`subsystemName`, nested ones included),
`sensitive_data_scan` to a module or a subsystem. An unknown target, or a selector that disagrees with
`scope`, is refused by name rather than scanning the whole project.

`support_registry` reads the vendor support state of a configuration: which vendor configurations it
descends from with their releases, the object count per support mode, the rules the environment
applies on the next update, and the mode of a single object together with the objects the
environment requires to change with it. A mode is a declared setting - `CHANGES_NOT_ALLOWED`,
`CHANGES_ALLOWED` or `CANCELLED` - and not a statement that the object was modified;
`compare_three_way` reports that. When the two disagree - an object modified while still set to
`CHANGES_NOT_ALLOWED` - the next vendor update overwrites that modification. The configuration root
has a mode of its own and must be set to `CHANGES_ALLOWED` before any other object can be; request
it as `objectFqn=Configuration`. Where a configuration descends from several vendors, a mode belongs
to a pair of object and vendor: `object_mode` reports per vendor, and `list_objects` requires
`parentId`.

`audit_role_rights mode=orphans` is the exception to this group being read-only: it lists rights that point at objects no longer in the configuration, and with `apply=true` removes them. It removes only what it could prove is gone - what it could not decide is listed separately and left alone - and `apply=true` is refused when the active preset forbids writing.
| `docs_lookup` | `get_platform_documentation`, `get_object_help`, `help` |
| `workspace_marks` | `get_tags`, `get_objects_by_tags`, `get_bookmarks`, `get_tasks`, `help` |
| `git` | `status`, `branches`, `log`, `commit`, `checkout` |

`compare_configurations` with `mode=projects` pairs renames before classifying: an object whose
content is equal once the name and its mirrors (the uuid, the synonym, the type ids) are set aside
reads as `renamed`, not as removed on one side and added on the other. Equality is the whole
evidence - an object that changed beyond the mirrors does not pair. Contained children are
compared by content, so a change invisible to a name-only walk (an attribute whose type moved)
shows at `level=attribute`.

`content_assist` answers a batch in one call: `positions` is a JSON array of objects, each with
its own `filePath` (falls back to the top-level one), `line` and `column`. One position's failure
answers in place and does not stop the rest - a survey over hundreds of positions is one call.
The same on `symbol_info`: its `computeTypes` resolution runs under the model watch, so a type
read off a model that was rebuilding is named rather than trusted.

`GET /health` names the open projects of the workspace (`projects`): at two stands on one machine
the port that answers is read from the projects it serves, and a `project_not_found` is never
read as the server being down when the port belongs to the other instance.

## Diagnostics

| Operation | Notes |
|---|---|
| `get_project_errors` | Problems for a project, filterable by object, severity and check. Each finding carries the line it sits on where the check reports one. |
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
  one call, not thirty-eight. The whole array is read before the first write: an unknown
  operation, an entry naming none, an argument name no schema declares, or a nested `batch` is
  refused by its number in `refusedBeforeRunning` and nothing runs, so a typo leaves the project
  untouched. Past that point validation is not interleaved between operations and the batch is
  not atomic, so read `batchResults[]` afterwards, redo the failed entries individually, then
  revalidate. Do not use a batch where each step must be checked before the next one.
- `dryRun=true` previews a change without writing it.
- `operation=help topic=parameters` gives the full rules of the parameters whose schema description
  is a single line. The catalogue is sent whole before the first call, so the prose those parameters
  used to carry lives here instead. Every parameter is still declared - nothing became
  undiscoverable, only quieter.
- `extend_object_type` adds a type to an object the extension BORROWED, marked `Extended`, leaving
  the inherited ones alone. `set_object_type` does not work there and does not say so: an adopted
  object keeps its types in the extension block, and setting a type wrote to a property it does not
  have while answering `applied:true`.

`edit_form` exposes the same form operations under a smaller surface. Prefer `edit_metadata` when
form edits are chained with other metadata edits.

## Infobase and launching

| Operation | Notes |
|---|---|
| `get_applications` | Launch configurations known to the project. |
| `create_infobase`, `delete_infobase` | Infobase lifecycle. |
| `register_infobase` | Registers an EXISTING infobase - a file `path` or a server `connectionString` (`Srvr=...;Ref=...`), exactly one of the two - in EDT's list and binds it to the project in one call. A duplicate address is answered as `reused`; a failed binding rolls the added list entry back. `accessMode` / `userName` / `password` store the infobase credentials in EDT's encrypted store in the same call - written after the list entry and before the binding (the same contract as `set_infobase_credentials`), so a base with users registers without an interactive login prompt; a failed write refuses before the binding and the answer carries the reason. The answer names what was stored (`access`, `userName`, `passwordStored`, `verifiedByReadback`); the password never appears in the answer, the call history or the journal. |
| `set_infobase_credentials` | Stored credentials for a launch configuration. Writing EDT's infobase list keeps launch configurations bound: this operation, `create_infobase`, `delete_infobase` and `register_infobase` put each configuration's application id back after the write and answer `launchApplicationIds`. A failed write is answered with both passwords masked. |
| `create_launch_config` | Binds an infobase to a project in the project's current association context - the branch, for a project under version control - and answers with `associationContext` and the `applicationId` the binding created. |
| `start_client` | Starts a 1C client from a launch configuration, without a debugger. Use it instead of building a `1cv8.exe` command line - the client then matches what the IDE is configured for. A `projectName` + `applicationId` pair with no launch configuration gets one created and saved (`autoCreatedConfiguration`). Takes `startupOption` for a `/C` string, `clientType` / `runMode` for the client and its run mode (see `launch_debugger`), and `waitForEndpoint` / `endpointTimeoutSeconds` to wait for what the client opens. `launch_debugger action=launch` if you want the debugger, `action=terminate` to stop either. |
| `update_database` | Writes the configuration into the infobase. Validate for export first. With `dryRun=true` it starts nothing and answers the update state (`updateState`), whether an update is needed (`wouldUpdate`) and, when the infobase belongs to the parent configuration, its owner (`infobaseOwner`). Readiness and the export validation an update runs first are NOT checked and the answer says so: `notCheckedInDryRun` lists them and `notCheckedInDryRunNote` says why - readiness reaches the infobase synchronization cycle through a thick client and does not return while a thick-client session holds the infobase; the export validation walks the whole project, and `diagnostics operation=validate_for_export` runs it. Such a call is answered in place: no run is recorded, no `runKey` is issued and no infobase is claimed. The objects an update would carry are not reachable that way, and `composition` says so. Before an update starts, the stored `ConfigDumpInfo.xml` format is compared with the one a rebuild recorded for this infobase, and a mismatch refuses the update naming `sync_control syncOperation=rebuild_dump_info`; `ignoreDumpInfoFormat=true` goes ahead, and `dryRun` reports it in `dumpInfoFormatCheck`. With `statusOnly=true` it reads the tracked updates instead - `updates[]` with `runKey`, state and progress per run, filtered by `projectName`; it starts nothing and does not claim a finished result, and a `runKey` of another kind of work is refused. Before the state is read it refreshes the project and, for an extension, its parent from disk (`refreshWorkspace`, default true): files written outside this server (a file tool, git checkout, a pull) are invisible to the model until then, and the answer reports `workspaceRefresh.changedResources` - 0 means the model already matched. Pass `refreshWorkspace=false` only when every change went through this server. |
| `branch_infobase` | Binds a git branch to a launch configuration, so `update_database` refuses to write into an infobase that belongs to another branch. For a project that is an extension the binding lives in the extension itself, not in the configuration it extends. |
| `sync_control` | Inspects and controls EDT-to-infobase synchronization. See the safety rule in `expected-behavior.md`. `status` lists every application of the project with whether its infobase has a baseline; `mark_synchronized` accepts a binding with no `index.idx` yet and stamps the project's configuration id on the baseline it writes (`configurationUuidStamped`, `stampError` when the stamp fails). `rebuild_dump_info` (`confirm=true`) rebuilds `ConfigDumpInfo.xml` with the infobase's own Designer dump and keeps the previous file as a copy. |

## Configuration import and export

`config_io`: `export_configuration_to_xml`, `import_configuration_from_xml`,
`import_configuration_from_binary`, `export_object` (an `.epf` or `.erf`), `export_common_picture`,
`export_configuration_to_cf`, `export_infobase_objects`, `unpack_external_binary`.

An external processor or report takes two steps, not one: `unpack_external_binary` turns a binary
`.epf` / `.erf` into Designer-XML, then `import_configuration_from_xml` turns that directory into a
project.

`export_infobase_objects` reads the objects out of the INFOBASE's own configuration, not out of the
EDT project - use it when the Configurator (or anything else outside EDT) changed the base and the
project has not seen the change. `objects` takes whole top objects, forms and common forms
(`Catalog.Банки`, `Catalog.Банки.Form.ФормаЭлемента`, `CommonForm.Имя`; Russian kind spellings
work), verified against the project's model before anything starts. `outputPath` is a directory
that must be absent or empty; the result is placed there in one rename and the answer lists the
files. A slow run answers `Pending` with a `runKey`; the Designer itself is given 600 seconds.

A whole configuration or extension delivered as one `.cf` or `.cfe` takes a single call:
`import_configuration_from_binary`. It does not touch any infobase of yours - it creates a
temporary one of its own, loads the binary there, exports XML and imports that, then removes it.
EDT cannot read binary formats at all, so this detour is the only route; the platform can read
them, but only into an infobase. On a large configuration the call takes minutes and returns a
`runKey` to collect the result with, rather than holding the connection.

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

`action=launch` can open an external data processor or report in the client it starts:
`externalObjectName` names the object, `externalObjectProject` the project that holds it when that
is not the project being launched. A name that resolves to nothing stops the launch before the
infobase is updated, and the answer says `nothingWasLaunchedOrUpdated`. There is no path to an .epf anywhere in the launch - the environment resolves the object from an external-object project only, so a ready binary goes in through `external_object_workshop operation=import_external_object` first; that route is the only one.

The environment opens such an object by BUILDING it, so the object's project must have dump
generation switched on. It is off by default on a freshly made project, and then the client starts
with nothing open. The launch refuses and names the project; `enableExternalObjectDump=true` turns
it on for that project and goes ahead.

`debugServerPort` gives this launch its own debug server port (1..65535). One machine has one
default port between all the environments running on it, and the second one to start debugging is
refused by a dialog of the environment's own - this is how to step around that. The saved
configuration is not changed.

`startupOption` is a `/C` string for the client - `autostart;anything`, an `/Execute` target, a
debugger URL - written into the working copy of the launch configuration, never the saved one.
An Attach configuration refuses it, and so does a client already running without these arguments.
`waitForEndpoint` with `endpointTimeoutSeconds` (1..50, 20 by default) turns the answer into a
readiness report: a GET on the URL, ready when the final status is below 500, at most five
redirects, one read never longer than five seconds. `endpointReady`, `endpointWaitedSeconds` and
`endpointHttpStatus` say what was seen; a URL that never answers is reported, the client is not
killed. An Attach configuration refuses `waitForEndpoint` too - it starts no client and opens no
endpoint. The same arguments are on `infobase_admin operation=start_client`.

The client follows the run mode. A configuration whose default run mode is the ordinary
application starts in the thick client: a launch configuration created by the launch is saved
with `thick`, an existing one starts this launch with the thick client while keeping its own on
disk, and `/RunModeOrdinaryApplication` is put among the infobase's additional launch parameters
on the reference EDT holds for this session - the infobase list on disk, the one the platform
launcher shares, is not written: writing it makes EDT reload the list and take the application
off every launch configuration. EDT starts the thick client with `/RunModeManagedApplication`;
the platform takes the last run-mode flag on the command line, and the infobase's parameters
come last. A managed launch takes the flag out again.
`clientType` (`thin`, `thick`, `web`) and `runMode` (`ordinary`, `managed`) name the choice
outright; `clientType=thin` on an ordinary-application configuration is refused unless
`runMode=managed` comes with it, because the thin client has no ordinary mode. The answer says
what was decided (`clientType`, `clientTypeSource`, `runMode`, `runModeSource`) and what was
changed (`runModeFlagState`: `added`, `removed`, `present`, `absent` or `not applied: ...`;
`runModeFlagScope`; `infobaseAdditionalParameters`). Both launch tools decide before updating the infobase, so a
contradiction costs no update.

A launch is reported as running only when a live debug target is observed. The refusal says what was
seen instead - the launch was not created, terminated at once, has only terminated targets, or
registered no target within ten seconds - and lists the modal dialogs that opened during the launch.

`action=wait_for_break` waits **50 seconds at most**, and 50 by default, under every name it accepts
(`timeoutSeconds`, `timeoutMs`, `waitSeconds`, `timeout`): one request does not outlive that, and a
longer ask used to come back as a transport error rather than an answer. A cut wait says so with
`timeoutCapped` and `waitedSeconds`; breakpoints stay set, so waiting longer is another call.

`action=get_variables` marks a record the variables API returned no value for with
`valueNotReturnedByVariables` and counts them: their values are read one at a time, by
`expandPath=<name>` - which falls back to evaluating the name - or by `action=evaluate`. A variable
whose value is an empty string is not marked: an empty value is an answer.

When the application cannot be resolved on its own, the refusal lists every launch it saw with its
configuration, type, application id and number of debug targets.

## When the workbench is waiting on a dialog

A modal dialog stops the workbench, and from outside it is indistinguishable from a hang: the call
that raised it never returns and nothing says why. Two places name it. `self_status` reports the
dialog that is up with its title, message and buttons; and a long operation answering
`status=Pending` carries `blockedByDialog`, `dialogs` and `blockedExplanation` when a dialog is what
holds it.

`project_admin operation=answer_dialog button=<label>` presses one of those buttons. Nothing is
pressed by itself, and a label matching no button or several is refused rather than guessed. The
infobase-update question does not have to appear at all: `launch_debugger action=launch` updates
before launching by default, and `infobase_admin operation=start_client` does so with
`updateBeforeLaunch=true`.

## Tests

`yaxunit_tests` with `mode=run` or `mode=debug`. Filters: `extensions`, `modules`, `tests`,
`suites`, `tags`, `contexts`. `updateBeforeLaunch` defaults to true, so a test run implies an
infobase update - which is why `validate_for_export` matters here too.

`vanessa` drives scenario UI tests from the outside and photographs a form of a running 1C. The scenario comes as a file (`featurePath`), as text (`scenarioText`), or is composed from `formToOpen` - and then `openStep` carries the wording, which differs for a list form, an object form and an extension's form - or from the list arguments: `listKind` with `listName` opens the list (ten metadata kinds), `tableName` names the form's table (`Список` when it is left out), `column` with `columnValue` goes to the row (`whenSeveral`: `unique` by default demands exactly one, `first` takes the first), `buttonTitle` or `buttonName` presses the form's button, `windowTitle` names the window to wait for (without it, one whose title differs from before the click) and `windowWaitSeconds` how long (default 10, and it may not exceed `timeoutSeconds`). The waiting step carries the `@screenshot` tag, so the opened window is captured; the capture keys are written into VAParams on both composing branches, and the list arguments mix with none of the other ways of naming the scenario. `screenshots=false` is refused on this call, and `vanessaParams` may not carry `ИспользоватьКомпонентуVanessaExt`, `ИспользоватьВнешнююКомпонентуДляСкриншотов` or `ТаймаутДляАсинхронныхШагов`. The answer names `sought`, and on a failed step `onScreen` - the window read out of the failing step's own text, empty where Vanessa names none. That step needs the UI-testing types, which exist only in a client started as a test manager (`testManager`); without it the step answers Тип не определен. `testClient` names the client the start step launches, `testClientPort` its port, `infobaseUser` the user to sign in as - a password is refused. On a list action and on `formToOpen` both are turned on when left out, and an explicit false of either is refused before the run starts: those scenarios open a form, so the start step activates the client this tool names (`КлиентыТестирования` with `АктивизироватьСтроку`), the one whose infobase is the project's. A client written only into `datatestclients` is not the row that step reads. The verdict is read from the `<uuid>-result.json` files Vanessa names itself.

A refusal that knows its next step carries it as `helpHint`: `{"tool": ..., "arguments": {...}}`,
ready to send, with a detail beside it when there is one. A project that is not found hints
`project_admin operation=list_projects` and names `suggestedProjectName` when an open project is
close to the name given; an owner that is not found in a metadata write hints
`insights operation=semantic_metadata_search` with the object's name as the query.

`tools/list` publishes MCP `annotations`: `readOnlyHint` and `idempotentHint` on the tools that
change nothing, `openWorldHint=false` on everything that stays on the machine. The class is read off
the Read-only preset, so it matches what that preset switches off; a facade that gates its writes by
name (`git`) reads as read-only while those writes are off. Only values that differ from the
specification's defaults are published.

## What a call left behind

`get_mcp_history` lists the recent calls, shortened to a few hundred characters each because the
buffer lives in the IDE's own heap. The full text is kept on disk beside it: pass `entryId` from a
listed record to read that one call whole - full arguments and full answer, masked the same way the
journal is. An entry the store no longer has is refused by name rather than answered with the
shortened copy, and `entryId` cannot be combined with `clear`.

What is kept on disk is set on the preference page under **Call history on disk**: whether to keep
the full text, how many days to keep it (14 by default, zero for until the size limit), and the
folder to keep it in. Nothing is written there while recording is off.

## The repository the project lives in

`git` answers what a shell would answer about the project's repository, inside the IDE, through
the JGit the environment ships: `operation=status` (work tree and index against HEAD, and how far
the branch is ahead of or behind its tracking branch), `branches` (local branches, the current one
first), `log` (recent commits, `limit` up to 100), `commit` and `checkout`. The repository is the
nearest `.git` walking up from the project's directory, so a project anywhere inside a repository
is answered about that repository.

`commit` stages only what `paths` names - comma-separated, relative to the repository root - and
refuses a call without them: there is no add-all, because a commit of everything lying in the work
tree is what a review cannot be told apart from. A path that names nothing is refused before
anything is staged. The author is the repository's `user.name` / `user.email`; a repository that
names none needs `authorName` and `authorEmail`. When the branch is bound to an infobase
(`branch_infobase`), the answer names it as `boundInfobase`.

`checkout` takes `branch` and, with `createBranch=true`, creates it from HEAD. A switch that would
overwrite uncommitted files is refused with those files listed in `conflicting` and changes nothing;
unrelated uncommitted work carries over, as git itself does it.

The two writes are switched by presets under the names `git_commit` and `git_checkout`: under
Read-only the facade still reads and both writes are refused before a file is staged. Both names
are also callable on their own as aliases of the operation.

## Tools that stand on their own

Not every tool belongs to a facade. These are called by name.

| Tool | Use it for |
|---|---|
| `get_metadata_objects` | The objects of a configuration, filtered by type and name. Omit `projectName` to search every open project at once - useful when you are looking for where an object lives. |
| `get_metadata_details` | One object in depth: attributes, tabular sections, forms, modules. |
| `get_command_interface` | The command interface of a subsystem: what it shows and in what order. |
| `generate_event_handlers` | Handler stubs for the events of an OBJECT module - catalogs, documents, registers. Not for form handlers: those come from the form operations of `edit_metadata`. Read the stub before relying on it; the parameter lists come from a table here, not from the platform. |
| `copy_object` | Copies an object into another project as its own, not adopted; forms, modules and presentation travel with it. |
| `find_dead_code` | Exported methods nobody calls. |
| `dcs_search` | Finds data composition schemas by what is inside them. |
| `code_template` | Ready code patterns instead of writing a familiar shape from memory. |
| `naparnik` | Whether 1C:Naparnik is installed, which version, and whether that version is the supported `1.0.7`. `status` only lists bundles. `probe=true` checks each link and starts the Naparnik UI bundle if it is not already running. `ask` sends one question to 1C:Naparnik 1.0.7 (`projectName`, `question` up to 20000 characters, `conversationId`, `replyTo` only together with `conversationId`, `maxToolRounds` 1..30 default 10 and always sent positive, `timeoutSeconds` 30..1800 default 300, `waitSeconds` 1..120 default 30, `runKey`, `cancel` only with `runKey`). The question, and whatever Naparnik's tools read, goes to the 1C:Naparnik service. The bridge (`mcpNaparnikBridgeEnabled`) is off by default. With it on, `ask` allows only the read tools unless `mcpNaparnikAllToolsEnabled` is on (off by default): then the request carries no tool filter, and Naparnik may change metadata, write files and execute code in EDT. Read-only, Debug & Test and Code Review disable `naparnik` in both modes. One question at a time; past `waitSeconds` the answer is Pending with a `runKey`. |
| `get_mcp_history` | What has been called on this server recently, with arguments and answers. Useful for retracing your own steps; a person reads the same buffer from the status bar. A record carries `arbitratedBy` (`tool` / `signal` - whether the operator answered the agent from the status bar instead of the tool), `deliveryStatus` (`delivered` / `failed`) and, for a signal, `signalType` and `signalNote`; `stats` carries `interrupted` and `undelivered` on top of `success` and `failure`. |
| `self_status` | Server, EDT services, queue and heap. Ask when the server answers but one operation misbehaves. |
| `marker_corrections` | Applies the fix the check that raised a finding offers, rather than inventing a repair by hand. Its own `list` and `apply` operations; it is NOT an operation of `diagnostics`. |

Three more are reached through a facade rather than by name, because the Canonical preset hides
them: `extension_workshop operation=list_interceptors`, `project_admin operation=self_upkeep` and
`project_admin operation=answer_dialog`.

## Will this delivery break the extension

Two questions, and neither answers the other.

| Ask | Operation | What it reads |
|---|---|---|
| what a new delivery breaks in an extension | `extension_workshop operation=check_release_fitness` | declarations in the model: a borrowed object gone, a borrowed field gone, a field whose type moved. Needs `baseProjectName`, the project the new delivery is loaded as |
| whether the handlers still fit their targets | `extension_workshop operation=list_interceptors` with `baseProjectName` | the target's signature from the BSL model, and whether a controlled fragment's text drifted |
| whether the platform will take it at all | `extension_workshop operation=check_platform_verdict` | the platform's own answer, in a staging infobase built for the run |

`check_release_fitness` finding nothing does NOT mean the extension applies - it reads declarations,
and a dependency written as a string in code, a name inside a query or a drifted controlled fragment
is not a declaration. Only the platform says "applies", which is what `check_platform_verdict` asks:
it takes the delivery as a `.cf` and the extension as a `.cfe` (files, not projects - exporting
either out of a project takes the configuration lock away from the open EDT session), loads them
into an infobase created for the run, and removes it afterwards.

Read its answer with care on two fields. `applies` is three-valued: `null` means the run could not
be made and the platform never saw the extension, which is not the same as a refusal. `refusedAt`
says which question failed - `applicability` is "does not fit this delivery", `load` is "the file
would not go in at all", usually the file or the platform version.

Loading is not applicability: measured, a delivery with the borrowed catalogue deleted still
answered "Загрузка конфигурации успешно завершена" to the load. The applicability check is a
separate question the platform only answers when asked.

## Constructors

| Tool | Builds |
|---|---|
| `dcs_workshop` | Data composition schemas. Validates query text and expressions before writing. `repair_schema` (through the facade: `edit_metadata operation=repair_report_schema`) puts the schema a `.dcs` holds back into a model that lost it - the template opens in EDT with the schema unavailable while the file is intact. Arguments `projectName`, `objectName`, `templateName` (by default the owner's main schema, named in the language of the configuration), `overwriteModel`, `dryRun`. The file is never written. `outcome`: `restored` (the model held none), `matched` (the model already serializes to the file, nothing changed), `refused_model_differs` (the model holds another schema - replaced only with `overwriteModel=true`, and then its serialization is written to `backupPath` beside the `.dcs`), `replaced`, `file_changed` (the file changed between the read and the attach - repeat), `no_template`, `no_file`. `success` is true only with `confirmed=true` - the model read back after the commit serializes to the file; otherwise `confirmation` says why. `dryRun=true` decides and answers without changing anything. An Object dataset requires `dataObjectName` on `add_dataset` and on `add_union_item` alike - a non-Object dataset refuses the argument. `add_total` writes `dataPath` from the expression unless `dataPath` is given. `add_appearance` builds a filter from `field` with `conditionValue` (a partial condition is refused with the argument named) and takes `appearance` as `Name=Value;Name=Value` - a JSON object or array is refused, it corrupts the file. The same arguments are on the `edit_metadata operation=add_conditional_appearance` alias. A write to report settings answers `settingsWarnings`: a filter that compares against nothing (`emptyFilterValue`) and a custom period without dates (`emptyPeriod`). |
| `mxl_workshop` | Spreadsheet templates. Coordinates are 1-based. `check_print_width` answers from the model alone whether the print area fits the sheet by width. The content span is the column set the platform's paginator prints: the document's columns and the sets the rows carry are compared, each counted to its declared size rather than to the last cell, and the widest wins; a columns print area is measured over begin..end with the columns of row 0, not the set stored on the area; a rectangular print area is measured over x..x+width-1, the span the fit-to-width scale uses, and when the platform paginates that rectangle it takes one column more. Column widths follow the platform's inheritance: the column's own format, the format of the set of columns, the document's default format, then the platform's 72 eighths of a character. One character's width is measured in the template font (Arial 8), and `charWidthSource` names where the number came from, because a template near the edge flips its answer with the font the machine has. Page settings the model never set are taken as the platform takes them (A4, portrait, 10 mm margins, 100% scale) and are listed in `assumed`; a paper declared by its own dimensions (code -1 with pageWidth and pageHeight, millimetres) is measured by those, and any other code but A4 is measured as A4 and `assumed` says so. `verdict`: `fits`; `borderline` - over the printable width by no more than 5%, which another font moves back to `fits`; `overflows`; `smallPrint` - with `fitToPage`, the scale the content needs is below `smallScalePercent` (10..100, default 75); `empty` - no cell anywhere in the template. The answer carries `contentWidthMm`, `contentWidthCharUnits`, `printableWidthMm`, `marginMm` and `overflowMm` (one figure and its negation), `orientation`, `paper`, `printScalePercent`, and with `fitToPage` also `requiredScalePercent` and `fontSizeAfterScale`. A print scale stored in the model is reported as `printScalePercent` and is not folded into the width. Nothing is written. |
| `xdto_workshop` | XDTO package schemas. Create the package with `edit_metadata` first. |
| `extension_workshop` | Extension projects, borrowing objects and members, deployment, comparison, and what a new delivery does to an extension. Borrowing writes the link that makes the extension actually extend, and a borrow that cannot write it fails rather than reporting success; calling borrow again repairs an object left unlinked by an older build. `borrow_module` needs `moduleType` wherever an object has more than one module. `borrow_child` composes the child's FQN from `objectFqn` plus `childKind` and `name` - before that it borrowed the OWNER and answered "borrowed". `borrow_form_item` borrows the FORM that carries the item: a form item has no address of its own, so name the form with `formName` beside the owner or spell it in `objectFqn` (`Catalog.X.Form.ФормаЭлемента`); without a form named the call is refused and nothing is borrowed. |
| `external_object_workshop` | External data processor and report projects, which are standalone DT projects rather than configuration objects. `import_external_object` adds an `.epf` / `.erf` to an existing container: `targetProjectName`, `inputPath`, `baseProjectName` (the configuration; the container's parent by default), `applicationId` (when the configuration has several applications - see `get_applications`). The binary is converted through the Designer of that application's infobase, the way `unpack_external_binary` does it: EDT releases the infobase and takes it back; a release that fails refuses the import (`infobaseNotReleased`), a reconnection that fails is named in `reconnectError` whatever the import's own outcome. The answer carries `hostProject` and `infobaseName`. |
| `external_data_source_workshop` | Tables, fields and functions of an external data source. |

**An open editor outranks the file.** `read_module_source` returns the editor's unsaved text and marks it in the answer; `write_module_source` refuses while such an editor holds the file. What is read and what is written then describe one state rather than two.
