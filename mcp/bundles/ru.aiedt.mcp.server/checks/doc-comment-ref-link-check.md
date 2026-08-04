# doc-comment-ref-link-check

**Category:** Documentation comments  ·  **Severity:** Minor (Code style)

Flags a `See` (`См.`) reference inside a documentation comment that points to a method or metadata object the checker cannot resolve.

## Why it matters

A broken reference is worthless as documentation, and usually signals the target was renamed, moved, or deleted after the comment was written.

## How to fix

Fix the typo or update the path so it points at something that actually exists. If a `See` inside the description section is intentional, allow it via the `allowSeeInDescription` option instead of suppressing the check entirely.

## Example

```bsl
// Wrong: target method does not exist
// See Common.NonExistentMethod
//
Function GetData()

// Right: target resolves
// See Common.GetCurrentUser
//
Function GetData()
```
