# Ordinary forms

`ordinary_forms` reads and edits ordinary forms in an open EDT project. The form layout and its
BSL module are stored in `Form.oform`. The tool is included in the main AI-EDT feature.

## Address a form

Pass `projectName` and either `formFqn` or `modulePath`:

- `formFqn`: `Catalog.Products.Form.ItemForm`.
- `modulePath`: `Catalogs/Products/Forms/ItemForm/Module.bsl`, relative to the project's `src/`.

The module address is also accepted by `read_module_source`, `read_method_source`,
`get_module_structure`, `list_modules`, `write_module_source`, `diff_module` and
`code_search operation=text_search`. A module change updates the module entry alone: the layout
entry and every other entry of the container are carried through untouched.

Read the operation catalog with `operation=help`. The input schema and help use the same operation
and property registries.

```json
{
  "operation": "layout",
  "projectName": "my-project",
  "formFqn": "Catalog.Products.Form.ItemForm"
}
```

## Operations

| Operations | Purpose |
|---|---|
| `audit` | Enumerate ordinary forms, layout versions, container entries and round-trip results; `details` lists individual forms and `limit` bounds the lists. |
| `layout` | Read the title, attributes, pages, element tree, command panels and handler bindings. |
| `inspect_layout` | Report structural findings and the coverage of the analyzed records, bindings, handlers, anchors and geometry. |
| `diff_form` | Compare the layout, module and other container entries with the Git revision. |
| `describe_owner` | Read the owner's attributes and tabular sections for form composition. |
| `create_form` | Create a form for an owner. See the arguments and defaults below. |
| `import_from_infobase` | Pull the form's container out of the project's infobase. `module` chooses where the module entry comes from (`project`, the default, or `infobase`), `applicationId` the infobase when the project has more than one. |
| `set_property` | Change an addressed form or element property. |
| `add_element`, `remove_element`, `move_element` | Insert, remove or move elements, including their parent and page assignments. |
| `add_page`, `remove_page` | Edit pages of the form or a panel. |
| `add_column`, `remove_column` | Edit table columns and their data bindings. |
| `add_command_panel`, `add_button`, `remove_button`, `add_submenu`, `add_separator`, `set_action_source` | Edit command panels, actions and their sources. |
| `bind_handler`, `unbind_handler` | Bind or unbind an event; `stub=true` adds a procedure when it is absent. |
| `help` | Read the catalog, supported properties and event names. |

## Create a form

`operation=create_form` builds the form and its layout from the owner. `describe_owner` returns the
owner's attributes and tabular sections before that.

- `ownerFqn`, `name`: the owner (`Catalog.Products`) and the name of the new form.
- `purpose`: `ItemForm` (default for an object), `ListForm` (default for a register), `ChoiceForm`,
  `FolderForm`, `RecordForm`, `RecordSetForm` or `None`.
- `synonym` defaults to the name, `title` to the synonym.
- `width` from 500 to 1000 (default 500, or 820 with two columns); `height` from 350 to 700
  (default: the content, at least 350).
- `columns`: `1`, `2` or `auto` (default `auto`: one column up to 12 header attributes, else two).
- `tabularSections`: `pages` or `below` (default `pages` from two sections, else `below`).
- `withFields` defaults to true for `ItemForm`, `FolderForm` and `RecordForm`.
- `withTables` defaults to true only for `ItemForm` with tabular sections.
- `withCommands` defaults to true except for `purpose=None`, where it defaults to false.
- `setAsDefault` defaults to false.

`edit_metadata operation=create_form formType=ORDINARY` reaches the same creator. `ORDINARY` is
matched without regard to case, after trimming surrounding whitespace. Without explicit `ORDINARY`
the existing managed-form path applies. That route
passes `projectName`, `ownerFqn`, `purpose`, `synonym`, `title`, `setAsDefault`, `dryRun` and the
name, and refuses the arguments that shape the ordinary layout (`width`, `height`, `withFields`,
`withTables`, `withCommands`, `columns`, `tabularSections`, `language`) together with `layout`,
naming `ordinary_forms create_form` as the call that reads them.

## Layout and appearance

