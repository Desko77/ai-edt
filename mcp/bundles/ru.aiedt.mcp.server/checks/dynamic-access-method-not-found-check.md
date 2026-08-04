# dynamic-access-method-not-found-check

**Category:** Strict typing  ·  **Severity:** Major (Code style)

Under `@strict-types`, flags a dynamically-called method that does not exist on the resolved type of the object it's called on.

## Why it matters

This turns a typo like calling `SetParamter` instead of `SetParameter`, or calling a method that belongs to a different type entirely, into an analysis-time error instead of a runtime crash.

## How to fix

Correct the method name, double-check that the object is really the type you think it is, or set the `skipSourceObjectTypes` option to exclude objects the checker can't reliably resolve.

## Example

```bsl
// @strict-types
Query = New Query;
Query.SetParamter("Name", Value);  // wrong: typo, method not found

// @strict-types
Query = New Query;
Query.SetParameter("Name", Value);  // right
```
