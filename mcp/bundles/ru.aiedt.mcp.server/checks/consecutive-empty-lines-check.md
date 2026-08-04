# consecutive-empty-lines-check

**Category:** Code style / formatting  ·  **Severity:** MINOR

Flags runs of more than one consecutive blank line in a BSL module - between procedures, inside a procedure body, or trailing at the end of the file.

## Why it matters
A single blank line is enough to separate logical blocks; stacking several adds visual noise without adding information and makes the file inconsistent with the rest of the codebase.

## How to fix
Collapse any run of blank lines down to exactly one (zero at the very start or end of the file). Most 1C:EDT setups can do this automatically via format-document (Ctrl+Shift+F) or a format-on-save action. The maximum allowed count is configurable through the `maxEmptyLines` parameter (default 1).

## Example

```bsl
// Before
Procedure A()
EndProcedure



Procedure B()
EndProcedure

// After
Procedure A()
EndProcedure

Procedure B()
EndProcedure
```
