# module-structure-variables-in-region-check

**Category:** Module Structure  ·  **Severity:** Minor

Flags module-level `Var` declarations that are not placed inside the `Variables` region.

## Why it matters
Scattering `Var` declarations throughout a module makes it hard to see the module's full state at a glance; grouping them at the very top, in one region, gives that overview for free.

## How to fix
Move every module-level `Var` statement into `#Region Variables ... #EndRegion`, placed as the first region in the module.

## Example

```bsl
#Region Variables
Var Counter;
Var ModuleCache Export;
#EndRegion
```
