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

`restore_modes` finds an object by `bmGetId` / `getObjectById` and puts the mode back on its
attributes, tabular sections, tabular-section attributes, forms, templates, commands and nested
subsystems. A record it could not address, or could not read back inside the transaction, is named
in `refused` (at most 500 names); `refusedCount` is the full number of refusals and
`refusedTruncated` says `refused` carries fewer than that. `list_objects` answers `indexComplete`.
With `apply=true`, a run that found a drift and restored nothing ends as a warning rather than a
success: read the warnings before reporting that the modes are back. Writing into an object the
support entry closes is refused under `supportLock` - see `expected-behavior.md`.

`audit_role_rights mode=orphans` is the exception to this group being read-only: it lists rights that point at objects no longer in the configuration, and with `apply=true` removes them. It removes only what it could prove is gone - what it could not decide is listed separately and left alone - and `apply=true` is refused when the active preset forbids writing.

Writing rights reads `Rights.rights` with DOCTYPE and external entities turned off, and refuses
before it writes on an unknown role, a missing object, a role name carrying a dot, a right that does
not apply to the object's kind, an unknown collection kind or a configuration it cannot read. A new
block is written by the role's name, the ENGLISH address of the object (`Catalog.Товары`,
`Configuration.<Name>`), and the spelling the model uses for the right; an existing block written in
Russian is updated. The configuration root is an address of one segment (`Configuration`, or the
configuration's own name) or `Configuration.<Name>`, and a block named `<name>Configuration</name>`
is renamed to `Configuration.<Name>`. The orphan sweep of `audit_role_rights` walks the same way and
leaves a record it cannot resolve. Read and write errors of `Rights.rights` name no absolute path.

`audit_role_rights` takes `objectType` by kind in any case, language and number, with `Register`
standing for every kind of register; an unknown value is refused with the list. `mode=impact`
refuses a name that is not a role and names it. `format=markdown` outside `mode=rights` answers JSON
with a `formatNote` instead of the markdown asked for. In `find_rls_violations` an unknown `roleName`
is refused with the list of roles, and a role whose `Rights.rights` could not be read answers
`rlsNotDetermined` with the reason and `rightsNotRead`.

In `sensitive_data_scan` an unknown name in `checks` is refused with the list, `all` turns every
check on, and names are matched without regard to case. `customPatterns` is compiled with
`UNICODE_CASE` and `UNICODE_CHARACTER_CLASS`, a JSON array is read as a list, a comma inside `{n,m}`
does not split the pattern, and a pattern that will not parse is refused with its own text and the
message. The name dictionary matches whole identifier words, so `Setting` is not a match for `tin`.
The answer carries `statistics.omittedBelowSeverityFilter` for what the severity filter dropped.
| `docs_lookup` | `get_platform_documentation`, `get_object_help`, `help` |
| `workspace_marks` | `get_tags`, `get_objects_by_tags`, `get_bookmarks`, `get_tasks`, `help` |
| `git` | `status`, `branches`, `log`, `commit`, `checkout`, `show_file_changes`, `revert_file`, `create_merge_restore_point`, `restore_merge_point` |
| `cluster_admin` | `get_clusters`, `create_cluster`, `update_cluster`, `delete_cluster`, `add_to_cluster`, `remove_from_cluster`, `help` |
| `tag_admin` | `create_tag`, `update_tag`, `delete_tag`, `assign_tag`, `unassign_tag`, `help` |

## Clusters

`cluster_admin` edits the custom folder hierarchy of the Navigator: a cluster is a folder hanging
under a metadata collection (`Catalog`, `CommonModule`), the objects it holds are stored per project
in `.settings/aiedt-clusters.yaml` and hidden from their normal place in the tree.

`get_clusters` answers the tree - `fullPath`, `name`, `description`, `order`, the held `objects` and
the nested `children` - for one `collectionPath` or for every collection that has clusters. A full
path joins the collection and the names with slashes: `Catalog/Shelf/Sub`.

The five writes each pass their own door, so a write-blocking preset refuses them before the file is
read: `create_cluster` (`name` plus `collectionPath`, or `parentClusterPath` to nest),
`update_cluster` (`newName` required, `description` optional; the same state again answers
`noChange: true`), `delete_cluster` (removes the nested clusters too; `dryRun=true` answers
`nestedClusters` and `objects` and writes nothing), `add_to_cluster` (moves the object out of its
current cluster and answers `movedFrom`), `remove_from_cluster` (out of every cluster at once,
answer `removedFrom`).

`add_to_cluster` checks the object against the EDT model first: an object that is not in the
configuration is refused with `objectNotFound` and the nearest names, and an object of another
collection than the cluster (`Document.X` into a `Catalog` cluster) with `outsideCollection`. An
object that is in no cluster, asked off clusters, is refused with `notClustered`. A refused write
answers with the cluster outcome's own code - `clusterExists`, `nameTaken`, `changedOnDisk`,
`unreadableFile` - beside a sentence for a person. The cluster service is not an OSGi service and
can be absent before the plugin starts; every operation answers `serviceUnavailable` then.

`compare_configurations` with `mode=projects` pairs renames before classifying: an object whose
content is equal once the name and its mirrors (the uuid, the synonym, the type ids) are set aside
reads as `renamed`, not as removed on one side and added on the other. Equality is the whole
evidence - an object that changed beyond the mirrors does not pair. Contained children are
compared by content, so a change invisible to a name-only walk (an attribute whose type moved)
shows at `level=attribute`.

`compare_configurations mode=files` compares two dumps on disk: `projectName` is the path of the first
dump and `target` the path of the second, each a file or the directory of a dump. The answer carries `failed` and
`failedCount` - files that could not be read, directories whose listing failed, and symbolic links
the walk does not follow. Read them: `success:true` beside a `failedCount` above zero is an
INCOMPLETE diff, not a clean one, and an unread module or a template that would not open lands in
`failed` rather than being passed over. A multi-valued reference is compared by its element types,
so `CatalogRef.A` becoming `CatalogRef.B` shows at `attribute` level, and `level=attribute` requires
`mode=projects`.

`compare_three_way` with decisions refuses the merge as a whole (`decisionsRefused`) when a decision
names an object the comparison does not hold, an unknown rule or `CUSTOM_MERGE`; the decisions that
are left are marked. An unread tree or a missing root is refused before the merge. After a merge the objects the decisions named and this page of the comparison are revalidated;
when changes remain past that page (`moreChanged`), the answer says so in `revalidationNote`. A node the walk
could not classify (`UNKNOWN`) is held by `UPDATE_KEEPING_OURS`, a module root that has methods is
not merged whole, `sectionsOffset` pages the sections, and a module of an object or a manager is
named a module. See `expected-behavior.md` for the restore point taken before a merge.

`content_assist` answers a batch in one call: `positions` is a JSON array of objects, each with
its own `filePath` (falls back to the top-level one), `line` and `column`. One position's failure
answers in place and does not stop the rest - a survey over hundreds of positions is one call.
The same on `symbol_info`: its `computeTypes` resolution runs under the model watch, so a type
read off a model that was rebuilding is named rather than trusted.

`object_references` counts references phase by phase, up to ten times `limit` per phase, and prints
at most `limit` rows per category; the code section comes before the metadata one and the remainder
is named as "... and N more". A cut answer names the neighbouring projects it did not open. A `limit`
of zero or less is refused before the search. A failure in the BSL phase answers with what the other
phases found, and a failure of the index while callers are collected shows in the direct and the
transitive call graph.

`docs_lookup get_platform_documentation` resolves a name through the registry of type sets: a set
(`СправочникСсылка`, `ДокументСсылка` and the like) answers with the types it covers, and a request
for one member of a set is refused with that reason. Without `projectName` it reads the newest
platform version of the workspace, and every answer names the version it read. A `limit` of zero or
less is refused.

`GET /health` names the open projects of the workspace (`projects`): at two stands on one machine
the port that answers is read from the projects it serves, and a `project_not_found` is never
read as the server being down when the port belongs to the other instance.

## Tags

`tag_admin` writes the project's metadata tags: a tag is a named, colored label attached to
metadata objects, stored per project in `.settings/aiedt-markers.yaml`. Reading them - the list of
tags, the objects carrying one - stays in `workspace_marks` (`get_tags`, `get_objects_by_tags`).
Both reads answer a JSON error document - `isError` on the wire - when that file does not parse or
its bytes cannot be read at all, so that neither reads as a project defining no tags. An absent file
is not a refusal, and the answer is that the project defines no tags.

The five writes each pass their own door, so a write-blocking preset refuses them before the file is
read: `create_tag` (`tag`, an optional `color` as `#RRGGBB` and a `description`; without a color the
default gray), `update_tag` (`newName`, `color`, `description`; an omitted argument keeps the field,
an empty `description` clears it; a rename carries the assignments and the answer names
`movedAssignments`), `delete_tag` (takes the tag off every object, the answer names `assignments`;
`dryRun=true` answers the count and writes nothing), `assign_tag` (`objectFqn` plus a `tags` array;
the list writes as one, so one unknown tag refuses the whole call and nothing is assigned; a blank
or whitespace element refuses the whole call with `invalidName`), `unassign_tag` (a `tags` array; a
tag the object does not carry is `skipped`, and nothing removed answers `isError` with
`reason=notAssigned`). The `assign_tag` and `unassign_tag` answers name what the operation itself
changed under its write lock: a tag a parallel call assigned or removed first reads as `skipped`,
not as `assigned` or `removed`.

`assign_tag` checks the object against the EDT model first: an object that is not in the
configuration is refused with `objectNotFound` and the nearest names. Nested addresses resolve
too, in kind-name pairs the way the marker file spells them: `Catalog.Products.Attribute.Code`,
`Catalog.Products.Form.ItemForm`. Names that differ only in case are different tags; a name empty
after trim is refused with `invalidName`, a color that is not `#RRGGBB` with `invalidColor`. The
file refusals every write can answer are `unreadableFile`, `readOnlyFile`, `changedOnDisk` (the
file changed after it was read and was left as the changing party wrote it) and `saveFailed` -
which also answers when the file's bytes cannot be read at all: refused access, a drive that went
away.

## Diagnostics

| Operation | Notes |
|---|---|
| `get_project_errors` | Problems for a project, filterable by object, severity and check. Each finding carries the line it sits on where the check reports one. `objects` is a JSON array, matched by whole FQN segments; anything else is refused rather than read as one name. |
| `get_problem_summary` | Aggregated counts instead of a full listing. |
| `revalidate_objects` | Recomputes problems for the given objects. Run this after edits, before reading errors. |
| `clean_project` | Full rebuild of derived state. The remedy when validation results look stale rather than wrong. |
| `validate_for_export` | Pre-flight for writing into an infobase or building artifacts. Findings block the operation. A `Template.mxlx` picture reference `ref="v8ui:/"` is finding `mxlx-empty-picture-ref`: one finding names the file and the count. The platform refuses to load a picture reference with an empty name. |
| `get_check_description` | What a specific validation check means. |

## Changing the model

`edit_metadata` is the single constructor for metadata, forms, command interface, services,
templates, extensions and data composition schemas. It carries far more operations than are worth
listing here - call `operation=help` for the catalogue by group, and
`operation=help topic=availability` for what the current EDT runtime supports.

Writing operations are refused by Read-only, Debug & Test and Code Review through the
`edit_metadata_writes` door, before the project is read; a batch carrying a writing operation is
not started at all and names the blocked entries in `presetBlocked`. The reading operations
(`help`, `get_template_content`, `get_route_map`, `list_pictures`, `list_form_appearance_rules`)
and `dryRun` previews keep working. `sync_export`, `remove_object`, `delete_metadata_object` and
`rename_metadata_object` have no preview and are refused with `dryRun=true` as well. The refusal names
the facade and the preset.

Two things worth knowing before the first call:

- `batch=true` with an `operations` array does many creations in one call. Thirty-eight roles are
  one call, not thirty-eight. The whole array is read before the first write: an unknown
  operation, an entry naming none, an argument name no schema declares, or a nested `batch` is
  refused by its number in `refusedBeforeRunning` and nothing runs, so a typo leaves the project
  untouched. Past that point validation is not interleaved between operations and the batch is
  not atomic, so read `batchResults[]` afterwards, redo the failed entries individually, then
  revalidate. Do not use a batch where each step must be checked before the next one. An entry is
  written in the line form as the operation name and `name=value` pairs; a token that is not
  `name=value` is refused, so a value with spaces needs the JSON form. A multi-language synonym
  written as a JSON object is applied or refused whole. An entry whose failure follows from an
  earlier operation that created nothing (a preview, or an error) carries `derivedFailure` and the
  index in `causedByOperation` - it is not a defect of its own. A batch that stopped early says so
  in `stoppedOnError`.
- `dryRun=true` previews a change without writing it. Each previewed batch entry runs in its own
  transaction, so an entry that needs an object an earlier entry would have created fails.
- `operation=help topic=parameters` gives the full rules of the parameters whose schema description
  is a single line. The catalogue is sent whole before the first call, so the prose those parameters
  used to carry lives here instead. Every parameter is still declared - nothing became
  undiscoverable, only quieter.
- `extend_object_type` adds a type to an object the extension BORROWED, marked `Extended`, leaving
  the inherited ones alone. `set_object_type` does not work there and does not say so: an adopted
  object keeps its types in the extension block, and setting a type wrote to a property it does not
  have while answering `applied:true`.

### Form operations

- `create_form` builds a managed form with EDT's own form generator - the engine the New Form wizard
  drives - from the owner and the purpose; when the generator does not deliver, the call is refused,
  the transaction rolls back and no form is left behind, with `layout=empty` offered as the retry that
  creates a form without the generator. `layout=empty` answers `formLayout: empty` and the form root
  receives the generator's base properties: `SaveWindowSettings`, `AutoTitle`, `AutoUrl`,
  `Group=Vertical`, `AutoFillCheck`, `AllowFormCustomize`, `Enabled`, `ShowTitle` and
  `ShowCloseButton` - `true` for the booleans - plus an `autoCommandBar` (`horizontalAlign=Left`,
  `autoFill=true`; a bar the form lacked is created named `ФормаКоманднаяПанель` with `id` -1) and an
  empty `commandInterface` with its navigation and command panels; the answer
  carries `formScaffolded`, the count of properties applied, when any was applied, and a property
  with no matching setter on the running EDT build is skipped. A common form
  (`ownerFqn=CommonForm.<Name>`) is created through `create_object` and its inner form receives
  the same properties.
- `add_form_appearance_rule`, `list_form_appearance_rules` and `remove_form_appearance_rule` are the
  conditional appearance of a managed form. `add_form_appearance_rule` takes `itemNames` - the form
  items the rule styles, comma-separated, and the whole form when omitted - a condition (`field`,
  `conditionType`, `conditionValue`) and `appearance` in the form `add_conditional_appearance` takes.
  A name that is not an item of the form, a condition and an appearance the builder refuses are all
  refused before anything is written. `remove_form_appearance_rule` removes a rule by `index` or by
  `field`, inside a condition group as well. The rules live in `ConditionalAppearance.dcssca` beside
  `Form.form`, and a file that will not write is reported as `persistWarning`. The list answers a
  colour as `#RRGGBB`, a font as `Face,height[,bold][,italic]`, and a condition group as `groupType`
  with its `items`.
- `set_excluded_commands` keeps standard commands out of the command interface of a form object:
  `itemPath` names the object - the form (`Form` or empty), the global command source
  (`FormCommandPanelGlobalCommands`) or an item (`Item.<Name>`, or the bare name) - and `commands`
  names the commands to exclude, taken without regard to case. `mode` is `replace` (the default: the
  list becomes exactly the names passed, and an empty `commands` clears it), `add` or `remove`;
  `add` and `remove` need at least one name. Only the form, a table, a field and the global command
  source keep such a list: a command bar, a context menu or a button group is refused with the
  address of the source its commands come from. Every name is checked against the object's own
  `getCommands()` before anything is written, and a name outside it is refused with the allowed names
  and the nearest match. New elements are taken from that same list, never built. An object whose
  command list is not built is refused in its own words. The answer carries `excludedCommands` (the
  final list in model order), `added`, `removed` and `changed`; a result equal to the list already
  there answers `changed: false` and writes nothing. A refusal carries `availableCommands`.
- `add_form_event_handler` and `add_command_handler` append the handler procedure to the form module
  (`writeStub`, default true). The stub goes into the module region before its `#КонецОбласти`:
  `ОбработчикиСобытийФормы`, `ОбработчикиСобытийЭлементовШапкиФормы`,
  `ОбработчикиСобытийЭлементовТаблицыФормы<Имя>`, `ОбработчикиКомандФормы`, or their English names;
  with no such region one is created at the end of the module, in the language the module is written
  in. The answer carries `stubWritten`, `stubSkippedReason` (`alreadyPresent` or `dryRun`),
  `modulePath`, `stubRegion`, `stubRegionCreated` and `stubError`. A procedure the module already
  declares with another directive or another number of parameters is reported as
  `existingProcedureMismatch` and left as it is. `remove_form_event_handler` leaves the form module as it is, and `moduleProcedure` says to
  remove the procedure with `write_module_source` when nothing else calls it.
- `remove_item` removes a form item: `formFqn` (synonym `containerFqn`) and `name`, with the item
  looked up across the whole form and `dryRun` supported. Success is reported only when the item was
  removed. A template or a metadata object is refused, and the refusal names the operation that
  removes it.
- `add_form_command_interface_item` resolves the command address into a `Command` object before it
  creates the item; an address that resolves to nothing is refused with the tag `formApiNotFound` and
  the panel is left unchanged. It accepts the same addresses as `commandName`, without regard to
  case. A nested interface item is found by walking the container; `group`, `visible` and `index` are
  coerced to the types of their setters (`CommandGroup`, `AdjustableBoolean`, `Integer`); a group
  another panel item already carries is reused.
- `list_pictures` answers the platform pictures of the project's version: `stockPictures` and
  `stockExtPictures` with their counts, and `filter` matching the English or the Russian name. With
  no picture registry it refuses with `serviceUnavailable`. `StdPicture.<Name>` and
  `StdExtPicture.<Name>` are resolved against the same list, and a Russian name is accepted and
  written in English. `add_decoration elementType=Picture` and `set_form_item_property picture` write
  the picture as a `PictureRef`; a picture that cannot be built is refused and no decoration is
  added.
- `add_field` whose `dataPath` starts at something that is not an attribute of the form is refused
  with the list of the form's attributes; an extension form is recognized by its project
  (`IExtensionProject`). `set_form_item_property` with an unknown property answers the model class
  (`FormField`, for one) and the properties that are allowed, and a list-valued property (`items`) is
  refused with a pointer to the structure operations.
- `get_form_screenshot` refuses while the form editor holds unsaved changes, and closes nothing. Its
  `activePage` is matched exactly first and without regard to case after that.
- `add_radio_button` answers JSON.

Under `dryRun`, a form operation whose action is refused inside the preview answers the refusal as a
refusal with its own text: `success: false` from `edit_metadata`, `status: error` in the front matter
of the `edit_form` answer.

Reading a form back: `get_form_structure` collects the empty containers by walking the model from the
`subtree` root, whatever `depth` and `maxElements` were given, and answers them as `emptyGroups`,
`emptyTabRows` and `emptyPages`. A group with a command source, an `Addition`, and a container whose
items could not be read are not empty. `picture` is `StdPicture.<Name>` or `CommonPicture.<Name>`,
`commandSource` is `Form`, `FormCommandPanelGlobalCommands` or `Item.<Name>`, and the height of a
field is read from its extended information. `excludedCommands` is the standard commands the object
keeps out of its command interface, as an array of names; it is emitted only when the object keeps
some, and the names are the ones `set_excluded_commands` takes back. `commandName` of a button is the
command's path as the form file writes it: `Form.Command.X`, `Form.StandardCommand.X`,
`Form.Item.<Item>.StandardCommand.X`, `CommonCommand.X`, `<Kind>.<Object>.Command.X`, or
`<Kind>.<Object>.StandardCommand.X`. The answer carries a `conditionalAppearance` section.

### Metadata operations

- `set_task_addressing` sets `addressingRegister`, `addressingAttributes`, `mainAddressingAttribute`
  and `currentPerformer` on a task, or on the task a business process is linked to (one with no
  linked task is refused before anything is written). A call naming none of the four is refused with
  the list. `addressingAttributes` is a JSON array of `{name,type,dimension}`: an attribute the task
  already has is left alone, a missing one is created with the type and dimension its entry gives it,
  an entry with neither is refused, and an attribute with no dimension takes the register's dimension
  of the same name (a register without one is refused, naming its dimensions). Every property is
  read back inside the same transaction and a mismatch refuses the call; under `dryRun` the answer
  says the change was applied, read back and rolled back.
- `create_object` builds a catalog or a document through the EDT wizard's own factory, and the object gets
  the wizard's defaults for its kind (a catalog: levels, code and description length, code type,
  uniqueness, autonumbering, folders on top; a document: number length and type, periodicity,
  uniqueness, autonumbering, posting; both: standard commands and `producedTypes`). Where that factory
  is unavailable the object is built from the model's base factory instead and the answer carries
  `warning` naming the defaults the object did not receive and why. `objectType=CommonTemplate` sets
  `templateType` to `SpreadsheetDocument` unless `properties` already names another type, and after the
  commit writes an empty `Template.mxlx`. The answer carries `templateType` and `templateContentCreated`, or
  `templateContentKept` with `templateContentNote` when `Template.mxlx` already stood in the folder;
  a failed write carries `templateContentInitWarning` naming `mxl_workshop create_template` with
  `ownerFqn=CommonTemplate.<Name>` and `templateName=<Name>`. `TextDocument` and the other types write no
  spreadsheet file. `dryRun` writes none.
