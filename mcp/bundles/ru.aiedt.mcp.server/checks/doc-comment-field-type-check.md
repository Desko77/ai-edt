# doc-comment-field-type-check

**Category:** Documentation comments  ·  **Severity:** MINOR

Flags a structure field listed in a doc comment that has no type after it - just the bare field name, or a trailing hyphen with nothing following it.

## Why it matters
The type is what makes a field entry actually informative; a name on its own doesn't tell a reader (or any tooling parsing the comment) what kind of value to expect there.

## How to fix
Add the type right after the field name, separated by a hyphen: `* FieldName - Type - Description` (the description is optional, the type is not).

## Example

```bsl
// Returns:
//  Structure:
//      * Name - String - user name
//      * Age - Number - user age
Function GetUserData()
```
