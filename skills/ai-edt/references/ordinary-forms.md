# Ordinary forms

`ordinary_forms` reads and edits the ordinary (non-managed) forms of an open project. The layout and
the BSL module are stored in a `Form.oform` container inside the project. EDT builds neither a
layout model nor a BSL model for these entries. The tool is part of the main AI-EDT feature and answers JSON.

## Address a form

Operations on an existing form take `projectName` and either `formFqn` or `modulePath`:

- `formFqn`: `Catalog.Products.Form.ItemForm`.
- `modulePath`: `Catalogs/Products/Forms/ItemForm/Module.bsl`, relative to the project's `src/`.

`help` needs no project; `audit` takes the project without a form address. `describe_owner` and
`create_form` take `ownerFqn`, and creation also takes `name` (`formName` is accepted as an alias when `name` is absent).

The module tools resolve the same `modulePath` to the module
entry of the container: `read_module_source`, `read_method_source`, `get_module_structure`,
`list_modules`, `write_module_source`, `diff_module` and `code_search operation=text_search`.

```json
{
  "operation": "layout",
  "projectName": "my-project",
  "formFqn": "Catalog.Products.Form.ItemForm"
}
```

`operation=help` returns the catalogue of operations, their arguments, the property registry and the
event names of the current build.

## Operations

24 operations: 6 queries, 2 creation, 16 writes on an existing form.

| Operation | Group | What it does |
|---|---|---|
| `audit` | query | Every ordinary form of the project: layout version, container entries, module size, byte-for-byte round trip of the layout text. `details` defaults to false; `details=true` lists the forms, and `limit` caps the lists (default 20). |
| `layout` | query | One form: title, attributes with types, pages, handlers with their events, the element tree (type, name, caption, tooltip, box, page, data path, handlers) and the buttons of a command panel. |
| `diff_form` | query | One form against `HEAD`: whether the layout entry and the module entry changed, and which other entries were added, removed or changed. A changed module is left to `diff_module` at the module address. With nothing to compare against, `revision` names the outcome. |
| `inspect_layout` | query | Structural findings over the layout and their coverage. Reads only. |
| `describe_owner` | query | `ownerFqn` (`Catalog.Products`): the attributes, tabular sections, standard attributes and list columns of an owner, in words. |
| `create_form` | creation | A new form for an owner: the owner's entry and `Forms/<name>/Form.oform`. |
| `import_from_infobase` | creation | Imports the form container from the project's infobase. `module=project` (default) preserves the project's current module; `module=infobase` uses the exported container's module. `applicationId` selects the application, using the project's default when omitted. |
| `set_property` | write | A property of the form or of an element, chosen by `property` from the registry. |
| `add_element` | write | An element of `type` with `name`, `parent` (a Panel) and `page`, a rectangle (`left`, `top`, `right`, `bottom` or `width`, `height`), optional `data`, `caption` (`title` is accepted when `caption` is absent) and `tooltip`. |
| `remove_element` | write | `elementName` with its children, data bindings and anchors. `cascade=true` clears referring command-panel action sources; otherwise those references refuse removal. |
| `move_element` | write | `elementName` into `parent` and `page`; the box stays, anchors the move separates are released both ways and listed. |
| `add_page`, `remove_page` | write | `add_page` takes the required `name`, optional `title` and `parent`; `remove_page` takes `name` and `parent`. Without `parent`, the page belongs to the form. A page with elements on it or the last page is refused. |
| `add_column`, `remove_column` | write | A table column: `elementName` (the Table), `name`, `caption` (`title` is accepted when `caption` is absent), `data`, `type` (`String`, `Number`, `Date` or `Boolean` when the data binding does not determine the type), `width`, `editable` (default true; an explicit value contradicting the chosen `control` is refused), `control` (default `field`, or `none` with `editable=false`). |
| `add_command_panel` | write | A command panel with `name`, a box, `parent`, `page`, `actionSource` (a Table) and `autoFill`; an omitted box defaults to left=8, top=8, right=left+400 and bottom=top+25 (or left+width and top+height when the size words are given), and `autoFill` defaults to true with an `actionSource` and to false without one or with `none`. |
| `add_button`, `remove_button` | write | `panel` and `name` identify the button. Creation takes exactly one of `procedure` (with `stub=true` for an empty one; default false) and `action` (a standard action), optional `picture` (only with `action`, dictionary index 0 to 3), `parent` (a submenu) and `before` (a button of the same block). An omitted `caption` defaults to the button's name; a button of a standard action keeps the text the platform gives that action and takes the caption as its tooltip. An omitted `picture` defaults to the picture the measured corpus most often writes for the action. |
| `add_submenu`, `add_separator` | write | `panel` and `name` identify the submenu or separator, with `parent` and `before` as for a button. A submenu also takes `caption`, defaulting to `name`. |
| `set_action_source` | write | `panel` and `table` select the command panel and its source table, with `autoFill`, or `table=none` to clear the source; `autoFill` defaults to true with a table and false with `none`, and an explicit argument overrides it. |
| `bind_handler`, `unbind_handler` | write | `event` of `elementName` or of the form. `procedure` defaults to `<element><event>`, or to the event itself for the form; `stub` defaults to true, so a procedure the module does not declare is added with the measured parameters unless `stub=false` is passed, and an event whose parameter signature is not recorded gets no stub. |
| `help` | query | The catalogue. |