- `add_object_attribute`, `add_tabular_section_attribute` and `set_object_type` answer
  `qualifierIgnored` when the qualifier applies to none of the types in the composition (`length` on
  a `Number`, for one). Under `dryRun` the first two do not borrow the reference types' targets and
  list them in `autoBorrowSkipped` (`targetFqn`, `reason: dryRun`) and do not borrow the owner either;
  outside a dry run a failed borrow of the owner is listed there as `ownerFqn` with the borrow's own
  reason. `add_metadata_attribute` reads `dryRun` as well. The adoption operations
  (`adopt_object`, `adopt_objects`, `adopt_child`,
  `adopt_form_item`, `adopt_module`) answer a dry run with a `wouldBorrow` plan.
- `remove_object_attribute`, `remove_tabular_section` and `remove_tabular_section_attribute` refuse
  with the tag `requiresCascadeForms` and the list of forms and items when a form item of the owner
  reaches the element by its data path; `cascadeForms=true` removes those items in the same
  transaction and names them in `formItemsRemoved`. The path is compared from the form's main
  attribute.
- `add_predefined_item` adds an item to a catalog, a chart of characteristic types, a chart of
  accounts or a chart of calculation types, and refuses any other kind of owner, and an adopted one.
  `code` is written in the kind the owner's code type declares - a String setter takes the text as it
  comes, a `mcore.Value` setter a number or a string built from that type - and a code that does not
  fit the type, that another item of the owner already carries, or that cannot be typed, is refused
  with nothing written, naming the owner as `Вид.Имя`. Idempotent on the item name.
