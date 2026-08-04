# module-undefined-function-check

**Category:** Static Analysis  ·  **Severity:** Critical

Flags calls to functions that cannot be resolved in the current scope.

## Why it matters
A call to a function that doesn't exist compiles fine as free-form BSL text but throws at runtime - typically caused by a typo, a function removed during cleanup, a rename that missed one call site, or a missing common-module prefix.

## How to fix
Check the spelling against the actual definition, confirm the function still exists, add the common-module prefix if it's an export function from elsewhere (`Module.Function()`), and confirm it's available in the current compilation context (server vs. client).

## Example

```bsl
// Bad: module prefix missing
Result = CalculatePrice();

// Good
Result = PricingModule.CalculatePrice();
```
