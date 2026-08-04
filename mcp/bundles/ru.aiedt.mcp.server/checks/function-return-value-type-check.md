# function-return-value-type-check

**Category:** Strict typing  ·  **Severity:** Major (Code style)

Under `@strict-types`, flags a function whose return type can't be pinned down - neither declared in a `Returns:` doc-comment section nor inferable from the code itself.

## Why it matters

Strict-typing analysis needs a concrete return type to validate every call site against; a function with no determinable type is effectively invisible to that analysis.

## How to fix

Add a `Returns:` section documenting the type(s), and make sure every `Return` statement in the function actually produces a value consistent with it.

## Example

```bsl
// @strict-types

// Gets configuration value.
//
// Returns:
//  String - configuration value
//
Function GetValue()
    Return SomeSetting;
EndFunction
```
