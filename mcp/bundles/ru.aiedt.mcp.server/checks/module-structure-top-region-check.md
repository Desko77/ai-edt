# module-structure-top-region-check

**Category:** Module Structure  ·  **Severity:** Minor

Flags top-level regions whose name isn't one of the standard names for that module type, that appear out of the expected order, or that are duplicated.

## Why it matters
A consistent top-level region layout (`Public` then `Internal`/`EventHandlers` then `Private` then `Initialize`, or the form-specific equivalent) lets any developer navigate an unfamiliar module the same way every time.

## How to fix
Rename non-standard regions to their standard equivalent, reorder them to match the module type's expected sequence, and merge any duplicated region back into one.

## Example

```bsl
#Region Public
Procedure PublicMethod() Export
EndProcedure
#EndRegion

#Region Private
Procedure PrivateMethod()
EndProcedure
#EndRegion
```
