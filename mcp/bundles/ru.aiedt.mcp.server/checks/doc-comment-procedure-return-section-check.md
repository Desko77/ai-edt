# doc-comment-procedure-return-section-check

**Category:** Documentation comments  ·  **Severity:** Minor (Code style)

Flags a `// Returns:` block inside a procedure's documentation comment. Procedures never hand back a value, so a Returns section on one is always wrong.

## Why it matters

A Returns section on a procedure promises a result that will never exist, misleading anyone who reads the comment or relies on it for tooling.

## How to fix

Delete the Returns section from the procedure's comment. If the method genuinely needs to return something, convert it to a Function and document Returns there.

## Example

```bsl
// Wrong: procedure with a Returns section
// Clears cache.
//
// Returns:
//  Boolean - success flag
//
Procedure ClearCache()

// Right: no Returns section on a procedure
// Clears cache.
//
Procedure ClearCache()
```
