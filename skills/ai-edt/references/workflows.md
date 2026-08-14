# Call orders for common tasks

Recipes, not rules. Each one assumes EDT is running and `get_edt_version` answered.

## Understand an unfamiliar feature

1. `insights operation=semantic_metadata_search` or `code_search operation=text_search` to find the
   entry point. On a large configuration always narrow the search - see `expected-behavior.md`.
2. `ai_context` with the object FQN: metadata, module list and module structure in one call.
3. `get_module_structure` for the module that matters, then `read_method_source` for the two or
   three methods that matter. Do not read whole modules.
4. `code_search operation=call_hierarchy` with `direction=incoming` to learn who calls a method,
   `outgoing` to see what it calls.
5. `code_search operation=object_references` for everything that touches a metadata object,
   including forms, roles and subsystems - references a text search cannot find.

## Change BSL safely

1. `read_method_source` for the method you are about to change, so the replacement is based on
   what is there now.
2. `write_module_source` with a targeted mode: `replaceMethod` by name, or `replaceLines` with
   `expectedText` so a shifted file fails loudly instead of silently overwriting the wrong lines.
3. `validate_query` immediately for any query text you touched.
4. `diagnostics operation=revalidate_objects` on the object, then
   `diagnostics operation=get_project_errors` filtered to it.
5. `code_review` for style and complexity findings when the change is substantial.

Whole-module `replace` overwrites everything in the module. Use it only when you genuinely mean to
replace the whole file.

## Create metadata

1. `edit_metadata operation=help` to confirm the operation exists and what it needs. This step
   prevents the most common failure: hand-rolling a `.mdo` edit for something the server already
   does.
2. Create with `edit_metadata`, or many objects at once with `batch=true`.
3. Read `batchResults[]`, redo failed entries one by one.
4. `diagnostics operation=revalidate_objects`, then `get_project_errors` on the new objects.

On a freshly created project, validation markers such as unknown `String` or `Number` types are
stale derived state rather than real type errors. Revalidation does not clear them;
`diagnostics operation=clean_project` does.

## Write a query

1. Ask what you may select from. `insights operation=describe_db_tables` lists the tables an object
   turns into and the fields of each - the main table, one per tabular section, a register's virtual
   tables with their parameters - named in both languages, ready to write after `ИЗ`. This is the
   half validation cannot cover: validation rejects a field you invented, and says nothing about a
   field you never thought to ask for.
2. Write it.
3. `validate_query` with the project name. For a data composition query pass `dcsMode=true`.
4. Fix what it reports, revalidate. The `hints` array flags SQL habits that do not exist in 1C
   query language.
5. Before writing BSL that reads the result, ask again with `describeResult=true`. It returns each
   result table with its column names and types, taken from EDT's model rather than from the text -
   which is how you avoid inventing a column that is not there. A column with no type reported is
   one whose type could not be determined; it is not a column of unknown-but-guessable type.
   `packageIndex` counts temporary-table statements too, so it matches the real
   `ВыполнитьПакет()` position. An asterisk is expanded into the fields it stands for, named in the
   language the query is written in; a star over a nested query or a temporary table stays as one
   column, because there is no table of the model behind it.

Do not batch several queries and validate at the end - a failure then costs a hunt for which one
broke.

## Edit a managed form

1. `get_form_structure` to see the element tree, with `depth` or `subtree` for a large form.
2. `edit_metadata` form operations to add, change or remove elements.
3. `get_form_screenshot` to see the result as the editor renders it.
4. Revalidate, then read errors.

Never hand-write `.form` XML. The form model has invariants the constructor maintains and a
hand-edit does not.

## Debug a running application

1. `launch_debugger action=launch` for a client launch, or attach to a debug server when the code
   runs in a background job, an HTTP service or a server call.
2. `action=add_breakpoint` on a line that actually executes in the path you are exercising. A
   breakpoint on a declaration or an unreachable branch simply never hits.
3. `action=wait_for_break` to block until it hits.
4. Inspect the stack and variables, `action=evaluate` for expressions, step, then `action=resume`.
5. `action=terminate` when finished.

## Run unit tests

1. `validate_for_export` - the runner updates the infobase before launching by default, and that
   update is what a broken configuration breaks.
2. `yaxunit_tests mode=run` with filters narrow enough to be quick.
3. `mode=debug` when a test fails and you need breakpoints inside it.

## Update the infobase

1. `validate_for_export`. Findings block the update: fix, do not force.
2. `infobase_admin operation=sync_control syncOperation=status` to see whether the next update will
   be incremental or full, and why.
3. `infobase_admin operation=update_database`.

## Audit security

`security_audit` covers role rights, RLS violations and sensitive data. All three are read-only.
Treat their output as findings to verify, not as a verdict: a role that looks over-permissive may
be intentional, and a match on a field name is not proof that the field holds personal data.
