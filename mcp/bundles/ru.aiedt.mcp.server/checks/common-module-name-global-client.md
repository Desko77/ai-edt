# common-module-name-global-client

**Category:** Naming conventions (common modules)  ·  **Severity:** CRITICAL

Flags a client-side common module with `Global = True` (methods callable without a module prefix) whose name doesn't end in `GlobalClient` (or `ГлобальныйКлиент`).

## Why it matters
A global module's exported methods can be called bare, without the module name, which is convenient but makes it harder to tell which module a given call actually comes from. The `GlobalClient` suffix (standard 469) at least keeps the module itself identifiable, and distinguishes it from a global module that runs on the server instead.

## How to fix
Rename the module to end with `GlobalClient` (or `ГлобальныйКлиент`) and update any explicit (prefixed) references. Calls that already rely on the global namespace continue to work unprefixed.

## Example

```bsl
// Before (used with explicit prefix)
ClientHelpers.ShowNotification(Text);

// After (module renamed to ClientHelpersGlobalClient)
ClientHelpersGlobalClient.ShowNotification(Text);
```
