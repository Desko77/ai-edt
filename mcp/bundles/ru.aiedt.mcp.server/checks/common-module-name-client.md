# common-module-name-client

**Category:** Naming conventions (common modules)  ·  **Severity:** CRITICAL

Flags a common module restricted to the client (`Client (managed application) = True`, `Server = False`) whose name doesn't end in `Client` (or `Клиент`).

## Why it matters
Without the suffix, nothing in the name tells a developer that the module's code cannot run on the server - the mistake usually surfaces only when someone tries to call it from server-side code and it fails. Standard 469 uses this suffix specifically to prevent that class of mistake.

## How to fix
Rename the module to end with `Client` (or `Клиент`) and update every call site to use the new name.

## Example

```bsl
// Before
UIHelpers.ShowNotification("Done!");

// After (module renamed to UIHelpersClient)
UIHelpersClient.ShowNotification("Done!");
```
