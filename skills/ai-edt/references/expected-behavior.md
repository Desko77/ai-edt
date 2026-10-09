# Expected behavior and response signals

None of this is a defect list. These are responses the server returns deliberately and behaviors
of EDT at scale. Each one has a right reaction, and retrying blindly is never it. Read the tag and
the message before deciding.

## Signals in a response

| Signal | What it means | What to do |
|---|---|---|
| `Pending` with a `runKey` | A long operation (references on a large object, a test run, an export) did not finish inside the soft budget. A facade operation that leads to a soft-wait tool answers the same way, and it and the direct call with the same arguments share one `runKey`. | Repeat the same call with the same `runKey` and the same parameters. Changing the filters starts a different search. |
| A timeout on a large configuration | The search had no filter, or the index is still building. | Narrow it: `metadataType` or `fileMask`. For references, `skipBsl=true` or a larger `timeoutSeconds` with a retry. |
| `BSL model is not available` | Either the semantic model is not built, or the module path or FQN is wrong. | Check the path first. A wrong path produces exactly this message, and the model itself works on very large modules. |
| `propertyMismatch` with `mismatches` | The object already exists and its properties differ. It is a refusal to overwrite silently, not a failure. | Do not retry the creation. Walk `mismatches` and set each property. |
| `requiresCascadeForms` with `affectedForms` | Removing the field would change forms. | Review the listed forms, confirm the destructive step, repeat with `cascadeForms=true`. |
| Names ending in `ApiNotFound`, or `dcsFactoryMethodNotFound` | The installed EDT runtime does not expose the API this operation needs. | Not an implementation defect and not fixable by retrying. Tell the user and offer the equivalent action in the EDT interface. |
| `kindMismatch` on an export | The output path does not match the object kind. | Match `.epf` and `.erf` to the object. |
| A tool is disabled or not found | The active preset hides it, or the build does not have it. | Do not work around it. Say which preset change or which build would provide it. |
| An infobase operation refused, and the answer names who holds the base | The runtime clients this EDT launched, and the other AI-EDT servers on this machine with the project open. | Close what it names. An empty list means none that this server can see - a client started from a shortcut, or a Designer opened by hand, is invisible to it and is never "nobody". |
| A removal answered that it removed nothing, naming another operation | The name is not a form item. It is a form, a metadata object or a template, and each has its own operation. | Call the operation the refusal names. Repeating `remove_item` cannot succeed. |
| A data-path refusal on an extension form, naming the element, the path and a reason | Both halves came out against the path: EDT does not export it with the extension form, and the form does not resolve it. Nothing was written. | Read the reason. An unborrowed attribute: the borrow of the form attribute ran inside the refused write and was rolled back with it - fix the cause the reason names and repeat the call; `extension_workshop operation=borrow_object` borrows a metadata object and does not redo that borrow. A path the form does not resolve: bind to an attribute the extension can carry, or drop the path. Repeating the same call cannot succeed. |
| A data path bound on an extension form answered success | The path was written. If the form resolves every segment but the extension cannot reference the whole path - a segment of another engine, a tail past the borrowed attribute - EDT can drop it on export, and no marker reports it: `form-data-path` fires only on a segment nothing resolves. | Prefer an attribute the extension owns for a path that must survive export; treat a binding to a longer path as unverified on the platform. |
| `dataPathChecksNotPerformed` in the answer of a form write | The runtime could not be asked one of the data-path questions - the EDT service is absent or the call failed. The write stands with the question unanswered, and nothing is refused. | Treat the path as unverified, not as checked. Names are `attributeBelongsToExtension`, `exportOfExtensionForm`, `pathResolutionInForm`. The key is absent when every check ran. |
| A data composition write refused, naming what it could not set | The property or the target was not written. A write that does not land is refused rather than reported as done. | Fix the name or the path the refusal names. Do not treat the schema as changed. |