- `add_subsystem_content` and `remove_subsystem_content` refuse an `ownerFqn` that is not a subsystem,
  naming the kind and the FQN. `add_url_template` and `create_http_service` refuse a template with no
  leading `/` before anything is written.
- `add_template` checks `templateType` against the model's own literals before the transaction and
  refuses an unknown one; synonyms (`GraphicalScheme`, Russian names) are taken as the model literal
  (`GraphicalSchema`), `templateType` answers the type the model wrote, and `requestedTemplateType`
  carries the requested type as the model literal when the written type differs from it. An owner FQN written with a Russian
  kind is translated through the type catalogue. `set_template_content` with an explicit
  `templateType` refuses a template that carries no catalogue, and one of another format.
- `create_route_map` writes the business process's `Flowchart.scheme`: points with `location` (four
  sides, or `x`/`y` with optional `width`/`height`) and `handlers` (one object of event to handler, or
  an array of `{event,handler}`). An event the point kind does not declare, an empty handler name and
  an incomplete `location` are refused. The answer carries `written`, `replaced` when a scheme was
  already there and this call replaced it, `points` with the coordinates as written and
  `locationFromCaller`, and `handlers`. A handler the object's module does not declare gets a
  procedure with its event's parameters (`stubsWritten`, `stubsAlreadyPresent`, `stubsToWrite` under
  `dryRun`); a module that cannot be read is reported in `stubWriteFailed`. `get_route_map` reads a
  point back as `title`, `taskDescription`, `subprocess`, `location`, its addressing attributes and
  `events` of `{event,handler}`, and a transition as `title` and, out of a `Condition`, `branch`.

`edit_form` exposes the same form operations under a smaller surface. Prefer `edit_metadata` when
form edits are chained with other metadata edits. Both facades refuse a form write under
Read-only, Debug & Test and Code Review by the `edit_form_writes` / `edit_metadata_writes` doors
before the project is read; `help` and `dryRun` previews keep working.

