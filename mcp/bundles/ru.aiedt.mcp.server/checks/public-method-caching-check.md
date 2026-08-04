# public-method-caching-check

**Category:** API Design  ·  **Severity:** Major

Per standard #644, flags common modules configured with return-values reuse (session/call caching) that still expose a `Public` (external API) region instead of `Internal`.

## Why it matters
Caching is an implementation detail of a module, not part of its contract. A cached module advertised as `Public` invites other subsystems to depend on behavior (freshness, side effects) that caching can silently change, which breaks the isolation that library compatibility relies on.

## How to fix
Rename the module's `Public` region to `Internal` (`ПрограммныйИнтерфейс` -> `СлужебныйПрограммныйИнтерфейс`); if a genuinely public, non-cached API is needed, expose it through a separate wrapper module instead.

## Example

```bsl
// Common module with ReturnValuesReuse = AtSession
#Region Internal
Procedure GetData() Export
EndProcedure
#EndRegion
```
