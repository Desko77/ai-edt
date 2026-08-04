# doc-comment-field-in-description-suggestion-check

**Category:** Documentation comments  ·  **Severity:** MINOR

Flags text in a doc comment's free-text description that looks like a structure field definition (a line such as `* Name - String - user name`) sitting outside the `Returns:`/`Parameters:` type section where it belongs.

## Why it matters
A field written into the plain description isn't recognized as part of the structured type definition - it reads like documentation but doesn't actually register as one, so it's effectively lost to anything that relies on the `Returns:`/`Parameters:` section being complete.

## How to fix
Move the field line out of the description and into the proper type block, indented with `*` under the relevant `Returns:` or `Parameters:` entry.

## Example

```bsl
// Returns structure with user settings.
//
// Returns:
//  Structure:
//      * Name - String - user name
//      * Age - Number - user age
Function GetUserSettings()
```
