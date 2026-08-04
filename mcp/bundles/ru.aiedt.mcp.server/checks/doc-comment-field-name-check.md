# doc-comment-field-name-check

**Category:** Documentation comments  ·  **Severity:** MINOR

Flags a structure field name in a doc comment that isn't a valid identifier (starts with a digit, contains a hyphen, etc.) or that repeats another field's name within the same type definition.

## Why it matters
A field name that isn't a legal identifier, or that duplicates another field in the same structure, doesn't correspond cleanly to a real field - it undermines the doc comment's usefulness as an actual reference for what the structure contains.

## How to fix
Rename the field to a valid identifier - starts with a letter, only letters/digits/underscores after that, CamelCase - and make sure no two fields share a name within the same type definition.

## Example

```bsl
// Returns:
//  Structure:
//      * FirstName - String - first name
//      * LastName - String - last name
Function GetUserData()
```
