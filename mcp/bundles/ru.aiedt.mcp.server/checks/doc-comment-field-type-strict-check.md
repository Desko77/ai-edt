# doc-comment-field-type-strict-check

**Category:** Documentation comments / strict typing  ·  **Severity:** MAJOR

The strict-typing counterpart of `doc-comment-field-type-check`: in a module marked `@strict-types`, flags any structure field in a doc comment that has no type.

## Why it matters
Strict-typing mode exists to make every type on a module's public surface explicit and verifiable; an untyped field slipping through a doc comment is exactly the kind of gap that mode is meant to close, which is why it's treated as an error here rather than a style suggestion.

## How to fix
Give every field an explicit, concrete type - avoid `Arbitrary` - using the same `* FieldName - Type - Description` format as the non-strict check. This is required wherever `@strict-types` applies, even for fields that would otherwise be optional to type.

## Example

```bsl
// @strict-types

// Parameters:
//  Settings - Structure:
//      * Timeout - Number - timeout in seconds
Procedure Configure(Settings)
```
