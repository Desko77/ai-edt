# doc-comment-export-procedure-description-section-check

**Category:** Documentation comments  ·  **Severity:** MINOR

Flags an exported procedure or function whose doc comment has no description text before the `Parameters:` section, or has an empty one.

## Why it matters
The description is the part of the comment that actually explains what the method is for; a comment that jumps straight into `Parameters:` documents the signature but leaves the purpose unstated.

## How to fix
Add one or more sentences describing what the method does, placed before `Parameters:`, ending in a period.

## Example

```bsl
// Saves data to the database.
// Validates data before saving and raises an exception if validation fails.
//
// Parameters:
//  Data - Structure - data to save
Procedure SaveData(Data) Export
```
