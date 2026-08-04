# property-return-type-check

**Category:** Strict Types  ·  **Severity:** Major

Under `@strict-types`, flags dynamic property access (`Object.Field`) on a value whose documented return type doesn't specify that field's type - most commonly an untyped `Structure`.

## Why it matters
Strict-type inference needs to know the type of every field it hands out; a `Structure` return documented only as "Structure" with no field breakdown makes every property pulled from it untyped from that point forward.

## How to fix
Document the full field list in the producing function's `Returns:` block, giving each field its own `* FieldName - Type - description` line.

## Example

```bsl
// Returns:
//  Structure:
//      * Timeout - Number - timeout in seconds
//      * Server - String - server address
//
Function GetSettings()
    Return Settings;
EndFunction
```
