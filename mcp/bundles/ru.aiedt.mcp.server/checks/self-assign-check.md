# self-assign-check

**Category:** BSL  ·  **Severity:** Major (Error)

Flags assignments where the left and right side are the same variable or property (`Value = Value;`, `Object.Name = Object.Name;`) - a statement that changes nothing.

## Why it matters
A self-assignment does not affect program state, so it almost never appears on purpose. It usually means a copy-paste left a line unedited, a rename during refactoring missed one spot, or a variable name was typed wrong - and the real bug is whatever assignment *should* have happened instead.

## How to fix
Work out what the line was meant to do and fix the right-hand side to reference the intended source (often a similarly named variable, or a different object), or delete the line entirely if it turns out to be leftover debug/dead code. Note that compound forms like `Value = Value + 1` are not self-assignment - they do change the value and are fine.

## Example
```bsl
// Copy-paste left the last line unedited
Target.Field1 = Source.Field1;
Target.Field2 = Source.Field2;
Target.Field3 = Target.Field3; // should read Source.Field3
```
Fixed:
```bsl
Target.Field1 = Source.Field1;
Target.Field2 = Source.Field2;
Target.Field3 = Source.Field3;
```
