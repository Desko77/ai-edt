# common-module-name-cached

**Category:** Naming conventions (common modules)  ·  **Severity:** MINOR

Flags a common module that has return-value reuse (caching) turned on - `ReturnValuesReuse` set to "during session" or "during call" - but whose name doesn't end in `Cached` (or `ПовтИсп` in Russian).

## Why it matters
Standard 469 uses the module name to signal its behavior. A cached module can return stale data from a previous call instead of recomputing it, which is important context for anyone reading or debugging code that calls it - the suffix makes that visible without opening the module's properties.

## How to fix
Append `Cached` (or `ПовтИсп`) to the module name - combined with any other suffix it already needs, e.g. `ServerCallCached` - and update every call site to the new name.

## Example

```bsl
// Before
Result = CommonUtilities.GetValue(Key);

// After (module renamed to CommonUtilitiesCached)
Result = CommonUtilitiesCached.GetValue(Key);
```