Tools correct callers on their own: a wrong enumeration value comes back with the valid ones, a
missing required parameter comes back with an example. Read the message instead of guessing the
next attempt.

## Stopping instead of looping

One editing cycle is one object or one module until it validates. After two failed attempts at the
same error with the same approach, change the approach. If no other approach is visible, stop and
ask. Stopping applies to that approach, not to the task: finish the independent parts and say what
is left.

## Large configurations

- A project-wide search with no filter times out. Narrow it by `metadataType` or `fileMask` every
  time.
- Reach for `get_module_structure` and `read_method_source` first. They work on modules of tens of
  thousands of lines and return exact method boundaries. Fall back to reading raw files only on an
  actual model failure, not preemptively because a module looks big.
- Line numbers from a text search mark the matching line, not the method boundary. Take boundaries
  from `read_method_source`.
- `get_module_structure` counts a region's bounds from the `#Область` / `#КонецОбласти` pair in the
  text and in the model: the end is the closing directive's line, an unclosed region ends at the
  module's last line, and regions are listed by increasing start line. Its `Params` column reads a
  signature written over several lines and keeps the brackets of default values. `Экспорт` after a
  parameter list is matched without regard to case here and in `read_method_source`.

## Synchronization between EDT and the infobase

EDT decides between an incremental and a full upload by comparing the project configuration
against a stored baseline. If they do not match, or no baseline exists, the upload is full. EDT
2026 keeps the baseline inside the workspace, in the project's private working location
(`.metadata/.plugins/org.eclipse.core.resources/.projects/<project>/com._1c.g5.v8.dt.platform.services.core/ib-sync/ss/<infobase>/`);
older EDT kept it under `%APPDATA%/.1cedt/ib-sync/ss`. `sync_control` reads both, the workspace
store first, and each baseline in `status` names its `store`.

`sync_control` operations `status` and `diagnose` are read-only and safe. `suppress` gates only the
background automatic synchronization, and is reversible; explicit actions such as a manual database
update are not gated by it.

**`mark_synchronized` and `reseed_baseline` only on an explicit instruction from the user, never on
your own initiative.** Both make EDT believe the infobase already matches the project. If a real
difference exists, EDT will silently skip genuine changes. Only the user knows what has not
changed.

## An update that would delete data

`update_database` compares the infobase's synchronization baseline with the model before it starts
anything (`protectData`, on by default): the manifest of the last synchronization (`ConfigDumpInfo.xml`),
one record per entity the base holds, matched against the model by `uuid`. An entity that carries
data and is gone from the model means the restructure would drop its table, and the call is refused
with `status=confirmationRequired` and `nothingStarted=true` - no update, no claim on the infobase,
no client stopped. The answer names the addresses in `dataLossTables` (`Catalog.X`,
`Catalog.X.Attribute.Y`), their number in `dataLossCount`, the baseline file in `dataLossFile` and
what was compared in `dataLossCheck`. A rename keeps the uuid and is not a deletion. A base with no
baseline file, or a model that cannot be read whole, is not compared: `dataLossCheck` says so and
the update is not stopped.

**`acceptDataLoss=true` is the user's decision, not yours.** Passing it is the one way to carry the
deletion through, and only what this comparison found is accepted, for that call alone. Show the
user `dataLossTables` and let them decide; a silent restructure is exactly what losing the table
and its data looks like from their side.

`infobase_admin operation=inspect_database_sync` answers the same comparison without starting any
update: its `dataLossProtection` block carries `pendingDataLoss`, `pendingDataLossCount`,
`dataLossCheck`, `baseline` and `nextStep`, beside `dataLossCompared`, `nextUpdateProtectsData` and
`acceptDataLossDefault=false`. Read it when the decision is still open and an update has not been
attempted.

## Before a merge, and the way back from it