## Infobase and launching

| Operation | Notes |
|---|---|
| `get_applications` | The project's applications: `applicationKind` (`infobase` / `server` / `other`), where an infobase lives (`infobase.location` `file` with `path`, or `server` with `server` and `infobaseName`), `operationsNotAvailable` for a non-infobase application with `capabilities` saying what it still answers to (a server launches, updates, debugs and runs tests; extension management refuses), `launchConfigurations` bound to it (name and client), `updateNextStep` when it is not up to date. |
| `list_registered_infobases` | Every infobase in EDT's own list, not only the project's: `groups` and `infobases[]`, each with its group path, `type`, `connectionString`, platform `version` and `additionalParameters` with the passwords masked, and the `projects` bound to it. A project whose bindings could not be read is named in `projectsNotRead` rather than left out silently. |
| `create_infobase`, `delete_infobase` | Infobase lifecycle. |
| `register_infobase` | Registers an EXISTING infobase - a file `path` or a server `connectionString` (`Srvr=...;Ref=...`), exactly one of the two - in EDT's list and binds it to the project in one call. A duplicate address is answered as `reused`; a failed binding rolls the added list entry back. `accessMode` / `userName` / `password` store the infobase credentials in EDT's encrypted store in the same call - written after the list entry and before the binding (the same contract as `set_infobase_credentials`), so a base with users registers without an interactive login prompt; a failed write refuses before the binding and the answer carries the reason. The answer names what was stored (`access`, `userName`, `passwordStored`, `verifiedByReadback`); the password never appears in the answer, the call history or the journal. |
| `set_infobase_credentials` | Stored credentials for a launch configuration. Writing EDT's infobase list keeps launch configurations bound: this operation, `create_infobase`, `delete_infobase` and `register_infobase` put each configuration's application id back after the write and answer `launchApplicationIds`. A failed write is answered with both passwords masked. |
| `create_launch_config` | Binds an infobase to a project in the project's current association context - the branch, for a project under version control - and answers with `associationContext` and the `applicationId` the binding created. Disabled under Debug & Test, both as a direct call and as the facade operation. |
| `start_client` | Starts a 1C client from a launch configuration, without a debugger. Use it instead of building a `1cv8.exe` command line - the client then matches what the IDE is configured for. A `projectName` + `applicationId` pair with no launch configuration gets one created and saved (`autoCreatedConfiguration`). Takes `startupOption` for a `/C` string, `clientType` / `runMode` for the client and its run mode (see `launch_debugger`), and `waitForEndpoint` / `endpointTimeoutSeconds` to wait for what the client opens. `launch_debugger action=launch` if you want the debugger, `action=terminate` to stop either. With `updateBeforeLaunch=true` under a preset that disabled `update_database` (Read-only, Debug & Test, Code Review) the start is refused before the update and before the launch, naming `updateBeforeLaunch=false`. |
| `update_database` | Writes the configuration into the infobase. Validate for export first. An update that would drop an entity the infobase holds data for is refused before anything starts (`protectData`, on by default) - see `expected-behavior.md` for the refusal and what carries the deletion through. With `dryRun=true` it starts nothing and answers the update state (`updateState`), whether an update is needed (`wouldUpdate`) and, when the infobase belongs to the parent configuration, its owner (`infobaseOwner`). Readiness and the export validation an update runs first are NOT checked and the answer says so: `notCheckedInDryRun` lists them and `notCheckedInDryRunNote` says why - readiness reaches the infobase synchronization cycle through a thick client and does not return while a thick-client session holds the infobase; the export validation walks the whole project, and `diagnostics operation=validate_for_export` runs it. Such a call is answered in place: no run is recorded, no `runKey` is issued and no infobase is claimed. The objects an update would carry are not reachable that way, and `composition` says so. Before an update starts, the stored `ConfigDumpInfo.xml` format is compared with the one a rebuild recorded for this infobase, and a mismatch refuses the update naming `infobase_admin operation=sync_control syncOperation=rebuild_dump_info confirm=true`; `ignoreDumpInfoFormat=true` goes ahead, and `dryRun` reports it in `dumpInfoFormatCheck`. An incremental update is decided against the stored copy, and a sidecar `ConfigDumpInfo.record.properties` beside it records which infobase the copy was written for and its fingerprint; every finished update rewrites it. An incremental update is refused with `infobaseChanged` and `nextStep` (`infobase_admin operation=sync_control syncOperation=rebuild_dump_info confirm=true`) when the copy was written for another infobase than the one the application points at (`recordedInfobase`, `currentInfobase`) and when a `restore_database_snapshot` load replaced the infobase (`loadedFrom`, `loadedAt`); `rebuild_dump_info` clears that refusal (a sidecar it could not write is named in `copyRecord`), and `fullUpdate=true` does not read the copy and is not gated. `verifyInfobaseContent` (default false) reads the infobase's own `ConfigDumpInfo` before an incremental update, through the same Designer run `rebuild_dump_info` uses, and compares it with the copy: a mismatch refuses with `infobaseChanged`, `infobaseRecords` and `copyRecords`, a failed read refuses with the reason and the tag of that read or Designer launch failure (such as `busy`, `infobaseNotReleased`, `thickClientFailed`, `resolveFailed`), a match is named in the answer; the stored copy is not replaced. `dryRun` and `fullUpdate` do not read the infobase and say so. `infobaseChangeCheck` in the update, dry-run and `inspect_database_sync` answers names the outcome; a sidecar that could not be written is named in `infobaseChangeRecord`. With `statusOnly=true` it reads the tracked updates instead - `updates[]` with `runKey`, state and progress per run, filtered by `projectName`; it starts nothing and does not claim a finished result, and a `runKey` of another kind of work is refused. Before the state is read it refreshes the project and, for an extension, its parent from disk (`refreshWorkspace`, default true): files written outside this server (a file tool, git checkout, a pull) are invisible to the model until then, and the answer reports `workspaceRefresh.changedResources` - 0 means the model already matched. Pass `refreshWorkspace=false` only when every change went through this server. |
| `branch_infobase` | Binds a git branch to a launch configuration, so `update_database` refuses to write into an infobase that belongs to another branch. For a project that is an extension the binding lives in the extension itself, not in the configuration it extends. Disabled under Debug & Test, both as a direct call and as the facade operation. |
| `inspect_database_sync` | Reads what stands between the project and its infobase and starts nothing. Its `dataLossProtection` block carries `nextUpdateProtectsData`, `acceptDataLossDefault`, `dataLossCompared`, `pendingDataLoss`, `pendingDataLossCount`, `baseline`, `dataLossCheck` and `nextStep`, with `nothingStarted: true` beside it - the data-loss question answered before an update is attempted. `restructureConfirmationPrompt` (`on` / `off` / `unknown`) reports the infobase's own preference; this call reads it and never writes it. See `expected-behavior.md`. |
| `export_database_snapshot`, `restore_database_snapshot` | The whole infobase as one `.dt` file. The dump takes `path` (required) and refuses a file already standing there instead of replacing it (`alreadyExists`); it is written by one thick-client launch of the platform into a temporary file beside the destination and moved over only when it is non-empty and this run's product (`outputMissing`). The load takes the `.dt` at `path` (missing or empty file - `inputMissing`) and writes a copy of what the infobase holds now first: `backupTo`, or `<stem>-backup-<time>.dt` beside the file when omitted, an existing file at the backup path refused (`alreadyExists`); the load does not start without the backup, and it replaces everything the infobase holds. Both claim the infobase monopoly for the run (snapshots of all infobases go in turn), and a `busy` refusal names the holders in `infobaseHolders` when the server sees them - the clients EDT launched and the other instances of this server on the machine. `timeoutSeconds` 5-120, default 30 (`waitSeconds` accepted as an alias): past it the answer is `Pending` with a `runKey`; poll with the `runKey`, and `cancel=true` beside it stops tracking - a launch already inside the platform is not interrupted and a load that has begun is not undone. A success answer carries `status` (`Exported` / `Loaded`), `path`, `sizeBytes`, `durationMs`, `infobase`, and for a load `backup` with `backupSizeBytes`, and a load refused after its backup was written names `backup` and `backupSizeBytes` as well; a finished load marks the stored `ConfigDumpInfo.xml` copy (`copyMarked: true`, `infobaseChangeCheck`, `nextStep`), and an incremental `update_database` is refused until `rebuild_dump_info` rewrites the copy; a mark that could not be written is `copyMarked: false` with the reason in `infobaseChangeCheck` and `nextStep`: the next incremental update does not see that load until the copy is rebuilt; a run abandoned at the 600-second launcher budget while its platform process lives holds the infobase claim, and the answer names `lockHeldForProcess`; `leftBehind` (the file the live process writes) appears when the failure fell on writing the export's temporary file or the load's backup copy. |
| `sync_control` | Inspects and controls EDT-to-infobase synchronization. The action travels as `syncOperation` here, because the facade's own `operation` routes the call. See the safety rule in `expected-behavior.md`. `status` lists every application of the project with whether its infobase has a baseline; `mark_synchronized` accepts a binding with no `index.idx` yet and stamps the project's configuration id on the baseline it writes (`configurationUuidStamped`, `stampError` when the stamp fails). `rebuild_dump_info` (`confirm=true`) rebuilds `ConfigDumpInfo.xml` with the infobase's own Designer dump and keeps the previous file as a copy. `retrieve_database_changes` pulls the changes made in the infobase into the project - the direction opposite to `update_database`, through EDT's own synchronization manager; `applicationId` names the binding when the project has several applications and without it the project's first binding is taken, and for an extension project the infobase of the configuration it extends is pulled from (`infobaseProject`, `viaParentProject`). A project carrying changes of its own is refused until `replaceLocal=true` (the infobase's version of those objects then replaces the project's); the answer carries `localChanges`, `conflictAsked` and the infobase's change counts (`infobaseChangesNew` / `Modified` / `Deleted`), and a refusal reached before the synchronization state is read carries none of them: the synchronization manager or the application manager is unavailable, the project is not ready, the project's applications cannot be read, no binding carries the named `applicationId`, or the chosen binding carries no infobase. After a successful pull the sources of objects the infobase no longer has are removed (`removedSources`, `skippedObjects`, `removalFailures`, `filesKept`), the project is refreshed (`refreshed`) and its build waited for; `markSynchronized=true` then runs the same baseline rewrite as `mark_synchronized` (`baselineMarked=false` with `baselineMarkError` when it fails). A thick client this EDT launched is refused before the platform is asked (`busy`, `heldBy` with the launch names), and an infobase held by a monopoly claim is refused with `heldByThisInstance` when this server is the holder. `timeoutSeconds` 30-3600, default 300, is how long the call waits before answering `Pending` with a `runKey`; the platform pull is not cancelled when the budget runs out - poll with the `runKey`, a fresh call without it joins a run in progress with the same argument set, and a finished result is not handed out again, and `cancel=true` stops tracking, the answer naming `platformCallStillRunning` when the call is still going. A success answer carries `pulled`, `resolution` and `durationMs`. |

