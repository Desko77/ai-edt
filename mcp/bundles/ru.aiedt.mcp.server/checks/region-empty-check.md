# region-empty-check

**Category:** Code Smell  ·  **Severity:** Minor

Flags `#Region ... #EndRegion` blocks that contain no executable code - comments alone don't count as content.

## Why it matters
Empty regions are almost always leftovers: a template that scaffolded all standard regions up front, or a region whose contents were moved elsewhere during refactoring. They add navigation noise without providing any structure.

## How to fix
Delete the empty region, or fill it with the content it was meant to hold.

## Example

```bsl
// Bad
#Region Internal
#EndRegion

// Good: removed if nothing belongs there
```