`insights operation=compare_three_way` with an intent other than a report records a restore point
of the PROJECT FILES before the merge begins and answers `mergeRestorePoint` and
`mergeRestoreNote`. A point that cannot be taken stops the merge before it starts
(`mergeStarted: false`) - read that as nothing having happened, not as a partial merge.
`git operation=create_merge_restore_point` takes such a point on demand, and
`git operation=restore_merge_point` puts those files back: the answer lists `restoredFiles`,
`restoredCount`, `unchangedFiles` and `removedFiles` - project files the point does not hold are
removed, and a file whose bytes already match the point is not rewritten. A restore that stops
halfway still removes the extras and refreshes the workspace, and names what it did not put back in
`unrestoredFiles`; an extra that could not be deleted is named with its reason in `cleanupFailures`
and a failed workspace refresh in `refreshFailure`, and either makes the answer an error.
`git operation=delete_merge_restore_point` drops a recorded point by its `pointId` - required, and
only a point of this project - without touching any project file; a ref or a copy directory that
could not be deleted is an error, and the index entry is kept so the point can be deleted again.

**The point and the restore cover project files only. The infobase is not copied and is not rolled
back.** A merge that reached the base has to be undone in the base by other means; a restore that
put the files back does not put the data back.

## Things that fail early by design

- In an extension project, a common module created with `privileged=true`, or with `global=true`
  combined with `server=true`, is rejected up front rather than producing an invalid module.
- An event subscription handler must be `CommonModule.Name.Method` or `Name.Method`. A bare method
  name cannot be resolved.
- Deleting an XDTO package by FQN is not supported; remove it through the file system.

`edit_metadata create_object` takes a `properties` object of property name to value, applied to the
new object before it joins the configuration - `{"methodName": "CommonModule.A.B", "use": false}` on
a `ScheduledJob`, for one. A property the object's type does not have is refused and **nothing is
created**. A property given both there and as its own argument must carry the same value. Setting
properties this way is one call; creating and then setting them one at a time is several, and the
object exists in between.

## Validation that looks wrong but is stale

After creating a project and populating it, markers about unknown `String` or `Number` types are
leftover derived state, not type errors. Revalidation does not clear them. `clean_project` does.
Checking a freshly built project without a clean pass tells you very little.

## Support snapshots and the limit

A merge leaves a support snapshot in the project's `.settings`. Ordinary ones past the limit are
removed when the server starts, so they do not accumulate. A snapshot from a merge whose outcome
nobody has established is protected and stays: only a person can establish what a merge left
behind. `sync_control operation=release_support_snapshot name=<file>` takes the protection off one
snapshot, after which the limit applies to it. Deliberate, and one at a time.

## An object the support registry will not let you change

A write into an object whose support entry closes it (`ChangesNotAllowed`, and the environment
agrees) is refused before the transaction opens - in `edit_metadata`, in the form operations and in
`write_module_source`. The refusal names the object's FQN, the mode it is set to, `canEdit` and how
the protection is lifted, and carries the tag `supportLock`. A nested subsystem
(`Subsystem.A.Subsystem.B`) is checked by its own support entry. Reading operations
(`get_form_structure`, `list_named_areas`, `read_template`, `check_print_width` and the like) do not
ask about support.

**Lifting support protection is the user's decision.** Do not change a support mode to get a write
through. Report the refusal and what it names, and let the user say whether the object may be
changed.

## An answer that is part of the work says so

Eight scans stop when the call is withdrawn, at a boundary that leaves what they have gathered
whole: `find_dead_code`, `detect_query_anti_patterns`, `sensitive_data_scan`,
`find_rls_violations`, `project_metrics`, `dependency_graph`, `semantic_metadata_search`,
`find_references`. Such an answer carries a `cancelled` field naming how far it got - "cancelled by
the operator after 2304 files" - and everything beside it is a part of the work, not all of it.

Read that field before drawing anything from the result. An empty list under it means the scan
stopped, not that there is nothing to find, and these tools say as much in words: "Nothing had been
found when the scan stopped", never "No violations".

