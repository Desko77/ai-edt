# module-structure-init-code-in-region-check

**Category:** Module Structure  ·  **Severity:** Minor

Flags module-level code (statements outside any procedure or function, executed when the module loads) that is not placed inside an `Initialize` region.

## Why it matters
Code that runs implicitly at module load time is easy to miss during review if it's scattered at the bottom of the file with no marker. Keeping it in a dedicated `Initialize` region makes startup behavior discoverable and predictable.

## How to fix
Move the loose top-level statements into `#Region Initialize ... #EndRegion`, placed after the other regions.

## Example

```bsl
#Region Initialize
IsInitialized = False;
ModuleCache = New Map;
InitializeModule();
#EndRegion
```
