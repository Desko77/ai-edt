# invocation-parameter-type-intersect-check

**Category:** Strict typing  ·  **Severity:** Major (Code style)

Under `@strict-types`, flags a call where the argument's type has no overlap at all with the parameter's declared type(s).

## Why it matters

Catches obvious type mismatches - passing a String where a Number is expected, or a reference to the wrong catalog - before they surface as a runtime error.

## How to fix

Pass a value of a compatible type, or broaden the parameter's declared type set if it should legitimately accept more than one type. The `allowDynamicTypesCheckForLocalMethodCall` option can relax this for local calls.

## Example

```bsl
// Parameters:
//  Amount - Number
//
Procedure ProcessAmount(Amount)
EndProcedure

Value = "text";
ProcessAmount(Value);  // wrong: String does not intersect with Number
```