A write marks the owner for the next infobase update.

## Properties of `set_property`

`elementName` addresses an element; without it the property applies to the form. The common
`language` argument defaults to `ru` and selects the language of localized text.

- `title`: `value` sets the form's title, or an element's caption with `elementName`.
- `caption`: an element's caption (`value`).
- `tooltip`: an element's tooltip (`value`; an empty value clears it).
- `size`: the form's `width` and/or `height`.
- `box`: an element's `left`, `top`, `right`, `bottom` or its `left`, `top`, `width`, `height`.
- `visible`, `enabled`: `value=true|false`.
- `readOnly`: a field's read-only flag (`value=true|false`).
- `tabs`: the page-strip setting of a panel (`value=true|false`).
- `font`: `value=auto` restores automatic font selection for the element, or use `fontFamily`
  with `fontSize`, `bold`, `italic`,
  `underline`.
- `textColor`, `backColor`, `borderColor`: `value=auto`, `#RRGGBB` or a style element index.
- `alignment`: `value=left|center|right|auto` for a Label, Button or Field; a Field accepts `left`, `right` or `auto`.
- `format`: a field's format string in `value`; an empty value clears it. `language` sets the language of the format string (default ru).
- `anchor`: `edge=left|top|right|bottom` bound to `value=form`, `value=none` or a neighbour
  `value=<element>.<edge>`, with `offset`.
- `editFormat`: returns a named refusal without changing the layout.

Layout writes accept versions 25, 26 and 27.

`add_element` writes one of 17 kinds: `Label`, `Field`, `Button`, `CheckBox`, `Group`, `Panel`,
`Table`, `RadioBtn`, `Separator`, `Image`, `SelectField`, `CommandPanel`, `TableField`, `FieldHtml`,
`ListField`, `TextDocumentField`, `Chart`. A Table and a ListField take their rows as `data`; a
TableField, a FieldHtml, a TextDocumentField and a Chart take none.

## Presets and `dryRun`

The tool declares one write capability, `ordinary_forms_writes`. A write-blocking preset refuses
every writing operation of the tool, `dryRun` included, before the project is resolved; the queries
stay available. Disabling the tool itself disables the queries too.

Under a write-enabled preset `dryRun=true` reports the proposed change and writes nothing. For
`import_from_infobase` the preview checks the form address, its entry on the owner and the presence
of the project container when `module=project`. Application selection, export, container validation
and handler checks take place during the actual import.

## `create_form` arguments

`ownerFqn`, `name`, `purpose`, `synonym`, `title`, `width`, `height`, `setAsDefault`, `withFields`,
`withTables`, `withCommands`, `columns`, `tabularSections`, `dryRun`, `language` and `projectName`.

- `purpose`: `ItemForm` (default for an object), `ListForm` (default for a register), `ChoiceForm`,
  `FolderForm`, `RecordForm`, `RecordSetForm` or `None`.
- An omitted `synonym` is generated from the form name; `title` defaults to that synonym.
- `width` 500 to 1000 (default 500, or 820 with two columns); `height` 350 to 700 (default: the
  content, at least 350).
