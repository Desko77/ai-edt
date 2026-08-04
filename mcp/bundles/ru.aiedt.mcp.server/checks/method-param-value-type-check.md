# method-param-value-type-check

**Category:** Strict Types  ·  **Severity:** Major

Under the `@strict-types` annotation, flags method parameters whose value type is not documented in the procedure/function's header comment.

## Why it matters
The strict-typing tooling relies entirely on documentation comments to know what type a parameter holds; an untyped parameter breaks type inference for every expression built from it downstream.

## How to fix
Add a `Parameters:` block to the method's doc comment with one line per parameter in the form `ParamName - Type - description`, using a concrete type rather than `Arbitrary` whenever possible.

## Example

```bsl
// @strict-types

// Saves user settings.
//
// Parameters:
//  User - String - user name
//  Settings - Structure - user settings
//
Procedure SaveSettings(User, Settings)
```
