# doc-comment-redundant-parameter-section-check

**Category:** Documentation comments  ·  **Severity:** Minor (Code style)

Flags a `// Parameters:` block left in the documentation comment of a method that takes no arguments.

## Why it matters

An empty or pointless Parameters section is dead weight - it adds nothing and makes the comment longer than the method it describes.

## How to fix

Remove the Parameters section entirely when the method has no parameters. Keep only the description line, plus a Returns section for functions.

## Example

```bsl
// Wrong: Parameters section with nothing to document
// Returns current date.
//
// Parameters:
//
// Returns:
//  Date - current date
//
Function GetCurrentDate()

// Right: Parameters section dropped
// Returns current date.
//
// Returns:
//  Date - current date
//
Function GetCurrentDate()
```
