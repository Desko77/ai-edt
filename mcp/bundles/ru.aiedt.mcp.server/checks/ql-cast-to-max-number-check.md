# ql-cast-to-max-number-check

**Category:** Query Language  ·  **Severity:** Major

Flags `CAST(... AS NUMBER(...))` expressions in queries that cast to the platform's maximum precision/length instead of a value sized for the actual data.

## Why it matters
Casting to the maximum allowed precision (e.g. `NUMBER(31, 15)`) doesn't add safety - it just forces the DBMS to reserve more storage and comparison width than the data needs, and can produce unexpected rounding or overflow behavior compared to a precision chosen to fit the real value range.

## How to fix
Look at the actual range of values the field holds and cast to a precision/scale that comfortably covers it, not the theoretical maximum.

## Example

```bsl
// Bad
CAST(Products.Quantity AS NUMBER(31, 15))

// Good
CAST(Products.Quantity AS NUMBER(15, 3))
```