`project_metrics` is the one to read most carefully, because its answer is counts rather than
findings. When it stops it reports `partial=true` and lists `unscannedModules`, every count in it is
a floor, and the sections whose step never ran - `objects`, `errors`, `forms` - are ABSENT rather
than zero. A missing section is the answer to "was this measured", and `tests.yaxunitDetected`
appears only when the module scan finished or a test module was actually found.

A withdrawal comes from the client as `notifications/cancelled` naming the call's `requestId`, or
from the person at the status bar. Either way the tool is not interrupted mid-unit.

A cancelled task turns `cancelled`, and its `statusMessage` says what stopping came to: the work
stopped; it was told to stop and had not ("It may still be running and still writing"); or only the
waiting stopped, because a Designer-mode process cannot be interrupted once started. The run key is held until the body of the call finishes, so the same
work asked for again inside that window answers `stillStopping: true` instead of starting a second
run - that is the cancel being allowed to finish, not a failure to act on. `get_tasks` names the
closed projects it did not read.

## A dependency graph walks from the root its selector named

`insights operation=dependency_graph` takes its root from `scope` or, with no scope asked, from
the selector: `objectFqn` an object, `moduleFqn` a module, `subsystemName` a subsystem, and
nothing the whole project. A selector under `scope=project`, a selector the scope does not
take, and two selectors together are refused by name - never dropped in silence, which used to
walk the whole project and read as thousands of nodes with no edges. A root that does not exist
answers with the `rootNotFound` tag. The seed of the walk fits
`maxNodes` like the walk itself: more roots than the cap are cut, the answer says
`truncated=true`, and the edges of the roots it took are in it. The module level resolves its
modules through the project files; a module whose file is there and whose model did not load is
named in `modulesUnloaded` and `modulesUnloadedNames`, and a module level with not one loaded
module is a refusal, not an empty graph.

## A metadata dependency graph names metadata

`insights operation=dependency_graph` keeps what its level is about: `metadata` carries metadata
objects, `mixed` carries metadata objects and BSL modules. An end of that kind is required on BOTH
ends of an edge, as the source and as the target: an EDT-internal end is dropped at whichever end
reports it (`internalEdgesDropped` when any were, counted once per edge; on `metadata` an edge with
a BSL module is dropped the same way). A root of a kind the level does not carry is no node either,
and `internalRootsDropped` names it. Repeated edges of the same `from`, `to` and `via` are one edge,
and `count` is the number of references between that pair - one reference met from both sides stays
one - and is written when it is greater than 1. `edgeKinds` keeps only the named `via` values; a kind
the walk never saw is `unmatchedKinds`, not a refusal. On `level=modules` the argument is not applied
(`edgeKinds: notApplied`) and `calls` edges merge the same way.

On `level=modules` an incoming edge (`direction=in`) is any call the reference index holds, and an outgoing edge (`direction=out`) is a call written `CommonModuleName.Method(...)`: calls through a manager, an object or a variable are not outgoing edges. With `direction=both` the incoming edges are collected first and may fill `maxNodes` before the outgoing ones.

## Cancelling an update that never gave you a runKey

`update_database` can be cancelled without one, addressed by `projectName` instead: that is what
names the run when the call that failed never returned a key. Without `projectName` the cancel is
refused rather than guessing which run was meant. The answer reports how many runs stopped being
tracked, and names no infobase holding - runs are keyed by project and holdings by infobase, and
nothing ties the two. `MonopolyLock.outstandingHere` is what reports holdings.

## The thick client competes for the infobase

While EDT holds a file infobase, launching a batch Configurator against that same infobase blocks
and hangs. That is a lock, not a fault. Operations that need the thick client are exposed as
tools - `update_database`, the configuration and extension exports, external object export - and
they hand the lock over correctly. Use them instead of starting a Configurator yourself.
