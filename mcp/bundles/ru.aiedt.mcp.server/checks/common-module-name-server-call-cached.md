# common-module-name-server-call-cached

**Category:** Naming conventions (common modules)  ·  **Severity:** MINOR

Flags a common module that is both callable from the client (`ServerCall = True`) and has return-value caching enabled, but whose name doesn't end in `ServerCallCached` (or `ВызовСервераПовтИсп`).

## Why it matters
The combined suffix (standard 469) tells the reader two things about network behavior at once: the method crosses the client-server boundary, and after the first call its result comes from the local cache instead of another round trip. That distinction matters when deciding whether it's safe to call the method repeatedly or whether cached data might be stale.

## How to fix
Rename the module to end with `ServerCallCached` (or `ВызовСервераПовтИсп`) and update every call site. Reserve this pattern for data that changes rarely (reference lookups, configuration constants) - avoid caching anything that needs to reflect the current state, like balances or per-user notifications.

## Example

```bsl
// Before
Data = CatalogServiceServerCall.GetCurrencyList();

// After (module renamed to CatalogServiceServerCallCached)
Data = CatalogServiceServerCallCached.GetCurrencyList();
```
