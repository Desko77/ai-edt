# doc-comment-type-check

**Category:** Documentation comments  ·  **Severity:** Minor (Code style)

Flags a type name written in a Parameters or Returns section that doesn't match any known 1C:Enterprise type - usually a typo or a stale reference to a renamed or removed object.

## Why it matters

An unresolved type name silently breaks IDE hints and any static analysis that trusts the comment.

## How to fix

Correct the spelling, or confirm that a referenced catalog/document/etc. still exists under that exact name.

## Example

```bsl
// Wrong: "Strng" is a typo
// Parameters:
//  Value - Strng - some value
//
Procedure Process(Value)

// Right: correct type name
// Parameters:
//  Value - String - some value
//
Procedure Process(Value)
```
