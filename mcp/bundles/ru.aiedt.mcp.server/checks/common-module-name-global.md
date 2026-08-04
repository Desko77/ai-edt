# common-module-name-global

**Category:** Naming conventions (common modules)  ·  **Severity:** CRITICAL

Flags a server-side common module with `Global = True` (its exported methods land in the global namespace and can be called without a module prefix) whose name doesn't end in `Global` (or `Глобальный`).

## Why it matters
Global modules let you call `DoSomething()` instead of `ModuleName.DoSomething()`, which is convenient but risks name collisions and makes it unclear which module actually provides a given method. Standard 469 requires the suffix so the module itself stays identifiable even though its methods don't need a prefix to be called.

## How to fix
Rename the module to end with `Global` (or `Глобальный`) and update explicit references. As a general practice, prefer calling global-module methods with the explicit module prefix anyway - it keeps the origin of the call obvious even though the platform doesn't require it.

## Example

```bsl
// Before (used with explicit prefix)
ServerUtilities.ProcessData(Value);

// After (module renamed to ServerUtilitiesGlobal)
ServerUtilitiesGlobal.ProcessData(Value);
```
