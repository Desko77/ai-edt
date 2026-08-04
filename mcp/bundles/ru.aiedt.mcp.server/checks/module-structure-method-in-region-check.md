# module-structure-method-in-region-check

**Category:** Module Structure  ·  **Severity:** Minor

Flags procedures and functions that sit outside the standard regions for their module type, and mismatches between a method's `Export` keyword and the region it's in (export methods outside `Public`/`Internal`, or non-export methods inside them).

## Why it matters
The region a method lives in is supposed to communicate its visibility at a glance: `Public` and `Internal` are the module's API surface, `Private` is implementation detail. A method in the wrong region misrepresents its own contract to callers.

## How to fix
Place `Export` methods meant for external use in `Public`, `Export` methods meant only for the same subsystem in `Internal`, and everything else in `Private`; make sure the `Export` keyword itself matches that placement.

## Example

```bsl
#Region Public
Procedure ProcessData(Data) Export
EndProcedure
#EndRegion

#Region Private
Procedure ValidateData(Data)
EndProcedure
#EndRegion
```
