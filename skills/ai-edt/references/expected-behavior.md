# Expected behavior and response signals

None of this is a defect list. These are responses the server returns deliberately and behaviors
of EDT at scale. Each one has a right reaction, and retrying blindly is never it. Read the tag and
the message before deciding.

## Signals in a response

| Signal | What it means | What to do |
|---|---|---|
| `Pending` with a `runKey` | A long operation (references on a large object, a test run, an export) did not finish inside the soft budget. | Repeat the same call with the same `runKey` and the same parameters. Changing the filters starts a different search. |
| A timeout on a large configuration | The search had no filter, or the index is still building. | Narrow it: `metadataType` or `fileMask`. For references, `skipBsl=true` or a larger `timeoutSeconds` with a retry. |
| `BSL model is not available` | Either the semantic model is not built, or the module path or FQN is wrong. | Check the path first. A wrong path produces exactly this message, and the model itself works on very large modules. |
| `propertyMismatch` with `mismatches` | The object already exists and its properties differ. It is a refusal to overwrite silently, not a failure. | Do not retry the creation. Walk `mismatches` and set each property. |
| `requiresCascadeForms` with `affectedForms` | Removing the field would change forms. | Review the listed forms, confirm the destructive step, repeat with `cascadeForms=true`. |
| Names ending in `ApiNotFound`, or `dcsFactoryMethodNotFound` | The installed EDT runtime does not expose the API this operation needs. | Not an implementation defect and not fixable by retrying. Tell the user and offer the equivalent action in the EDT interface. |
| `kindMismatch` on an export | The output path does not match the object kind. | Match `.epf` and `.erf` to the object. |
| A tool is disabled or not found | The active preset hides it, or the build does not have it. | Do not work around it. Say which preset change or which build would provide it. |
| An infobase operation refused, and the answer names who holds the base | The runtime clients this EDT launched, and the other AI-EDT servers on this machine with the project open. | Close what it names. An empty list means none that this server can see - a client started from a shortcut, or a Designer opened by hand, is invisible to it and is never "nobody". |
| A removal answered that it removed nothing, naming another operation | The name is not a form item. It is a form, a metadata object or a template, and each has its own operation. | Call the operation the refusal names. Repeating `remove_item` cannot succeed. |
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

## Synchronization between EDT and the infobase

EDT decides between an incremental and a full upload by comparing the project configuration
against a stored baseline. If they do not match, or no baseline exists, the upload is full.

`sync_control` operations `status` and `diagnose` are read-only and safe. `suppress` gates only the
background automatic synchronization, and is reversible; explicit actions such as a manual database
update are not gated by it.

**`mark_synchronized` and `reseed_baseline` only on an explicit instruction from the user, never on
your own initiative.** Both make EDT believe the infobase already matches the project. If a real
difference exists, EDT will silently skip genuine changes. Only the user knows what has not
changed.

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
