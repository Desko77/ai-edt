# module-unused-local-variable-check

**Category:** Dead Code  ·  **Severity:** Minor

Flags local variables that are declared or assigned but never subsequently read.

## Why it matters
An unused variable is either a debugging leftover, a copy-paste remnant, or a sign that some intended logic was never wired up - in every case it adds noise without adding value.

## How to fix
Delete the variable if it's genuinely not needed, or use it if it was meant to feed into later logic that was accidentally dropped.

## Example

```bsl
// Bad: TempValue is assigned and never used
TempValue = GetValue();
Result = Calculate();

// Good
Result = Calculate();
```