## Configuration import and export

`config_io`: `export_configuration_to_xml`, `import_configuration_from_xml`,
`import_configuration_from_binary`, `export_object` (an `.epf` or `.erf`), `export_common_picture`,
`export_configuration_to_cf`, `export_infobase_objects`, `export_database_configuration`,
`export_database_extension`, `unpack_external_binary`.

`export_database_configuration` dumps the MAIN configuration out of the infobase as a `.cf`, and
`export_database_extension` the named extension (`extensionName`) as a `.cfe`. Both are guarded so
that an old file cannot pass as this call's product: an existing `outputPath` is refused unless
`overwrite=true`, and an infobase out of sync with the project (anything but `EQUAL`) unless
`allowOutOfSync=true` - run `update_database` first. Both are synchronous.

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
Disabled under Debug & Test, both as a direct call and as the facade operation.
EDT cannot read binary formats at all, so this detour is the only route; the platform can read
them, but only into an infobase. On a large configuration the call takes minutes and returns a
`runKey` to collect the result with, rather than holding the connection.

For an extension that borrows from its base, both imports take `baseProjectName` - the project of
the configuration it extends. A `baseProjectName` that names no project is refused with the tag
`projectNotFound` rather than staged against an empty infobase.

`export_object` answers `outputMissing` when the platform reported a successful build and no
`.epf` / `.erf` appeared at `outputPath`. Read that tag as the artifact not existing, whatever the
build said.

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

`action=pause_thread` stops the named thread (`threadId`), or every thread of the session when none
is named. It answers `outcome`: `paused`, with the stopped thread, its stack and `topFrameRef`; or
`request_armed`, `terminated`, `error`. The session is found by `applicationId`, or taken as the only
active launch.

`action=set_breakpoint_state` takes `breakpointId` and `breakpointEnabled` and turns one breakpoint
on or off, keeping its line, condition and hit count; the answer carries `changed`.
`action=add_breakpoint` with `replaceModuleSet` clears the addressed modules of their breakpoints
before setting the batch, so the batch becomes their whole set - the answer names `removedCount` and
`clearedModules`. `action=set_exception_breakpoint` with `catchAll=false` and no `message` refuses
before creating the breakpoint.

A frame reference is not resolved to another frame after a fresh break: `action=get_variables`
carries the frame address in its answer, an index outside the stack is refused, and a scope that
could not be read is named in `scopeFailures`. A value written by `action=set_variable` does not go
into the journal. A long value from `action=evaluate` is cut with `truncated` and `fullLength`, and
when the wait runs out the answer says the expression is still running.

`action=launch` that updates the infobase refuses unless the base reached `UPDATED` - an update
already running, a load that ended in another state, an application that was not found or did not
resolve. `FULL_UPDATE_REQUIRED` means a full update is needed. The refusal carries `databaseUpdate`
and `databaseState`, and a session that did start carries `databaseUpdate`. The already-running check
runs before the update.

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
`updateBeforeLaunch=true`. A preset that disabled `update_database` (Read-only, Debug & Test,
Code Review) refuses an explicitly asked update - and with it the launch - before anything starts,
naming `updateBeforeLaunch=false`; a launch that named no argument runs without updating under
such a preset.

## Tests

`yaxunit_tests` with `mode=run` or `mode=debug`. Filters: `extensions`, `modules`, `tests`. `suites`,
`tags` and `contexts` are not filters - they are refused rather than ignored. `updateBeforeLaunch`
(default true) runs the infobase update before the launch and refuses the run when that update did
not finish, naming `updateBeforeLaunch=false` as the way to launch without one; that update is why
`validate_for_export` matters here too. A preset that disabled `update_database` (Read-only, Debug
& Test, Code Review) refuses an explicit `true` before the update and before the launch, while a
call without the argument launches without updating - and every answer of that run, the finished
report, the `Pending` one and the report a repeat call picks up, carries
`databaseUpdate=SKIPPED_BY_PRESET`. `reuseRecent=true` (`mode=run`) answers with a run that finished within
the last five minutes instead of starting another, saying so with `cached: true` and the moment it
finished.

`installYaxunit=true` installs the engine when it is absent and, on both the fresh-install and
already-installed paths, reads its safe-mode and unsafe-action-protection flags through the designer
session. By default `yaxunitUnsafeMode=true`: when either is on, both are lowered in one write and a
control read must confirm both off before the tests start. A mismatch is an error carrying both actual
values, and no test launch follows it. `yaxunitUnsafeMode=false` leaves the flags entirely untouched.
Write-blocking presets refuse this pre-step through the `install_extension` door before it changes the
infobase.

Every completed run writes a receipt file under `<state-location>/run-receipts/<tool>/`, at most
twenty, the oldest displaced first. The answer names it in `receiptPath`, or `receiptError` when it
could not be written. A key that looks like a secret (`password`, `pwd`, `scenarioText`) is left out
of the receipt at any depth, inside lists as well. A run answered from `reuseRecent` writes no
receipt. `run_yaxunit_tests` is an alias of this tool that declares and answers JSON.