- `columns`: `1`, `2` or `auto` (default `auto`: 2 when the form lays out a header with more than 12 attributes, otherwise 1).
- `tabularSections`: `pages` or `below` (default `pages` from two sections, else `below`).
- `withFields` defaults to true for `ItemForm`, `FolderForm` and `RecordForm`.
- `withTables` defaults to true only for `ItemForm` with tabular sections.
- `withCommands` defaults to true except for `purpose=None`, where it defaults to false.
- `setAsDefault` defaults to false.
- For `create_form` and `describe_owner`, an omitted `language` uses the configuration's default language, with `ru` as fallback when no default is available. Layout edits default to `ru`.

`edit_metadata operation=create_form` with `formType=ORDINARY` routes to this tool, passing
`projectName`, `ownerFqn`, `purpose`, `synonym`, `title`, `setAsDefault`, `dryRun` and the name.
`ORDINARY` is matched without regard to case, after trimming surrounding whitespace. Other values
do not select this route; without explicit `ORDINARY` the existing managed-form path applies.
The eight arguments above that belong to the ordinary creator alone (`width`, `height`, `withFields`,
`withTables`, `withCommands`, `columns`, `tabularSections`, `language`) and `layout` are refused by
that route with a pointer to `ordinary_forms`. Call `ordinary_forms` directly for them. A common
form is routed by name and refused by the ordinary creator.

## `inspect_layout`

Read-only. The answer carries `findings[{check, severity, subject, detail}]`, `findingCount`,
`errorCount`, `warningCount`, `elementCount`, `coverage[{check, status, detail}]` and `note`.

The checks are static and definite: duplicate ids and names in their namespaces, references to
pages, elements, attributes, action sources and anchor targets the layout does not hold, handlers
the module does not declare, invalid boxes and anchor cycles. The warnings are siblings of one page
that overlap (a Group or Panel frame is never a party) and a child past its parent's box.

`status` is `performed`, `partial` or `notPerformed`. A result without findings and with partial
coverage is not a validated whole form: read `coverage` alongside `findings`. Nothing is corrected.

## Module edits

A module write changes the module entry alone; the layout entry is carried through untouched and the
other entries of the container keep their bytes. A write that produces the text already there does
not write at all, so the container keeps its bytes and its timestamp. An unchanged container returns
its original bytes; a modified container is not byte-identical as a whole.

Before replacing changed text, a write compares each replaced entry with the text originally read.
If the layout or the module being replaced changed after that read, the operation refuses the
write and asks the caller to read the form again. Entries not being replaced are preserved from
the latest container read; writing the text already present does not modify the file.

`import_from_infobase` imports every exported entry; `module=project` substitutes only the module
entry with the project's module. It writes whenever the assembled container bytes differ from the
project container, including an added, changed or removed unknown entry with equal layout/module
texts. Equal whole-container bytes are a no-op.

`import_from_infobase` compares the whole project container with the bytes observed before export.
A container changed, deleted or created during export refuses the import, including an otherwise
unchanged result. The final writer repeats the byte comparison before writing. These are optimistic
checks, not an operating-system atomic compare-and-swap.

Trusted `/health` reports `ordinaryFormsRegistered` independently of tool visibility or write
permissions. A single-session self-update of a shared delivery confirms this registration after
relaunch. Without that confirmation it returns a nonzero result, including when health exposes only
unauthenticated liveness data; authenticate the runtime check separately in that case.

A write that changes a container refuses a connected text-editor buffer with unsaved changes.
The same check applies to module and layout writes, import and the final container write of
creation. Save or revert the editor before repeating the operation.

`handlerChanges` governs a write that takes away a procedure the form still names, in an event
binding of its layout or in a string literal of the module: `warn` (default) lists the procedures
and lets the write through, `refuse` keeps the container as it is. EDT resolves neither reference -
the client reports the missing procedure when the event fires.

The write ends with the mark of the owner for the next infobase update, and names it in the answer.
EDT builds no model of an ordinary form's module, so platform validation of it happens at the
infobase update, not in the project diagnostics.

Ordinary form modules are outside the BSL index. `code_search operation=call_hierarchy` and
`operation=object_references`, and `diagnostics operation=get_project_errors`, end with a coverage
line naming how many modules were not counted; `code_search operation=text_search` scans their text.

## Delivery

A successful write updates the project only. Apply it to the infobase with
`infobase_admin operation=update_database`, after `diagnostics operation=validate_for_export`. The
write response and the infobase update are separate results; a written file and a marked owner are
not an updated infobase. Read the form back with `layout`, and compare the container entries with
`diff_form`.
