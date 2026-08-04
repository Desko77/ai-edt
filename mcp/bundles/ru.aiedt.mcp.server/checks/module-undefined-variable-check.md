# module-undefined-variable-check

**Category:** Static Analysis  ·  **Severity:** Critical

Flags references to variables that are not defined in the current scope.

## Why it matters
BSL creates a local variable on first assignment, so reading one before that first assignment - or reading a variable that only exists in a different procedure's scope - raises a runtime error. The usual causes are a typo, a variable that's only conditionally assigned, or code that assumes another procedure's local variable is visible.

## How to fix
Fix the spelling, initialize the variable unconditionally before any branch that might skip its assignment, or promote it to a module-level `Var` if it genuinely needs to be shared across procedures.

## Example

```bsl
// Bad: Value may never be assigned before use
If Condition Then
    Value = 10;
EndIf;
Result = Value;

// Good: always defined
Value = 0;
If Condition Then
    Value = 10;
EndIf;
Result = Value;
```