`vanessa` drives scenario UI tests from the outside and photographs a form of a running 1C. The scenario comes as a file (`featurePath`), as text (`scenarioText`), or is composed from `formToOpen` - and then `openStep` carries the wording, which differs for a list form, an object form and an extension's form - or from the list arguments: `listKind` with `listName` opens the list (ten metadata kinds), `tableName` names the form's table (`Список` when it is left out), `column` with `columnValue` goes to the row (`whenSeveral`: `unique` by default demands exactly one, `first` takes the first), `buttonTitle` or `buttonName` presses the form's button, `windowTitle` names the window to wait for (without it, one whose title differs from before the click) and `windowWaitSeconds` how long (default 10, and it may not exceed `timeoutSeconds`). Once the window is open the scenario saves the frame itself - `И я сохраняю скриншот "<file>"` with an absolute path in the run's screenshots directory - on a list action and on `formToOpen` alike. The add-in draws the test client's top window into the file, so the frame is that window and not the screen, whatever covers it. The capture keys written into VAParams on both branches are `ИспользоватьКомпонентуVanessaExt`, `ИспользоватьВнешнююКомпонентуДляСкриншотов` and `СпособСнятияСкриншотовВнешнейКомпонентой` = 1 (the current test client window, which the frame step and a failure screenshot both honor), and the list arguments mix with none of the other ways of naming the scenario. `screenshots=false` is refused on this call, and `vanessaParams` may not carry `ИспользоватьКомпонентуVanessaExt`, `ИспользоватьВнешнююКомпонентуДляСкриншотов`, `СпособСнятияСкриншотовВнешнейКомпонентой` or `ТаймаутДляАсинхронныхШагов`. The answer names `sought`, and on a failed step `onScreen` - the window read out of the failing step's own text, empty where Vanessa names none. That step needs the UI-testing types, which exist only in a client started as a test manager (`testManager`); without it the step answers Тип не определен. `testClient` names the client the start step launches, `testClientPort` its port, `infobaseUser` the user to sign in as - a password is refused. On a list action and on `formToOpen` both are turned on when left out, and an explicit false of either is refused before the run starts: those scenarios open a form, so the start step activates the client this tool names (`КлиентыТестирования` with `АктивизироватьСтроку`), the one whose infobase is the project's. A client written only into `datatestclients` is not the row that step reads. The verdict is read from the `<uuid>-result.json` files Vanessa names itself.

`vanessa` writes the same receipts. Its answer names the files Vanessa wrote in `resultsDir` and `resultFiles` and, when
the result was read from a JUnit report instead, `junitXmlPath`. A run that reaches its timeout
reads the 1C client windows holding it before the process is stopped and answers them as `blockingWindows` (`pid`, `title`, `texts`, `buttons`, `modal`, and `imageFile` when a
screenshot was taken; an empty list when no window was found) and `blockingWindowsError` (only when
the windows could not be read); with a window found, the advice does not suggest
waiting longer. The image is taken with `PrintWindow`; texts come from `Pane` and `Text` elements,
without repeats and without the line equal to the title, a line longer than 500 characters is cut
with `...`, at most 20 lines of one kind are listed and the rest is named as a number. The
`blocking-*.png` images are kept beside the receipts under the same file limit.

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
folder to keep it in. Nothing is written there while recording is off. The full text on disk is
masked under the same flag as the journal (`mcpHistoryFileRedact`), and a folder path the server
cannot use does not stop it starting: the store falls back to the plugin state location and names
the reason.

## The repository the project lives in

`git` answers what a shell would answer about the project's repository, inside the IDE, through
the JGit the environment ships: `operation=status` (work tree and index against HEAD, and how far
the branch is ahead of or behind its tracking branch), `branches` (local branches, the current one
first), `log` (recent commits, `limit` up to 100), `commit` and `checkout`. The repository is the
nearest `.git` walking up from the project's directory, so a project anywhere inside a repository
is answered about that repository.

`commit` stages only what `paths` names - comma-separated, relative to the repository root - and
refuses a call without them: there is no add-all. `.`, `*`, a pattern, a directory, an absolute path
and a path leaving the repository through `..` are refused before a file is read, and so is a path
that names nothing on disk and nothing in the index; a tracked file that is gone is staged as a
deletion. Nothing else in the index enters the commit. The author is the repository's
`user.name` / `user.email`, checked before anything is staged; a repository that names none needs
`authorName` and `authorEmail`. When none of the named paths has a change, the call is refused and
the paths are unstaged again. The answer carries `changes[]` (`path`, `change` - `added`,
`modified` or `deleted`) and, when some named paths had nothing to commit, those names in
`unchanged`. When the branch is bound to an infobase (`branch_infobase`), the answer names it as
`boundInfobase`.

`checkout` takes `branch` and, with `createBranch=true`, creates it from HEAD. A switch that would
overwrite uncommitted files is refused with those files listed in `conflicting` and changes nothing;
unrelated uncommitted work carries over, as git itself does it.

`show_file_changes` reads a diff without writing anything: without `filePath` it lists the changed
files with their line counts, `filePath` narrows it to one file and
`fromRef` / `toRef` name the ends (working tree by default at the `to` end, `HEAD` at the `from`
end). `limit` caps the listed files at 50 by default and 200 at most, and the answer gives the full
number in `totalFileCount` with `truncated`. With
`granularity=method` the answer names each changed procedure and function of one `.bsl` file named
in `filePath` - any other file is refused there.

`revert_file` puts one file back to the bytes `fromRef` holds, default `HEAD`, with the line endings a
`git checkout` of that path would give it (`.gitattributes` `text` and `eol`, `core.autocrlf`); with
no rule in the repository the file keeps the endings it has. `dryRun=true` previews it and writes
nothing, even while an editor holds unsaved changes for the file; only the write is refused in that
state. The answer carries `bytesWritten`, `restoredFrom`, `lineEndings`, `fileStatus` and
`indexUntouched` - the index is not touched. After a `.form`, `.mdo` or `.dcs` was restored, the
answer advises `revalidate_objects`: the model still holds the previous state until it is refreshed.

`create_merge_restore_point` records the project files as a restore point and answers `pointId`,
`kind` (a commit, or a copy of every file) with `commit` or `copyPath`, `files` and `fileCount`. A
point that cannot be taken answers `mergeStarted: false` and names the storage directory and the
reason. `restore_merge_point` takes `pointId`, the project's latest point when it is left out, and
puts those files back, listing `restoredFiles` with `restoredCount`, the files whose bytes already
matched in `unchangedFiles`, and the files the point does not hold in `removedFiles`, which name
only the files that were really removed. A file that already matches the point is not rewritten.
A restore that stops halfway still names what it did not put back in `unrestoredFiles`, still
removes the extras and still refreshes the workspace; an extra that could not be deleted is named
with its reason in `cleanupFailures`, a failed workspace refresh in `refreshFailure`, and either
makes the answer an error that says so beside the primary one. It needs no repository: a project
outside git is served from a copy of its directory. `delete_merge_restore_point` takes a required
`pointId` and drops that point - the ref or the copy and the index entry; no project file is
touched, and a point of another project is refused. A ref or a copy directory that could not be
deleted is an error with the reason, and the index entry is kept so the point can be deleted
again. See `expected-behavior.md` for what such a point covers.

The writes `commit`, `checkout`, `revert_file`, `restore_merge_point`, `create_merge_restore_point`
and `delete_merge_restore_point` are switched by presets under the names `git_commit`,
`git_checkout`, `git_revert_file`, `git_create_merge_restore_point` and
`git_delete_merge_restore_point`: under Read-only the facade still reads and each write is refused
before a file is staged or restored or a point is taken or dropped; `restore_merge_point` passes
through the `git_revert_file` door, and taking a point writes nothing into the work tree but starts
a ref in the repository or a copy of the project directory. All five names are also callable on
their own as aliases of the operation.

## Tools that stand on their own

Not every tool belongs to a facade. These are called by name.

