# method-optional-parameter-before-required-check

**Category:** BSL Method Design  ·  **Severity:** Major

Flags procedure and function signatures where a parameter with a default value appears before a parameter without one.

## Why it matters
BSL has no named-argument syntax, so parameters are matched positionally. If an optional parameter comes first, a caller can never skip it to reach the required one after it - they are forced to always pass a value for the "optional" parameter anyway, which defeats the point of giving it a default.

## How to fix
Reorder the signature so every required parameter comes first and every parameter with a default value follows, then update call sites.

## Example

```bsl
// Bad: Data is required but comes after the optional Format
Procedure ProcessData(Format = "XML", Data) Export

// Good: required parameter first
Procedure ProcessData(Data, Format = "XML") Export
```
