# module-undefined-method-check

**Category:** Static Analysis  ·  **Severity:** Critical

Flags calls to procedures or functions that cannot be resolved in the current scope.

## Why it matters
Same failure mode as an undefined function, just covering the procedure form: the call passes review as plain text but raises a runtime error the moment it executes, most often from a typo, a deleted or renamed method, or a missing module reference.

## How to fix
Verify the spelling, confirm the method is actually defined and reachable (same module, or an `Export` method on a referenced common/object module), and check that it's valid for the current client/server compilation context.

## Example

```bsl
// Bad: server-only method called from client code without a wrapper
&AtClient
Procedure ClientProcedure()
    LoadDataFromDatabase();
EndProcedure
```
