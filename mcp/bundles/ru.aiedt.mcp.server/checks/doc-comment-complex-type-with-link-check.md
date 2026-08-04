# doc-comment-complex-type-with-link-check

**Category:** Documentation comments  ·  **Severity:** MINOR

Flags a doc comment that spells out a complex type (Structure, ValueTable, and similar) field by field in a `Parameters:`/`Returns:` section, instead of linking to the constructor function that already documents that shape.

## Why it matters
Every place that repeats the same Structure's fields is another place that can drift out of sync the next time that structure changes. Keeping the full description on the one function that builds the value, and pointing everywhere else back to it, keeps a single source of truth.

## How to fix
Move the field-by-field description onto the constructor function (e.g. `NewDocumentData()`), and replace inline descriptions elsewhere with `<Type> - See <ConstructorFunctionName>`.

## Example

```bsl
// Returns:
//  Structure - See NewDocumentData
Function GetDocumentData()
    Return NewDocumentData();
EndFunction

// Returns:
//  Structure:
//   * Date - Date - document date
//   * Number - String - document number
Function NewDocumentData()
```
