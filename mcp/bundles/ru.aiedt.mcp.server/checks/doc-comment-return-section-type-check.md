# doc-comment-return-section-type-check

**Category:** Documentation comments  ·  **Severity:** Minor (Code style)

Flags a function's `// Returns:` section that has no type at all, or names a type the checker doesn't recognize.

## Why it matters

IDE completion and strict-typing analysis both read the Returns type; leaving it blank or misspelled breaks that tooling for every caller of the function.

## How to fix

Write a real 1C:Enterprise type name right after `Returns:` (comma-separate several types if the function can return more than one), followed by a short description.

## Example

```bsl
// Wrong: no type after Returns:
// Returns current user.
//
// Returns:
//
Function GetCurrentUser()

// Right: concrete type
// Returns current user.
//
// Returns:
//  CatalogRef.Users - current user reference
//
Function GetCurrentUser()
```