| Tool | Use it for |
|---|---|
| `get_metadata_objects` | The objects of a configuration, filtered by type and name. Omit `projectName` to search every open project at once - useful when you are looking for where an object lives. |
| `get_metadata_details` | One object in depth: attributes, tabular sections, forms, modules. |
| `get_command_interface` | The command interface of a subsystem: what it shows and in what order. Checks that the project is ready and reads the interface inside a model read transaction. |
| `generate_event_handlers` | Handler stubs for the events of an OBJECT module - catalogs, documents, registers. Not for form handlers: those come from the form operations of `edit_metadata`. Read the stub before relying on it; the parameter lists come from a table here, not from the platform. |
| `copy_object` | Copies an object into another project as its own, not adopted; forms, modules and presentation travel with it. |
| `find_dead_code` | Exported methods nobody calls. |
| `dcs_search` | Finds data composition schemas by what is inside them. |
| `code_template` | Ready code patterns instead of writing a familiar shape from memory. |
| `naparnik` | Whether 1C:Naparnik is installed, which version, and whether that version is one of the supported `1.0.7`, `1.0.8`. `status` only lists bundles. `probe=true` checks each link and starts the Naparnik UI bundle if it is not already running. `ask` sends one question to 1C:Naparnik 1.0.7 or 1.0.8 (`projectName`, `question` up to 20000 characters, `conversationId`, `replyTo` only together with `conversationId`, `maxToolRounds` 1..30 default 10 and always sent positive, `timeoutSeconds` 30..1800 default 300, `waitSeconds` 1..120 default 30, `runKey`, `cancel` only with `runKey`). The question, and whatever Naparnik's tools read, goes to the 1C:Naparnik service. The bridge (`mcpNaparnikBridgeEnabled`) is off by default. With it on, `ask` allows only the read tools, including service knowledge-base reads named `mcp__knowledge-hub__` and starting `Search_`, `Fetch_`, `Diff_` or `Get_` (`allowedServiceTools`), unless `mcpNaparnikAllToolsEnabled` is on (off by default): then the request carries no tool filter, and Naparnik may change metadata, write files and execute code in EDT. An answer with no text and no tool call is refused, with `conversationId` and `replyTo` kept for asking again; no text after tool calls is a success with `answerEmpty: true`. Read-only, Debug & Test and Code Review disable `naparnik` in both modes. One question at a time; past `waitSeconds` the answer is Pending with a `runKey`. |
| `get_mcp_history` | What has been called on this server recently, with arguments and answers. Useful for retracing your own steps; a person reads the same buffer from the status bar. A record carries `arbitratedBy` (`tool` / `signal` - whether the operator answered the agent from the status bar instead of the tool), `deliveryStatus` (`delivered` / `failed`) and, for a signal, `signalType` and `signalNote`; `stats` carries `interrupted` and `undelivered` on top of `success` and `failure`. |
| `self_status` | Server, EDT services, queue and heap. Ask when the server answers but one operation misbehaves. |
| `marker_corrections` | Applies the fix the check that raised a finding offers, rather than inventing a repair by hand. Its own `list` and `apply` operations; it is NOT an operation of `diagnostics`. |

Three more are reached through a facade rather than by name, because the Canonical preset hides
them: `extension_workshop operation=list_interceptors`, `project_admin operation=self_upkeep` and
`project_admin operation=answer_dialog`.

Debug & Test switches off the destructive members of the applications group by name, so these are
refused under it both as a direct call and as the facade operation: `update_database`,
`create_infobase`, `delete_infobase`, `delete_project`, `create_project`,
`import_configuration_from_xml`, `import_configuration_from_binary`, `install_extension`,
`uninstall_extension`, `set_infobase_credentials`, `register_infobase`, `branch_infobase`,
`create_launch_config`, `sync_control`, `resync_to_disk`, `restart_edt`, `answer_dialog`,
`self_upkeep`.

## Will this delivery break the extension

Two questions, and neither answers the other.

