# form-list-field-ref-not-added-check

**Category:** Forms - dynamic lists  ·  **Severity:** Major (Error)

Flags a dynamic list table that doesn't include the Ref field among its columns.

## Why it matters

Opening, deleting, copying, or otherwise acting on the currently selected row all depend on its Ref. Without the field in the table, code that reads `CurrentData.Ref` simply has nothing to read.

## How to fix

Add a Ref field to the table - it's fine to keep it hidden - so the reference is loaded and available wherever the row's data is read.
