# redundant-export-check

**Category:** API Design  ·  **Severity:** Minor

Flags `Export` procedures and functions that are never actually called from outside their own module.

## Why it matters
`Export` is supposed to mark a module's public contract. A method that carries `Export` but is only ever called internally misrepresents that contract, making it harder to tell what other modules, forms, or subsystems actually depend on.

## How to fix
Search the whole configuration for external references to the method; if none exist, drop `Export` and move the method into the `Private` region. Genuine external entry points (event subscriptions, scheduled jobs, HTTP services, form callbacks) should keep `Export`, and can be excluded from the check by name pattern if they read as unused due to indirect invocation.

## Example

```bsl
// Bad: only ever called from within the same module
Function GetData() Export
    Return LoadData();
EndFunction

// Good: no Export, moved to Private
Function GetData()
    Return LoadData();
EndFunction
```