Layout writes accept versions 25, 26 and 27. `add_element` takes `type`, `name`, optional `parent`
and `page`, and the rectangle as `left`, `top`, `right`, `bottom` or `left`, `top`, `width`, `height`.
A data-bound element also takes `data`, using a path returned by `layout`. `caption` and
`tooltip` set the new element's text. `remove_element` takes `elementName`; `cascade=true` clears
command-panel action sources that refer to the removed element. Without it, those references
refuse removal.

Element types: `Label`, `Field`, `Button`, `CheckBox`, `Group`, `Panel`, `Table`, `RadioBtn`,
`Separator`, `Image`, `SelectField`, `CommandPanel`, `TableField`, `FieldHtml`, `ListField`,
`TextDocumentField` and `Chart`.

`set_property` selects `property`; `elementName` addresses an element, and its absence addresses
the form. The property registry describes which targets accept each property:

- `title`, `caption`, `tooltip`: text in `value`, with `language` for localized text.
- `box`: the rectangle of an element, as `left`, `top`, `right`, `bottom` or as `left`, `top`,
  `width`, `height`. `size`: the form's `width` and/or `height`.
- `visible`, `enabled`, `readOnly`: `value=true` or `value=false`.
- `tabs`: the panel's page-strip setting, `value=true` or `value=false`.
- `font`: `fontFamily`, `fontSize`, `bold`, `italic`, `underline`; `value=auto` restores automatic font selection.
- `textColor`, `backColor`, `borderColor`: `value=auto`, `#RRGGBB` or a style-element index.
- `alignment`: `value=left`, `center`, `right` or `auto`.
- `format`: a format string in `value`; an empty value clears it.
- `anchor`: `edge=left|top|right|bottom`, `value=form|none|<element>.<edge>` and `offset`.
- `editFormat`: returns a named refusal without changing the layout.

```json
{
  "operation": "set_property",
  "projectName": "my-project",
  "formFqn": "Catalog.Products.Form.ItemForm",
  "elementName": "NameLabel",
  "property": "caption",
  "value": "Product name",
  "language": "en",
  "dryRun": true
}
```

## Inspect a layout

`operation=inspect_layout` takes the same `projectName` and form address as `layout`. It returns:

- `findings`: entries with `check`, `severity`, `subject` and `detail`.
- `findingCount`, `errorCount`, `warningCount`, `elementCount`: result counts.
- `coverage`: entries with `check`, `status` and `detail`. Status is `performed`, `partial` or
  `notPerformed`.
- `note`: explains how partial coverage affects the result.

The checks include duplicate names and element IDs, missing page and data-binding targets,
missing handler procedures, missing command sources, invalid rectangles, missing anchor targets
and anchor cycles. Geometry warnings compare siblings on the same page and children with their
parent; group and panel frames are excluded from overlap pairs.

Coverage identifies unknown element records, the layout version, page references, handler
procedures, data paths, column paths, anchors and geometry. Read its `detail` alongside the findings:
a result without findings covers only the checks and records named there. Inspection does not
change the form.

## Preview, write and update

With a write-enabled preset, `dryRun=true` reports the proposed change without writing it.
For `import_from_infobase`, the preview checks the form address, its entry on the owner and the
presence of the project container when `module=project`. The actual import selects the application,
exports the source form and validates the container and handler bindings.

Read-only presets allow query operations and reject write operations, including their previews,
before project resolution. The tool carries one write capability, `ordinary_forms_writes`, which is
what those presets switch off. An explicit disable of the tool also applies to queries.

A module-only write preserves the layout entry; a layout-only write preserves the module entry.
A handler operation with stub creation can explicitly change both entries. Unknown entries retain
their bytes. An unchanged container returns its original bytes; a write producing the existing text
does not change the container or its timestamp. A modified container is not byte-identical as a whole.

`handlerChanges` governs a module write that takes away a procedure the form still names. `warn`
(default) lists those procedures and lets the write through, `refuse` keeps the container as it is.

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

A successful write updates the project and marks the owner for a subsequent database update.
Apply the change to the infobase with `infobase_admin operation=update_database`, after
`diagnostics operation=validate_for_export`. The write response and the database update are
separate results. Read the form again with `layout` and compare container entries with `diff_form`.

An ordinary form module is outside the BSL index: `code_search operation=call_hierarchy` and
`operation=object_references`, and `diagnostics operation=get_project_errors`, answer without it and
name the number of modules they did not count. `code_search operation=text_search` scans their
text. The project diagnostics hold no model of that module either - the platform checks it when the
infobase is updated.
