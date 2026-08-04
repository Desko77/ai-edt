# module-empty-method-check

**Category:** Code Smell  ·  **Severity:** Minor

Flags procedures and functions whose body has no executable statements.

## Why it matters
An empty method is either an abandoned stub, code that was accidentally deleted during a refactor, or a forgotten `TODO` - in all three cases it's misleading to anyone who reads the call site expecting behavior.

## How to fix
Implement the method, delete it if it's genuinely unused, or - if it is intentionally left as an extension point or interface stub - leave a short comment explaining why so the check can be waived for that case.

## Example

```bsl
// Bad
Procedure ItemOnChange(Item)
EndProcedure

// Good: implemented, or explicitly explained
Procedure ItemOnChange(Item)
    RecalculateTotals();
EndProcedure
```