| Ask | Operation | What it reads |
|---|---|---|
| what a new delivery breaks in an extension | `extension_workshop operation=check_release_fitness` | declarations in the model: a borrowed object gone, a borrowed field gone, a field whose type moved. Needs `baseProjectName`, the project the new delivery is loaded as |
| what drifted in the extension and what apply would write | `extension_workshop operation=update_borrowed` | the extension's own record of what it borrowed, against the base: uuid links, controlled types with qualifiers, form items, handler signatures. `apply=true` aligns the controlled types and updates the borrowed forms; the rest it refuses to touch and says so |
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
| `dcs_workshop` | Data composition schemas. Validates query text and expressions before writing. `repair_schema` (through the facade: `edit_metadata operation=repair_report_schema`) puts the schema a `.dcs` holds back into a model that lost it - the template opens in EDT with the schema unavailable while the file is intact. Arguments `projectName`, `objectName`, `templateName` (by default the owner's main schema, named in the language of the configuration), `overwriteModel`, `dryRun`. The file is never written. `outcome`: `restored` (the model held none), `matched` (the model already serializes to the file, nothing changed), `refused_model_differs` (the model holds another schema - replaced only with `overwriteModel=true`, and then its serialization is written to `backupPath` beside the `.dcs`), `replaced`, `file_changed` (the file changed between the read and the attach - repeat), `no_template`, `no_file`. `success` is true only with `confirmed=true` - the model read back after the commit serializes to the file; otherwise `confirmation` says why. `dryRun=true` decides and answers without changing anything. An Object dataset requires `dataObjectName` on `add_dataset` and on `add_union_item` alike - a non-Object dataset refuses the argument. `add_total` writes `dataPath` from the expression unless `dataPath` is given. `add_appearance` builds a filter from `field` with `conditionValue` (a partial condition is refused with the argument named) and takes `appearance` as `Name=Value;Name=Value` - a JSON object or array is refused, it corrupts the file. The same arguments are on the `edit_metadata operation=add_conditional_appearance` alias. A write to report settings answers `settingsWarnings`: a filter that compares against nothing (`emptyFilterValue`) and a custom period without dates (`emptyPeriod`). Every operation except `help` writes the schema or the settings, and Read-only, Debug & Test and Code Review refuse it by the `dcs_workshop_writes` door before the UI thread is taken; `help` and `dryRun` keep working. |
| `mxl_workshop` | Spreadsheet templates. Coordinates are 1-based. `check_print_width` answers from the model alone whether the print area fits the sheet by width. The content span is the column set the platform's paginator prints: the document's columns and the sets the rows carry are compared, each counted to its declared size rather than to the last cell, and the widest wins; a columns print area is measured over begin..end with the columns of row 0, not the set stored on the area; a rectangular print area is measured over x..x+width-1, the span the fit-to-width scale uses, and when the platform paginates that rectangle it takes one column more. Column widths follow the platform's inheritance: the column's own format, the format of the set of columns, the document's default format, then the platform's 72 eighths of a character. One character's width is measured in the template font (Arial 8), and `charWidthSource` names where the number came from, because a template near the edge flips its answer with the font the machine has. Page settings the model never set are taken as the platform takes them (A4, portrait, 10 mm margins, 100% scale) and are listed in `assumed`; a paper declared by its own dimensions (code -1 with pageWidth and pageHeight, millimetres) is measured by those, and any other code but A4 is measured as A4 and `assumed` says so. `verdict`: `fits`; `borderline` - over the printable width by no more than 5%, which another font moves back to `fits`; `overflows`; `smallPrint` - with `fitToPage`, the scale the content needs is below `smallScalePercent` (10..100, default 75); `empty` - no cell anywhere in the template. The answer carries `contentWidthMm`, `contentWidthCharUnits`, `printableWidthMm`, `marginMm` and `overflowMm` (one figure and its negation), `orientation`, `paper`, `printScalePercent`, and with `fitToPage` also `requiredScalePercent` and `fontSizeAfterScale`. A print scale stored in the model is reported as `printScalePercent` and is not folded into the width. Nothing is written. Writing operations are refused by Read-only, Debug & Test and Code Review through the `mxl_workshop_writes` door before the project is read; `read_template`, `list_named_areas`, `check_print_width`, `help` and `dryRun` keep working. |
| `xdto_workshop` | XDTO package schemas. Create the package with `edit_metadata` first. Writing operations are refused by Read-only, Debug & Test and Code Review through the `xdto_workshop_writes` door before the project is read; `read`, `help` and `dryRun` keep working. |
| `extension_workshop` | Extension projects, borrowing objects and members, deployment, comparison, and what a new delivery does to an extension. Borrowing writes the link that makes the extension actually extend, and a borrow that cannot write it fails rather than reporting success; calling borrow again repairs an object left unlinked by an older build. `borrow_module` needs `moduleType` wherever an object has more than one module. `borrow_child` composes the child's FQN from `objectFqn` plus `childKind` and `name` - before that it borrowed the OWNER and answered "borrowed". `borrow_form_item` borrows the FORM that carries the item: a form item has no address of its own, so name the form with `formName` beside the owner or spell it in `objectFqn` (`Catalog.X.Form.ФормаЭлемента`); without a form named the call is refused and nothing is borrowed. `update_borrowed` (arguments `projectName`, optional `baseProjectName`, optional `objectFqn`, `apply` default false) reviews everything the extension borrowed against the updated base - attributes and tabular-section attributes by uuid and by the controlled type composition (Checked entries, qualifiers included), forms by item names, interceptors by handler signature - and answers one status per row: `inSync`, `baseWider` (base wider, qualifiers unchanged), `typeConflict`, `sourceGone`, `renamedInBase`, `formOutOfDate`, `handlerSignatureChanged`. `apply=true` writes only the type conflicts (the base's exact type with qualifiers, or one `AnyRef` without qualifiers when the base went composite with reference types) and updates borrowed forms through the adopt service after its `isUpdatable`; everything else lands in `notApplied` with the reason, and `stillOutOfSync` is a re-read after the writes. Read-only, Debug & Test and Code Review refuse `apply=true`. `install_extension` loads a `.cfe` into an infobase and, with `updateDatabase` (default true), applies it there (`/UpdateDBCfg -Extension`): `databaseUpdated` is set only when the Designer log confirms the update, `databaseUpdateNote` explains an update that is not confirmed, and errors in that log refuse the call with `databaseUpdateFailed`. |
| `external_object_workshop` | External data processor and report projects, which are standalone DT projects rather than configuration objects. `import_external_object` adds an `.epf` / `.erf` to an existing container: `targetProjectName`, `inputPath`, `baseProjectName` (the configuration; the container's parent by default), `applicationId` (when the configuration has several applications - see `get_applications`). The binary is converted through the Designer of that application's infobase, the way `unpack_external_binary` does it: EDT releases the infobase and takes it back; a release that fails refuses the import (`infobaseNotReleased`), a reconnection that fails is named in `reconnectError` whatever the import's own outcome. The answer carries `hostProject` and `infobaseName`. Both operations write the workspace, and Read-only, Debug & Test and Code Review refuse them by the `external_object_workshop_writes` door before the workspace is read; `help` keeps working. |
| `external_data_source_workshop` | Tables, fields and functions of an external data source. Writing operations are refused by Read-only, Debug & Test and Code Review through the `external_data_source_workshop_writes` door before the project is read; `list`, `help` and `dryRun` keep working. |

**An open editor outranks the file.** `read_module_source` returns the editor's unsaved text and marks it in the answer; `write_module_source` refuses while such an editor holds the file. What is read and what is written then describe one state rather than two.

`dcs_workshop` in detail. `add_field` writes `title`, `type` and one `property`/`value` pair, reading
`property=type` as the type; an unknown property or a type the field cannot take is refused before
the field is added. `add_parameter` and `set_parameter` apply `length`, `precision`, `fractionDigits`,
`nonNegative`, `dateFractions` and `allowedLength` to the value type and write `expression`, `use`,
`valueListAllowed` and `denyIncompleteValues`; `use` takes `true`/`Always`/`Всегда` as Always and
`false`/`Auto`/`Авто` as Auto, and anything else is refused. A qualifier without `type`, a type that
does not resolve and a field the parameter did not take are refused with "Nothing was written".
`set_settings_parameter` with no `value`, `userSettingID` or `use` is refused and the literal is left
alone; `set_output_parameter` with no `value` is refused, and creates the output-parameter container
and its record when the settings carry neither. Its name is checked against the platform's list of
output parameters: an unknown one is refused with the closest name, and where that list cannot be
read the record is written and the answer carries `outputParameterNameChecked: false` with
`outputParameterNameCheckNote`. `remove_dataset` also removes the calculated and total fields whose
expression reads the dataset - its name as a whole word followed by a dot, anywhere in the expression
(`Sum(Sales.Amount)`), compared without regard to case; in `affectedSettings` a
path beginning with a dataset name is read as one identifier. `remove_query_condition` removes the `AND`/`OR` beside the condition; one
between an `AND` and an `OR` is refused. An unknown `comparisonType`, `conditionType`, `orderType` or
`groupingType` is refused with the list under `Allowed:`, and a `parentPath` step is compared without
regard to case. `set_dataset_link_property` refuses a property the link does not have and a value its
setter did not accept, naming the link and the property. `remove_dataset`, `remove_dataset_field`,
`remove_parameter`, `remove_calculated_field` and `remove_total_field` read every settings variant
and name in `affectedSettings` the selection, order, filter, structure, conditional appearance and
data parameters that point at what is being removed, without changing them;
`reportAffectedSettings` defaults to true, and `false` leaves `affectedSettings` and `affectedCount`
out of the answer. `add_settings_table` and `add_settings_chart`
write the resources the schema declares into the selected structure fields and name them in the
answer; a schema that declares none answers that nothing was selected. `remove_conditional_appearance`
reads `target` the way `add_conditional_appearance` does - `schema` and `settings` for the default
settings variant, anything else a variant by name, an unknown variant refused by name - and a removal
that finds no appearance does not create the container. With `formFqn` and `attributeName`, the
settings of a dynamic list go into `Attributes/<name>/ExtInfo/ListSettings.dcss`, and a file that
will not write is reported as `persistWarning`. An answer to `dryRun=true` carries `"dryRun": true`,
on success and on refusal alike.

`mxl_workshop` in detail. `set_cell` takes `fillType` and `parameter`; `format_cells` takes borders,
font, colours, pattern and print parameters, and a call carrying print parameters alone needs no
range. `textOrientation` is degrees from 0 to 360, and `read_template` answers the angle in degrees.
The first format created is written into the template file. A font change takes the font the cell is
displayed in - its own, the row's, the column's, the default format's - and the link to the style is
kept. `set_cell` refuses text together with `parameter`, and a parameter name with `fillType=template`;
`format_cells` refuses `fillType` and `parameter`. A non-numeric `borderWidth`, `scale`, `copies`,
`perPage` or `margin`, and a `margin` outside the `int` range, are refused; `fontSize` and `margin`
take fractional numbers.

`insert_rows`, `delete_rows` and `copy_rows` work in whole rows. `insert_rows` puts `count` rows
before `row` (the last row + 1 appends) and shifts everything from that row down - the rows with
their cells and notes, the merges and whole-row merges, the named areas, the row groups, the
drawings, the print and repeat areas, the declared height and the saved view rows; a merge, an area,
a group or a drawing the point falls inside grows instead of moving. `formatFrom` says where the new
rows take the row format and the cell formats from - above (the default, and none at row 1), below
or none - and text, parameters and details are never copied. `delete_rows` removes `count` rows from
`row`; merges, named areas and drawings lying entirely inside the range go with them and are named in
the answer, partial overlaps shrink. `copy_rows` replaces the `count` rows at `toRow` with a copy of
the rows at `fromRow` - row format, cells with text, parameter, detail and format, notes, and the
merges fully inside the source repeated over the target while the merges fully inside the replaced
target come off first. There is no shift, an overlap of the two ranges is refused, named areas are
neither copied nor moved, and the target may run past the current end. The answer carries
`shiftedRows`, `resizedMerges`, `removedMerges`, `resizedNamedAreas`, `removedNamedAreas`,
`removedDrawings` and `lastRow`.

`insert_columns`, `delete_columns` and `copy_columns` work in whole columns. `insert_columns` puts
`count` columns before `col` (the last column + 1 appends) and shifts everything from that column
right - the cells of every row with their notes, the column sets with their declared size (a set
shared by several rows shifts once), the merges and whole-column merges, the named areas, the
column groups, the drawings, the print and repeat areas and the saved view columns; a merge, an area,
a group or a drawing the point falls inside grows instead of moving. `formatFrom` says where the new
columns take the column format and the cell formats from - left (the default, and none at column 1),
right or none - and text, parameters and details are never copied. `delete_columns` removes `count`
columns from `col`; merges, named areas and drawings lying entirely inside the range go with them and
are named in the answer, partial overlaps shrink. `copy_columns` replaces the `count` columns at
`toCol` with a copy of the columns at `fromCol` - the column width and format, the cells with text,
parameter, detail and format, the notes, and the merges fully inside the source repeated over the
target while the merges fully inside the replaced target come off first. There is no shift, an overlap
of the two ranges is refused, named areas are neither copied nor moved, and the target may run past
the current end. The answer carries `shiftedColumns`, `resizedMerges`, `removedMerges`,
`resizedNamedAreas`, `removedNamedAreas`, `removedDrawings` and `lastColumn`.

A write while the project is not ready is refused with the same readiness sentence as `update_database`
and `validate_query`, and the file is left as it is. A read is refused with that sentence while the
project is building, while its build state cannot be determined, and while the project is closed. A write whose model has no rows, no column set and
no drawings, against a `Template.mxlx` that is not the empty skeleton (the bytes a new template writes,
or that skeleton with `<indexTo>1</indexTo>` right after `<index>0</index>`), is refused and the file
stays byte for byte; a read names the mismatch as `templateModelFileMismatch`.

A picture reference that does not resolve in the project refuses the write before anything
changes - the document and `Template.mxlx` stay as they were, and `dryRun` answers with the same
refusal - naming the count and the first names. An empty picture placeholder does not refuse the
write. A document with no column set receives a column set of size 0 before the save, the same
shape as an empty template.

`create_template` with `ownerFqn=CommonTemplate.<Name>` and the same `templateName` creates that common
template as a spreadsheet and an empty `Template.mxlx`. An existing common template is not overwritten.
A different `templateName` is refused before the model is opened.
