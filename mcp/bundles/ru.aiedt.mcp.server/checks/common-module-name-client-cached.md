# common-module-name-client-cached

**Category:** Naming conventions (common modules)  ·  **Severity:** MINOR

Flags a common module that runs on the client only (`Server = False`, `Client (managed application) = True`) and has return-value caching enabled, but whose name doesn't end in `ClientCached` (or `КлиентПовтИсп`).

## Why it matters
The suffix packs two facts into the module name at once - it only runs client-side, and its results are cached for the session or call - both of which change how a caller should reason about the values it returns. Standard 469 expects that combination to be visible in the name, not hidden in the module properties.

## How to fix
Rename the module to end with `ClientCached` (or `КлиентПовтИсп`) and update every reference to the new name.

## Example

```bsl
// Before
Settings = UISettingsClient.GetUserSettings();

// After (module renamed to UISettingsClientCached)
Settings = UISettingsClientCached.GetUserSettings();
```
