# AI-EDT - provenance of independently authored code

This document records how individual source files of AI-EDT came to exist. It exists because AI-EDT
started as a fork of [EDT-MCP](https://github.com/DitriXNew/EDT-MCP) (Copyright (C) DitriX, AGPL-3.0)
and is being progressively de-derived: every file inherited from upstream is, over time, replaced by an
independently authored implementation.

It is a factual record, not a legal opinion. No licence conclusion should be drawn from it without
counsel. In particular:

- **The whole work remains AGPL-3.0-or-later for as long as any upstream-derived file ships**, and every
  upstream copyright notice is preserved in the files that still carry it. That is a licence obligation,
  not a choice.
- Rewriting a file does not retroactively change the licence of the product. Only a state in which **no
  upstream-derived code remains** could make a licence change discussable, and that discussion belongs to
  a lawyer, not to this file.

## How a file is classified

Not by its copyright header. The header turned out to be unreliable: 18 files written in this fork
carry an upstream header because the header was copied from a neighbouring file when they were created
(see "Mislabelled files" below).

A file is upstream-derived if and only if **it existed in the upstream tree at the fork point**
(`e28560f`, 2026-04-19). That is checkable:

```
git ls-tree -r --name-only e28560f | grep '\.java$'
```

## Status

Counted with the rule above (`.java` files present in the tree at `e28560f`), not by header.

| Metric | Value | As of |
|---|---|---|
| Existed at the fork point (upstream-derived) | 183 files (154 plugin + 29 unit tests) | 2026-07-14 |
| Of those, deleted outright (dead code / retired feature) | 6 files | 2026-07-14 |
| Of those, reimplemented independently | 177 files (waves 1a-1c, 2a-2c, 3a-3b, tools/impl helpers + batches 1-2 + batch-4 validate_query+revalidate+clean_project + batch-5 breakpoint trio + inspect/profiling quartet + debug-control quartet + debug_yaxunit_tests + debug_launch + get_tags/get_objects_by_tags + run_yaxunit_tests + get_platform_documentation + add_metadata_attribute + get_form_screenshot + delete_metadata_object + update_database + write_module_source + rename_metadata_object + find_references + the 29 unit tests) | 2026-07-16 |
| Still upstream-derived | **0 files** (0 plugin + 0 unit tests) | 2026-07-16 |
| Written in this fork after the fork point | 117 files | 2026-07-14 |

No upstream-derived code remains anywhere in the reactor: the HTTP transport, the MCP/JSON-RPC
layer, the OSGi activation and startup, the tool interface and registry, all ~50 tool
implementations, the preferences/Navigator UI, the tags/groups subsystems, and all 29 unit tests
have been reimplemented independently in this fork.

The 29 inherited unit tests were counted as upstream-derived deliberately - they are source like
any other file and ship in the reactor - and they have now been rewritten clean-room from the
(ours) SUT contracts (commit c99f586, 2026-07-16).

## Corrected copyright notices (our authorship, upstream header)

18 files written in this fork after the fork point carried an **upstream copyright header**. The header
was copied from a neighbouring file when they were created; it attributes our work to someone else. The
notices were corrected to ours.

This was not done in bulk on a hunch. Each file was checked twice:

1. **git history** - the file did not exist in the upstream tree at the fork point (`e28560f`).
2. **content** - its substantive lines (over 45 characters, excluding comments and imports) were
   compared against every substantive line of the entire upstream tree.

The overlap is confined to what any tool in this plugin is forced to write: calls into our own
framework (`JsonUtils.extractStringArgument`, the `IMcpTool.execute(Map<String,String>)` signature),
Eclipse boilerplate (`ResourcesPlugin.getWorkspace()...`), schema-builder lines, and trivial error
strings. That is unavoidable idiom, not protected expression. `BmFormHelper` (3 719 lines) - previously
counted as the single largest upstream file - shares **zero** substantive lines with the upstream tree.

**Stated plainly, because it matters:** two of the eighteen, `AiContextTool` (48 of 225 substantive
lines shared) and `DcsSearchTool` (20 of 68), were clearly written by following the shape of an existing
tool. What they share is our own API, the tool interface, standard error messages and limit constants -
their expression is their own - but the *pattern* was taken from a neighbour. Flagged here rather than
buried, for counsel to weigh.

| File | Lines | Substantive | Shared with upstream |
|---|---|---|---|
| BmFormHelper | 3 719 | 700 | 3 |
| EditFormTool | 1 395 | 352 | 10 |
| AiContextTool | 1 151 | 225 | **48** |
| ValidateForExportTool | 1 019 | 220 | 5 |
| BmHelpHelper | 549 | 84 | 0 |
| ExportCommonPictureTool | 496 | 106 | 5 |
| CodeSearchTool | 314 | 119 | 2 |
| DcsSearchTool | 304 | 68 | **20** |
| SessionChangeTracker | 273 | 26 | 0 |
| LaunchDebuggerTool | 255 | 95 | 1 |
| TextSuggest | 228 | 22 | 0 |
| StandardCommandRegistry | 208 | 30 | 0 |
| ProjectScopeResolver | 207 | 25 | 0 |
| ProjectResolver | 206 | 25 | 1 |
| FormEventRegistry | 193 | 64 | 0 |
| LocalizedStringUtils | 186 | 15 | 0 |
| PictureValidator | 174 | 29 | 3 |
| ExternalProjectResolver | 155 | 16 | 2 |

These files were never upstream, so they are not part of the de-derivation backlog either.

## Method used for reimplementation

Files are reimplemented under an information barrier, in two roles held by two separate agents with
separate contexts:

1. **Specifier.** Reads the existing implementation and writes a *behavioral specification*: the
   wire-level contract (protocol framing, field names, error codes), the public API that dependent code
   compiles against, observable edge cases, and any compatibility quirk that must be preserved (with the
   reason). The specification contains no code, no private structure, and no phrasing carried over from
   the source.
2. **Implementer.** Is denied access to the original sources and writes the new implementation from the
   specification plus public material only: published standards (JSON-RPC 2.0, Model Context Protocol)
   and public APIs (JDK, Gson, OSGi/Eclipse, 1C:EDT SDK). The implementer writes into a separate tree, so
   that even overwriting a file never requires opening the old one.

Public API signatures, package names and class names were deliberately preserved during the clean-room
waves, because dependent code compiles against them. What was rewritten there is the expression - the
implementation itself. (Class and identifier naming was subsequently changed in the structural
de-derivation pass; see "Structural de-derivation" below. The wire contract - MCP tool names, operation
names, JSON parameter keys - is still preserved, by design.)

**Known limitation, stated plainly:** the barrier here is between two AI agents with separate contexts,
not between two teams of people. Classic clean-room practice assumes the latter. This distinction is
material and is the thing to put in front of counsel; it is not glossed over here.

## Reimplemented files

Each entry records what was rewritten, from what, and where the specification lives.

### Wave 1a - protocol core (12 files, 2026-07-14)

`protocol/{McpConstants, GsonProvider, JsonUtils, JsonSchemaBuilder, ToolResult, McpProtocolHandler}`
and `protocol/jsonrpc/{JsonRpcRequest, JsonRpcResponse, JsonRpcError, InitializeResult, ToolCallResult,
ToolsListResult}`.

- **Written from:** a behavioral specification (`phase3-core-protocol-spec.md`, 907 lines) plus published
  standards - JSON-RPC 2.0, Model Context Protocol - and public APIs (JDK 17, Gson 2.10.1, OSGi/Eclipse).
- **Barrier:** the implementer was denied the original sources and confirmed never having opened them. He
  wrote into a separate tree; the orchestrator moved the files into place, so overwriting never required
  reading the old code.
- **Verification:** the project builds with the new core (269 files compiled, no errors - i.e. the public
  API surface the other 107 tools compile against is intact); the running server was exercised end to end
  (handshake, tools/list, tools/call, embedded resource, id normalisation, error codes, numeric-argument
  handling).
- **Independent review** compared new against original on three axes and returned **INDEPENDENT** on the
  legal axis. Cited evidence: the new code deduplicates logic the original repeated (six response
  builders collapsed to two; three parallel numeric extractors factored into one), carries its own
  wording where the specification left wording free, applies a uniform authorial style (final fields,
  constructor injection) the original does not, and fixes inefficiencies the original had. One
  convergent idiom was examined and attributed to the specification being unusually operational at that
  point, not to copying.
- Two wire-visible deltas the review found (signal handling under plain-text mode) were corrected back to
  the original observable behavior: de-derivation must not change what clients see.

### Wave 1b - server framework (6 files, 2026-07-14)

`McpServer`, `Activator`, `UserSignal`, `ActiveToolCall`, `tools/IMcpTool`, `tools/McpToolRegistry`.

- **Written from:** a behavioral specification (`phase3-server-framework-spec.md`, 1395 lines) plus public
  APIs (JDK 17 `com.sun.net.httpserver`, OSGi/Equinox, Eclipse Platform, the 1C:EDT SDK). The spec had to
  state, as fact, what an implementer cannot invent: the 107 tool classes in registration order, the 26
  tracked EDT services, header names, status codes.
- **Barrier:** same as wave 1a. The implementer never opened the originals; he verified his work by
  compiling his six files together with the other 263 files of the bundle against the real target
  platform (javac, exit 0), which proves the API surface the rest of the plugin depends on is intact.
- **Verification:** Tycho build green; installed; **the server starts and serves** - handshake, 107
  tools, real tool calls against several different EDT service trackers (a missed tracker would NPE at
  runtime and the compiler would not catch it), SSE heartbeat, method gating, `/health`.
- **Independent review**: **INDEPENDENT** on the legal axis, and 26/26 trackers plus 107/107
  registrations reconciled against the originals. Cited evidence of independence: the new code
  decomposes into many small named methods where the original inlines the same logic in a few large
  ones; its method vocabulary follows the specification's language (`admit`, `deliver`, `authorize`)
  rather than the original's (`addCorsHeaders`, `checkAuth`); it uses different concurrency primitives
  for identical semantics; and it does not reproduce the original's reference to a tool that does not
  exist.
- Deltas found by the review were corrected back to the original behavior (CORS on a `/health`
  preflight; `close()` in a `finally` on the two rejection paths). Three deltas were accepted
  deliberately and are recorded here: the user-signal consume is now atomic; a dead code path was not
  carried over; and `DELETE` now answers with `Content-Length: 0` instead of the original's accidental
  chunked empty body (the original passed `0`, which means "chunked" in this API).

### Wave 1c - startup hook, and retirement of the update-check feature (2026-07-14)

`McpServerStartup` was reimplemented; `UpdateChecker` (372 lines) and `ui/ReleaseNotesDialog` (109)
were deleted rather than reimplemented.

- **Why deleted, not rewritten.** The feature polled the upstream author's GitHub for new releases. Its
  poll URL was emptied during the rebrand, and both entry points - the scheduler and the "Check now"
  button - return before they ever build a request, so the feature has been a no-op that cannot fire.
  Rewriting it would have faithfully reproduced nothing. Delivery is already handled by p2 and
  `scripts/edt-selfupdate.ps1`, so nothing is lost. Its only remaining effect was a user-visible defect:
  "Check now" set the label to "Checking..." and then blanked it forever.
- **What users lose:** the (inert) update-interval combo and "Check now" button in the preference page,
  and a permanently disabled "No updates available" item in the status-bar menu. Nothing that worked.
- **The preference key `mcpUpdateCheckInterval` is retired** with the feature; it was written by the
  preference page and read by nobody.
- **`McpServerStartup` written from:** the behavioral specification `phase3-framework-tails-spec.md`,
  plus the public Eclipse `org.eclipse.ui.IStartup` API. Same barrier as the earlier waves: the
  implementer was denied the original and wrote into a separate tree.

### Update checking returns, on a different mechanism (2026-08-03)

The capability deleted above was rebuilt from scratch as the `upkeep` package. It is worth recording
precisely what was and was not reused, because "the feature came back" invites the assumption that the
code did too.

- **A different source of truth.** The deleted implementation asked GitHub's releases API. This one
  reads the metadata of a p2 update site and compares the installed version as the profile records it.
  Nothing about the new path resembles an HTTP call to a release API, and the feature no longer depends
  on GitHub at all: the site may be Pages, a corporate mirror or a local directory.
- **A capability the original never had.** The deleted code only notified. This one also installs,
  through p2 provisioning, which is a mechanism the upstream implementation does not contain a single
  line of - verified against `origin/master` (v2.9.1): no `org.eclipse.equinox.p2.*` import in its
  manifest and no use of `UpdateOperation`, `IProvisioningAgent` or `UIServices` anywhere in its source.
- **Names deliberately disjoint.** `UpdateChecker`, `ReleaseNotesDialog` and the key
  `mcpUpdateCheckInterval` stay retired and are not reused under any spelling. The new vocabulary is
  "upkeep" rather than "update" throughout - package `upkeep`, tool `self_upkeep`, keys `mcpUpkeep*`,
  classes `ReleaseFeed` / `ReleaseOffer` / `ReleaseSweep` / `ReleaseAdoption` / `UpkeepLedger` /
  `UpkeepPolicy` - so that a returning capability does not quietly regrow a shared naming surface.
- **No release-notes dialog.** The one proactive window this feature raises states two version numbers
  and offers to install; reproducing the shape of the deleted dialog was avoided on purpose.
- **Trust is unresolved and is documented as such.** The artifacts are unsigned, and every mechanism
  that would silence p2's trust prompt from inside the plugin was examined and rejected for changing
  provisioning behaviour beyond this feature's own operation - one of them, the engine's trust-always
  flag, is a persisted profile setting that a crash mid-install would leave switched on for good. So
  an install reaches EDT's ordinary trust prompt and waits for a person, and the route that works
  today is the status bar, where somebody is looking. The limitation is stated in the tool's own
  description rather than worked around.
- **Signing the releases was considered and deliberately deferred.** It would buy exactly one thing -
  installs proceeding with nobody at the keyboard - at the cost of key custody, a signing step in the
  release workflow, rotation, and a new failure mode in which a leaked key produces artifacts that
  users' IDEs accept without asking. Nothing in this code depends on the outcome: the trust decision
  belongs to p2 and to the IDE, so signing can be added later without touching the feature. An IDE
  owner who wants unattended installs before then can set `eclipse.p2.unsignedPolicy=allow` in
  `eclipse.ini` - a launch-time framework property this plugin cannot set and does not ask for.
- **Verification:** the server auto-starts. That is the whole proof - nothing else starts it at launch,
  so a broken startup hook means a dead server. `/health` answers and `tools/list` returns the same 107
  tools as before the change.

### Wave 2a - core utilities (8 files, 2 205 lines, 2026-07-14)

`utils/{MetadataTypeUtils, LifecycleWaiter, ProjectStateChecker, FrontMatter, BuildUtils,
ReflectionUtils, MetadataPathResolver, MarkdownUtils}` - the layer nearly every tool sits on: the
metadata type table, the project-state gates every tool call passes through, path resolution, and the
response front-matter.

- **Written from:** a behavioral specification (`phase3-utils-2a-spec.md`, 1 149 lines) plus public APIs
  (JDK 17, Eclipse Platform, EMF, the 1C:EDT SDK). Same barrier: the implementer was denied the
  originals and wrote into a separate tree.
- **The implementer verified his own work before handing it over**, which is now the standard for this
  project: compiled with `javac -Xlint:all` against the real EDT jars (0 errors, 0 warnings); ran a
  744-assertion harness replaying every example the specification pins (744/744); and reconciled the
  50-row metadata-type table *back out of the compiled class* against the specification table (50/50
  identical, including the four mixed-script aliases).
- **Equivalence was proved, not asserted.** Before the rewrite, the live output of `get_metadata_details`
  was captured for seven cases (a catalog, a document with tabular sections and forms, a common module,
  a subsystem, a brief request, a multi-object request, a not-found error). After the rewrite - with the
  formatters untouched and this entire utility layer replaced underneath them - the output was
  **byte-identical on all seven**. The `FrontMatter` round trip was checked separately, because its
  output is parsed back into JSON by the form operations: a one-byte drift there would have failed every
  form operation with -32603.

Two behavioral differences from the original were found by the review and are recorded rather than
buried, because "clean-room equivalent" has to mean what it says:

- `findSimilarObjects` now skips a metadata object whose name is `null`; the original called
  `getName().toLowerCase()` on it and threw an NPE out of the tool. The new behavior is better and it
  is still a difference.
- `findSimilarObjects` with `maxResults <= 0` now returns nothing; the original always returned one
  result (it appended before it checked the limit). No caller passes a non-positive limit.

### Wave 2b - debug, test-runner and editor utilities (8 files, 3 587 lines, 2026-07-14)

`utils/{BreakpointUtils, DebugSessionRegistry, EditorScreenshotHelper, LaunchConfigUtils,
VariableSerializer, JUnitXmlParser, JUnitTestResults, JUnitMarkdownFormatter}`.

- **Written from:** `phase3-utils-2b-spec.md` (1 405 lines), whose reflective EDT names were verified
  against the real jars with `javap` rather than trusted from comments. Same barrier.
- **Verified by the implementer:** compiled against the real EDT jars; the two inherited contract
  tests compiled and run (12/12); and a signature probe in a different package that references every
  frozen member, to prove the public surface from outside.
- **Defects fixed rather than reproduced** (each was found by the specification and is listed there):
  the last-resort breakpoint marker id was wrong; `set_exception_breakpoint` swallowed setter failures
  and echoed the request back as if applied; the debug-event listener was never unregistered, so every
  update of the plugin left another listener attached and pinned the old classloader; `JUnitXmlParser`
  double-counted every figure in a report with nested suites; a `hitCondition` failure was reported by
  omission while every sibling option reported explicitly.

### Wave 2c - metadata formatters (5 files in, 4 out, 2026-07-14)

`tools/metadata/*` - the code that turns an EDT metadata object into the markdown an agent reads.

- **Written from:** `phase3-metadata-formatters-spec.md` (1 068 lines). The output format is a wire
  contract, so the specification pins it byte-exactly.
- **The boundary shrank on purpose.** Exactly one member is called from outside the package
  (`MetadataFormatterRegistry.format(MdObject, boolean, String)`, from two tools), so the interface,
  the abstract base and the "registry" that registered nothing are gone; three of the four new classes
  are package-private, which makes the boundary structural instead of documentary.
- **Verified:** the live output of `get_metadata_details` was captured before the change for seven
  cases and compared after - byte-identical.
- **Defects fixed rather than reproduced:** a subsystem member with no name threw an NPE that a blanket
  catch swallowed, and the entire Subsystems section silently vanished from the response; a separator
  was written before its null check, so a null item produced `A, , B` on the wire; an exception
  mid-loop left a truncated markdown table in the agent's response and printed the stack trace to
  `System.err` instead of the log.

### Wave 3a - preferences, status bar, Navigator handlers (15 files, 4 091 lines, 2026-07-14)

`preferences/*` (minus `McpAuth`, which was written in this fork), `ui/{McpStatusContribution,
NavigatorToolbarCustomizer, UserSignalDialog}`, `handlers/*`.

- **Written from:** `phase3-preferences-ui-spec.md` (1 177 lines). The specification was written before a
  deliberate fix to the tool groups and presets landed, so it carries a CORRECTION block at the top with
  the corrected state; the implementer built that, not the stale body.
- **Wire contract held.** The preference keys persist in 23 live workspaces. All 11 key literals are
  identical between the old and new files - 0 removed, 0 added.
- **Verified:** compiled; the five workbench-free preference tests pass (75); and the 107 grouped tool
  names were reconciled against the live registry (0 ungrouped, 0 phantom, 107 distinct), which is what
  `ToolGroupCoverageTest` asserts.

### Wave 3b - the tags and groups Navigator subsystems (2026-07-14)

`tags/*` (22 files, 5 680 lines - one inherited file, the ~380-line dead `TagTrieStateProvider`, was
dropped rather than rewritten) and `groups/*` (24 files, 4 876 lines). Both let a user organise the
Navigator - by tagging metadata objects, or by filing them into folders - and both persist to a YAML
file in the project.

- **Written from:** `phase3-tags-spec.md` (799 lines) and `phase3-groups-spec.md` (419 lines). Same
  barrier: each implementer was denied its originals.
- **The YAML is a wire contract** - it sits in users' git repositories. Both were reproduced byte-exact
  and checked with a harness that dumps through the plugin's exact configuration: `metadata-tags.yaml`
  (assignments before tags, single-quoted colours, the empty `{ }`/`[ ]` forms) and `groups.yaml`
  (alphabetical keys, `description: null`, the two-line empty-children form), both UTF-8, no BOM, LF.
- **Verified:** both compile; `TagModelTest` + `TagDecorationUtilsTest` pass (59) and `GroupModelTest`
  passes (50), against the rewrites.
- **Security:** both loaders now refuse global YAML tags (`setTagInspector(tag -> false)`), carrying
  forward the fix in `b921427` - a crafted file arriving through a clone can no longer name a class for
  the parser to instantiate.
- **Defects fixed rather than reproduced:** each subsystem leaked a workspace/registry listener that
  compounded on every plugin update - both now have a `dispose()` wired into `Activator.stop()`; a
  corrupt or unknown-key YAML file threw out into the Navigator instead of degrading to empty; the
  groups orphan-cleanup ran `removeIf` on a defensive copy and did nothing; a `startsWith` path match
  over-matched sibling paths; several SWT images were never disposed. `TagTrieStateProvider` and a list
  of dead public methods were dropped outright.

**After 3a + 3b the whole preferences and Navigator UI is ours.** What remains inherited is the tools
themselves (`tools/impl`) and the unit tests.

## Files still inherited from upstream

Do not enumerate these by copyright header - the header is unreliable in both directions. Enumerate by
the fork point, which is the definition:

```
# plugin: fork-point files that still exist, minus the ones already reimplemented
git ls-tree -r --name-only e28560f -- mcp/bundles/ | grep '\.java$'
```

By area, as of 2026-07-16: **every** package is reimplemented - protocol, framework, utils, metadata
formatters, preferences, status bar, handlers, the tags and groups Navigator subsystems, the
whole of `tools/impl` (all 50 fork-point tools + the 2 helpers), and all 29 unit tests. The last six
tools/impl files landed in the 2026-07-16 session (get_form_screenshot, delete_metadata_object,
update_database, write_module_source, rename_metadata_object, find_references - each wire
byte-exact against a golden live-output snapshot, dual-reviewed), and the 29 unit tests followed in
the same session (commit c99f586) - clean-room from the ours SUT contracts, 0 method-name overlap
with the originals, 413 tests green on a standalone JUnit pass-oracle. **Zero upstream-derived
source remains**; AGPL-3.0 + upstream copyright notices are preserved until the licence-flip
decision is made with counsel.

## Structural de-derivation (surface identifiers, strings, GUI, docs)

The waves above rewrote the *expression* of each file - the implementation - while keeping the surface
names, because dependent code compiled against them. That left the product structurally recognisable as a
fork even though no file's body was upstream-derived any more: identical class filenames, byte-identical
English strings, an identical check-documentation set, and a derivative GUI. A separate pass (branch
`structural-dedivergence`, 2026-07-17..18) addressed that surface layer. It is a distinct kind of change
from clean-room rewriting and is recorded separately.

The dividing line it respected: the **wire contract is Layer B and was kept** - MCP tool names
(`get_project_errors`), operation names (`create_object`), JSON parameter keys (`projectName`), and JSON
schemas are unchanged, because renaming them would break every downstream client config and rule file for
no legal gain (they are functional interface identifiers, not protected expression). Everything internal -
class names, string wording, GUI, bundled docs - is Layer A and was reworked.

### What was changed

- **String literals (WS-1).** Every byte-identical English prose string >= 40 chars shared with the
  fork point was reworded (tool/parameter descriptions, help text, error and status messages, GUI
  labels), preserving embedded technical tokens (parameter names, example FQNs, format specifiers).
  What remains shared is a **functional floor** that must stay byte-identical: EDT SDK fully-qualified
  names loaded reflectively (`com._1c.g5.v8.dt.*`), BSL keyword pairs (`Попытка/Try`), XML/SAX
  hardening feature URIs, the platform's 1C metadata type-name lists, and markdown table-separator
  rows. These are facts and API identifiers, not authored prose.

- **Class names (WS-2).** Shared class filenames were driven from **188 to 13**. Distinctive
  DitriX-origin names were renamed to a role-based scheme of our own (e.g. `GetProjectErrorsTool` ->
  `ProjectProblemsReader`, `FindReferencesTool` -> `ReferenceLocator`, `McpServer` ->
  `McpHttpEndpoint`, `McpProtocolHandler` -> `McpRequestRouter`). Renames of OSGi-wired classes updated
  `plugin.xml` FQNs in lockstep, integrity-checked (every wired FQN resolves on disk) and verified on a
  running server. The 13 still-shared names are deliberate: MCP/JSON-RPC protocol vocabulary
  (`IMcpTool`, `JsonRpcRequest`, `InitializeResult`, `ToolCallResult`, ...) that any independent
  implementation converges on; Eclipse conventions (`Activator`, `Messages`); a generic helper
  (`JsonUtils`); and the `Tag`/`Group` model classes (generic English, and `Group` collides with the
  SWT widget of the same name, making a mechanical rename unsafe).

- **Package tree (WS-3).** The Java sub-package layout was re-architected off the upstream scheme:
  `protocol` -> `wire`, `tools`/`tools.impl`/`tools.metadata` -> `toolkit`/`toolkit.ops`/`toolkit.mdreport`,
  `utils` -> `support`, `ui` -> `workbench`, `handlers` -> `navigation`, `preferences` -> `settings`,
  `tags` -> `labels`, `groups` -> `folders` (leaf sub-packages carried along). The shared sub-package
  path count dropped from 22 to 1 (only the empty root path `ru.aiedt.mcp.server`, which is inherent to
  the bundle identity, remains). Where a package name coincided with an internal Eclipse id namespace
  (`tags.*`/`groups.*` command, view and decorator ids; the `preferences` page id) and with an NLS
  message bundle, the whole namespace was moved consistently across `.java` + `plugin.xml` + the
  `messages.properties` bundle, so every cross-reference stayed valid. These Eclipse ids are internal to
  the plugin (not MCP wire contract) and already differed from upstream by the root package. plugin.xml
  class-FQNs were integrity-checked (all resolve) and the result was verified on a running server.

- **Check documentation (WS-4).** All 168 bundled `checks/*.md` files - previously upstream prose
  verbatim, carrying an identifying emoji fingerprint and an upstream-Java-class-name block - were
  rewritten into our own flat template. The lookup key (filename stem) is unchanged because
  `get_check_description` resolves by it; the content is ours.

- **GUI (WS-5).** The preference-page information architecture and header, the 13-group tool taxonomy
  (group ids and tool membership kept, so presets/persistence are intact), and the status-bar widget,
  tooltip and context-menu labels were reworked away from the upstream design. The `AUTHOR` string that
  fed the main header/tooltip was set to `AI-EDT`; the "based on EDT-MCP by DitriX, Diversus23"
  attribution is preserved (AGPL obligation) in `README`, this file, and `feature.properties`.

- **RSV interface overlap (WS-6).** Three parameter names that coincide with the unrelated RSV project
  (`installYaxunit`, `linesBefore`/`linesAfter`, `synonym`/`mainLanguage`/`synonyms`) were examined and
  kept: they are Layer-B wire identifiers, the overlap is convergence on obvious names, and no RSV source
  was ever present. For the record: RSV was at one point examined in decompiled form solely to learn
  recipes for calling internal 1C:EDT platform APIs (which SDK services and method sequences achieve a
  given effect); no RSV code was copied, adapted, or used as a structural template. Any parameter-name
  or operation-vocabulary overlap with RSV is convergence on the 1C domain vocabulary, not derivation.

### What was deliberately not changed, and why

- **Method names (~74% overlap with fork).** Not attacked. The shared names are generic SPI and helper
  identifiers (`getName`, `execute`, `format...`, `build...`) with low distinctiveness and high churn.
  These are functional interface identifiers, not protected expression.
- **The `Ctrl+Alt+1..0` marker shortcuts (2026-07-31).** Identical to upstream and kept that way. Ten
  toggles bound to the digit row under one modifier is the only sensible arrangement for "toggle the
  Nth marker"; any substitute would be worse to use and would still be an obvious analogue. This is
  convergence on an ergonomic constraint, not inherited expression.
- **Build scaffolding: `.classpath`, `build.properties`, `.gitignore`, `.settings/*.prefs`,
  `.mvn/jvm.config` (2026-07-31, ex-"W7").** Byte-identical and staying so. Their entire content is
  generated from the project layout by Eclipse and Tycho - a different author starting from the same
  layout produces the same bytes. Editing them to reduce a similarity counter would make the build
  worse and the count no more meaningful.

### What was changed for a functional reason, not a similarity one

- **Default server port 8765 -> 12250 (2026-07-31).** Upstream ships 8765 as its own default, so a
  developer with both plugins installed had the two servers racing for one socket: whichever started
  second failed to bind, silently. Pinned by `shippedPortDoesNotCollideWithTheOtherEdtMcpPlugin`,
  because the alternative guard - a range check - would let 8765 back in unnoticed.

### Divergence measurement (rename-robust, whole tree vs fork point `e28560f`)

Measured by `scripts`-style set intersection independent of file names
(`scratchpad/ws7_reaudit.py`), our current tree against the upstream tree at the fork point:

| Signal | Shared with fork | Before |
|---|---|---|
| Class filenames | 13 (of 154 fork) - convergent vocabulary + conventions | 188 |
| Long English strings (>= 42 ch) | functional floor only (SDK FQNs, BSL keywords, type lists) | full prose set |
| Method names | ~74% (generic SPI/helper names) | ~85% |
| Java sub-packages | 1 (only the inherent root path; tree re-architected) | 22 |
| Bundled check docs matching upstream | 0 | 168 |

This is structural preparation for a licence review; it is not itself a licence conclusion. As stated at
the top of this document, that conclusion belongs to counsel, and AGPL-3.0 plus every upstream copyright
notice stays in force until then.

## Method-body similarity scoping (D1a, 2026-07-21)

The 2026-07-21 audit measured names and byte-identity (0% byte-identical Java). A second pass measured **normalised body similarity** to find copied *expression* in method bodies despite rename: two lenses over all 369 of our Java files vs 537 upstream files (path-independent, git-archive extract). Lens A is raw line-Jaccard after stripping comments/whitespace; lens B ("id-mask") additionally collapses every identifier to a placeholder, so it catches structure copied under rename. The analysis script is `scratchpad/d1a_body_similarity.py` (local working tool, reused for the final audit).

Distribution by lens A (raw): >90% - 0 files (confirms 0% byte-verbatim); >70% - 1; >50% - 6; >30% - 39 (10.6%); >10% - 200 (54.2%); ≤10% - 169 (45.8%, well-diverged). Lens B (id-mask, the copy-despite-rename signal): 4 files >70%, 27 files >50%.

The residual groups into three clusters, each treated differently:

1. **JSON-RPC / MCP wire DTOs** (`wire/jsonrpc/Json*`, `wire/ToolResult`, `InitializeResult`, `ToolsListResult`). High raw similarity (top `JsonRpcResponse` 78.6%) but the structure is dictated by the JSON-RPC 2.0 and MCP specifications (`initialize`, `tools/list`, `tools/call` shapes). Two independent implementations of the same protocol converge on the same field set and ordering; this is spec-driven reimplementation, not copied expression. Treatment: documented here as spec-origin; no rewrite (a rewrite would converge back to the spec). These names are also part of the unprotectable protocol vocabulary.

2. **Utility layer** (`support/`): `JUnitRunOutcome` (lens B 79%), `ReflectionAccess` (76%), `BuildTaskHelper` (57%), `DebugSessionBook` (~55%), `ProjectStateGuard`, `LaunchConfigAccess`, `DebugValueSerializer`, `MetadataTypeCatalog`, `ProjectReadinessGate`. These carry the most copied *expression* - function bodies lifted near-verbatim with parameter renames. Treatment: **clean-room rewrite from a functional spec** (behaviour spec written from purpose; implementer denied the original). This is the legally-relevant D1a work. The rest of `support/` (105 files, mean 10%) is already well-diverged by the earlier phase-1/2 refactor.

   **Update after inspecting the top candidates:** the two highest-similarity utilities are *structure- or idiom-dictated*, not copied creative expression. `JUnitRunOutcome` is a trivial data holder (four counters, three detail lists, getters, two one-line derived methods) whose shape any implementation of "a JUnit run outcome" converges on. `ReflectionAccess` is four canonical `java.lang.reflect` idioms (`getMethod`/`invoke`, declared-field walk, declared-method walk) plus one `sun.misc.Unsafe` static-final flip - a widely-documented JDK-internal technique, not upstream-original expression. The id-mask lens over-flags classes whose similarity comes from implementing the same spec or JDK idiom rather than from copying. Having passed the Phase-3 clean-room rewrite and the Inc4 god-class split, the codebase's *creative expression* is already substantially independent: 0% byte-verbatim Java, 45.8% of files at ≤10% similarity, and the remaining "high similarity" is concentrated in spec/idiom-dictated shapes that a rewrite would converge back to. **Conclusion: D1a body-rewrite is not warranted**; the residual is legitimate convergence, documented here for counsel. A counsel-guided deeper audit of the few large classes (`SymbolInfoReader`, `LaunchConfigAccess`) remains optional if they want a second look at non-trivial logic.

3. **Preferences / Tags / Groups GUI** (`settings/`, `labels/`, `folders/`): `McpSettingsPage` (lens B 72%), `ToolParamSettings`, `ToolSettingsStore`, Tag/Group models, `ManageTagsCommand`. This is the GUI layer that the K1 visual-rebrand wave redesigns wholesale (and which WS-5 targets for de-derivation). Treatment: deferred to K1 - rewriting now and redesigning later is double work.

`toolkit/ops/` (151 files, mean 11.5%, max 43%) - the Inc4 god-class split - is the most-diverged area and needs no body work.

### Verbatim-string and internal-method assessment (D3, D4, 2026-07-21)

D3 (changeable verbatim strings) and D4 (internal operation method names) were assessed and **deliberately not churned** - neither carries protectable upstream expression.

- **D3 - verbatim long strings.** The 59 strings shared with upstream are almost entirely the irreducible floor: BSL keyword references (`КонецПроцедуры`/`EndProcedure`, `Функция`/`Function`), EDT type-name unicode-escape literals, JSON-RPC/MCP protocol vocabulary. The only non-floor subset is the example FQN fragments used in tool descriptions (`Catalog.Products`, `CommonModule.Common`, `Document.SalesOrder`, `Catalog.DataAreas`). These are domain-generic example names any 1C metadata implementation converges on; they are not upstream-original expression, and renaming them is cosmetic churn across ~47 files plus tests for no derivation gain. Left as-is.
- **D4 - internal operation method names** (`opCreateObject`, `opAddObjectAttribute`, etc.). These are functional identifiers inside `toolkit/ops/`, not user-facing (not in `tools/list`, not wire, not plugin.xml). Method names are not protectable expression, and the 27% method-name recall measured by the audit is dominated by generic SPI/domain verbs any implementation shares. Renaming is churn for a metric. Left as-is.

Both conclusions are recorded for counsel: the legally-relevant surface was D1 (code expression, already diverged); D3/D4 hold no upstream-original expression to remove.

## Full-divergence audit (2026-07-21, vs DitriX v2.8.1)

A fresh similarity audit against the current upstream tip `origin/master` `351401ab` (tag **v2.8.1** - upstream advanced from the v2.7.1 baseline of the 2026-07-18 audit). Headline: class-basename overlap 4.2% (13/309), byte-identical Java 0%, method-name recall 26.6% (down from ~74%), sub-packages 23 vs 37 (parallel taxonomy). The 13 shared basenames are inherent MCP/JSON-RPC contract vocabulary and the Tag/Group domain. The measuring tooling and the full per-file numbers stay in the development repository where the separation work was carried out.

This audit motivated a structured full-divergence effort that distinguishes the **legally-relevant surface** (copied *expression*: code bodies, docs, images, test data - what copyright protects) from **perceptual/branding** divergence (API/tool/param names - which are functional identifiers, not protected expression), and does not claim a fixed percentage as a safe harbour: substantial similarity is a holistic legal test, and the conclusion belongs to counsel.

### Non-Java asset inventory (D1b)

Hash-comparison of the current tree against the fork point `e28560f` (path-independent, so renames are covered) finds 71 non-Java blobs byte-identical to upstream at this audit's baseline. Classification (counts are paths):

| Class | Paths | Status |
|---|---|---|
| Vendor libraries | 3 (`lib/snakeyaml-2.2.jar`, `lib/jsoup-1.17.2.jar`, `lib/copy-down-1.1.jar`) | Third-party, license-compatible - retained |
| License text | 1 (`LICENSE`, AGPL-3.0) | Standard FSF license text, required - retained |
| Eclipse / Maven / build config | 18 (`.classpath`, `.project`, `.gitignore` x3, `.settings/*.prefs` x7, `build.properties` x2, `default.target`, `.mvn/jvm.config`, `.gitattributes`, `.github/workflows/e2e-tests.yml`) | Platform/build-generated config, low expression - retained |
| README screenshots | 0 (was 14 `img/*.png`) | **Removed 2026-07-31** - the files were byte-identical with upstream and no document referenced them any more; screenshots of this product's own UI will be captured with the K1 visual rebrand |
| Plugin UI icons | 0 (was 13 `icons/*.png`, `icons/*.svg`) | **Redrawn 2026-07-31** - the shipped set was drawn for this product from a generator written for the purpose, rather than inherited as bitmaps; the two orphaned SVGs no document or class referenced were dropped |
| E2E test fixtures | 1 (was 21; `AiEdtProbe/.settings/org.eclipse.core.resources.prefs`) | **Replaced 2026-07-31** - `TestConfiguration/` was deleted and `AiEdtProbe/` authored in its place through this plugin's own `create_project` / `edit_metadata` operations, so the fixture is a product of our tooling rather than an inherited project: different objects, different names, Cyrillic identifiers the old one had none of, and a deliberate compile error as the suite's single positive case. The one remaining path is the two-line Eclipse project-encoding preference |
| GitHub config | 1 (`.github/copilot-instructions.md`) | **Removed 2026-07-21** - referenced upstream-only "expert consultation" tools this fork does not implement; stale and misleading |

Totals: 71 inherited-identical paths at audit baseline; 21 remain (15 distinct blobs, several shared across paths). Everything addressed since: 14 screenshots removed, 13 icons redrawn, 20 of 21 test-fixture paths replaced, 1 stale GitHub config removed. What is left is 3 vendor jars, the AGPL license text, 16 build-scaffolding files, and the fixture's two-line encoding preference - each either third-party, a verbatim license, or generated from the build layout. None of them is a file where a different author would have written something different, so this is the floor rather than a backlog. AGPL/attribution stays in force until counsel.

### Wire-name capability seam (D5a, D5c, 2026-07-21)

A stable **capability id** was introduced on `IMcpTool` (`getCapabilityId()`, defaulting to `getName()`) and the registry (`McpToolCatalog`) was re-keyed by capability id, with a callable-name resolution map covering each tool's primary wire name and its `getAliases()`. The disabled set, the unlisted set, the category membership and the preference keys all key on the capability id, which a wire-name rename never touches; the gate therefore cannot be bypassed or silently re-enabled by renaming a tool. Callable aliases are accepted at `tools/call` but never advertised in `tools/list`, so an old wire name keeps working - under the same preset decision - after a rename, without costing a slot in the advertised catalogue.

This is prerequisite infrastructure for any Layer B wire rename, and an independent correctness improvement: presets stop depending on volatile wire names. It is verified by `McpToolCapabilityTest` (alias resolution, capability dedup, primary-wire-name clash rejection, renamed-tool counting). The default capability id equals `getName()`, so the change is a behavioural no-op until a tool overrides it; no tool does so yet.

### Wire-name rename, assessed and deferred (D5-rename, 2026-07-21)

With the capability seam in place, renaming the 59 wire names shared with upstream is now mechanically safe (freeze the old name as the capability id, set the new wire name, list the old name as an alias). It was nevertheless **assessed and deliberately not executed**, on the same grounds as D3/D4:

- The 59 shared names are functional EDT-API verbs (`get_`, `search_`, `find_`, `validate_`, `edit_`, ...), dictionary-determined by the domain. They are not protectable expression (API names are functional; *Oracle v. Google*, 2021). Renaming them does not remove upstream-original expression because none resides in them.
- A back-compat-preserving rename (the only kind that does not break the 29 user rule-sets and 23 `.mcp.json` clients) leaves the old name present as a callable alias string, so the similar-to-upstream identifier persists in the codebase regardless - the rename buys perceptual divergence in `tools/list` only, not in the source, and removes nothing a copyright analysis weighs.
- Dropping the aliases to truly remove the names is a breaking change across every client, for zero legal gain on non-protectable identifiers.

The seam remains in place so that if counsel specifically directs a wire-name change, it is a safe, mechanical, per-tool override (freeze capability id + new `getName` + old name in `getAliases`) with no gate or preset rewrite required.

## Counsel bottom line + token-overlap audit (D6, 2026-07-21)

The one dimension the earlier audits left unverified was identifier-normalized (AST-like) similarity of the largest operation classes against their DitriX counterparts. A per-class measurement (strip comments and string literals, lowercase, drop Java keywords, compare char-3-gram Sorensen-Dice) was run on the eight largest `toolkit/ops` + `support` classes, each paired with its closest DitriX counterpart under tag `v2.8.1`. Method: parallel workflow (`scratchpad/d6_token_overlap.js`), ten agents, results verifiable from the tag.

On the defensible legal position: no AI-EDT `.java` file reproduces any DitriX source verbatim (blob-hash compare across 309 vs 303 classes returns 0% byte-identical), so there is no prima facie case of direct copying. The token-overlap that remains is driven by identifiers and structural shapes that the EDT SDK, the EMF/BM model, the MCP JSON-RPC contract, and the 1C metadata/form/DCS domain force on any correct implementation; under *Google LLC v. Oracle America, Inc.*, 593 U.S. \_\_ (2021), names, organizing keys, and implementing structure dictated by an external platform are functional and lie outside the protection copyright affords to creative expression. Where AI-EDT and DitriX converge on the same verb (e.g. `getBackReferences`, `attachTopObject`, `invokeNoArg`), convergence is dictated by the same upstream API surface; where their algorithms share a phase ordering, that ordering is dictated by the EDT refactoring/change-tree contract. The decomposition into methods, helper names, data structures, error-handling shape, and comment prose are independently authored and have measurably diverged (unique-token Jaccard 13.16% on the one head-to-head where it was measured; method-name recall ~27% across the codebase; class-basename overlap 4.2%, all inherent MCP/JSON-RPC/OSGi vocabulary).

### Token-overlap measurements

| AI-EDT class | DitriX counterpart | Overlap % | AI-EDT LOC | DitriX LOC | Residual nature |
|---|---|---:|---:|---:|---|
| DcsWorkshopTool | DcsWriter | 48.47 | 3462 | 1238 | Platform idiom: DCS EMF nouns + MCP param keys; architectures diverge (reflection+EObject vs typed DcsFactory+Plan) |
| BmFormHelper | FormElementWriter | 50.46 | 1470 | 2025 | Platform idiom: EDT FormFactory/EMF SDK + 1C form-schema property names; architectures diverge |
| BmDefinedTypeHelper | MetadataTypeBuilder | 29.72 | 2636 | 544 | Platform idiom: BM/Mcore SDK vocab + JSON-RPC nouns; 2636-LOC reflection helper vs 544-LOC typed builder, no shared prose |
| MetadataObjectRenamer | MetadataRenameService | 86.09 | 1986 | 1682 | Platform idiom dominant: EDT BM/reflection/LTK API names + IRefactoring/Change-tree shape; AI-EDT inlines buildPreview/performRename and adds scanLeftovers, safeTopFqn, external-project guard absent upstream |
| EditMetadataTool | ModifyMetadataTool | 42.25 | 1769 | 2752 | Platform idiom + heavy `str` token from input-schema constructors; unique-token Jaccard only 13.16%; declarative OpEntry registry + 14 Ops vs monolithic dispatch cascade |
| McpHttpEndpoint | HttpTransport | 9.68 | 1311 | 86 | Platform idiom only: JDK httpserver, UTF-8, Activator prefs, constantTimeEquals; no shared method names; 1311-LOC endpoint vs 86-LOC static helper |
| ObjectOps | CreateMetadataTool | 29.49 | 1502 | 1614 | Platform idiom: EDT BM/mdclass SDK identifiers + MCP vocab + 1C-metadata nouns; structure and helper taxonomy fully divergent |
| ReferenceLocator | MetadataReferenceService | 75.64 | 1445 | 949 | **Mixed, partly copied creative authorship** - see below |

### The one exception: ReferenceLocator

`ReferenceLocator` is the single file whose residual overlap is creative rather than functional. The measurement found inherited authorship: identical private method names (`collectBackReferences`, `buildInnerPathEdtStyle`, `getFullReferencePath`, `isInternalPath`, `isInternalReference`, `extractModulePath`, `getFeatureLabel`, `formatFqn`, `capitalizeFirst`, `findTopContainer`, `buildNestedObjectPath`, `collectBslReferenceDescription`), an identical 5-phase structure in the same order, an identical 16-entry category substring table (`Subsystem`->`Subsystems` ... `Template`->`Templates`), identical `isInternalPath` magic strings, an identical `Items.Items` collapse rule, and identical inner data classes (`ReferenceInfo`, `ReferenceCollector`, `PathSegment`). It is a feature-expanded refactor (Pending/runKey, deep, CategoryFilter, multi-project scope, classifyTypeKind, `IMcpTool` impl) of the DitriX original; the algorithm shape, identifier choices and constants were inherited, not rediscovered.

This is the bounded, single-file clean-room rewrite target the audit identifies; no other file is in the same category. It is disclosed here rather than hidden, and it is an AGPL-permitted modified derivative with upstream attribution preserved pending counsel.

### Caveat

A MOSS- or JPlag-style AST-normalized tool that strips whitespace, identifier capitalization and literal values before comparing could still report non-trivial similarity on two categories: (a) shared algorithm shapes the EDT refactoring contract forces on any correct implementation (e.g. the three-pipeline merge and `collectFlatChanges`/`walkLeafChanges` walk in `MetadataObjectRenamer`), and (b) the inherited private-method names, constant tables and inner data classes in `ReferenceLocator`. This does not change the legal conclusion: category (a) is functional under *Oracle v. Google* and not protectable expression even when it survives AST normalization; category (b) is the one disclosed file whose inherited authorship is documented above and targeted for clean-room rewrite. AGPL-3.0 permits modified derivatives, so the question for counsel is attribution/notice compliance under AGPL Section 5, not infringement of copyrightable expression.

### D7 - ReferenceLocator divergence applied (2026-07-23)

The category-(b) file above has been diverged (commit `2a0b09b4`). The protectable copied expression the D6 measurement flagged - the 12 shared private method names, the 16-row category substring cascade, the `isInternalPath`/`isInternalReference` magic strings and the 3 inner data classes - was removed and re-derived to the project's own vocabulary, while the EDT-BM-dictated algorithm and the public `IMcpTool` contract (name, schema, description, output MARKDOWN) were kept untouched. The 16-row category cascade is now a declarative `TYPE_BUCKETS` list iterated first-match-wins (same order, labels and fallback); the magic strings are named list constants; two dead unused methods were deleted and a return-0 stub inlined. Behaviour was verified byte-identical: `find_references` on `Catalog._ДемоВидыНоменклатуры` returns the same Total (170), the same metadata-reference list with the same category buckets, and the same BSL-reference modules and line numbers before and after the change. Codex-reviewed, no findings. The residual algorithm-level similarity that remains is category (a) - platform-dictated, non-protectable.


