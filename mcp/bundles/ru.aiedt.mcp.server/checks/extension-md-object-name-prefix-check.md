# extension-md-object-name-prefix-check

**Category:** Extensions  ·  **Severity:** Major (Error)

Flags a metadata object newly created ("Own") inside an extension whose name doesn't start with that extension's configured prefix.

## Why it matters

Without the prefix, a new object introduced by the extension can collide with an object of the same name added later to the base configuration or to another extension.

## How to fix

Rename the object so it starts with the extension's prefix - IDE rename refactoring will update all references for you. Objects that are Adopted from the base configuration keep their original name; the prefix requirement only applies to objects the extension itself introduces.
