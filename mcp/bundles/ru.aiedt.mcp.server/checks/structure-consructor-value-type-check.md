# structure-consructor-value-type-check

**Category:** Strict Types  ·  **Severity:** Major (Code style)

In strict-typed (`@strict-types`) modules, flags `New Structure(...)` keys that carry no value at all, or that are only ever set to `Undefined`, leaving the key's type unknowable.

## Why it matters
Strict typing depends on every structure key having a value type the checker can infer statically. A key declared with no initializer, or initialized only to `Undefined`, gives the checker nothing to work with and breaks that guarantee for the rest of the module.

## How to fix
Give every key an explicit, correctly-typed default (`""`, `0`, `False`, and so on) either in the constructor call or via `Insert`. For structures built up piece by piece, wrap the construction in a dedicated constructor function so the "shape" of the structure - keys plus types - is defined in one obvious place.

## Example
```bsl
// @strict-types
Result = New Structure("Name, Age"); // keys have no values at all
```
Give each key a typed default:
```bsl
// @strict-types
Function NewUserData()
    Result = New Structure;
    Result.Insert("Name", "");
    Result.Insert("Age", 0);
    Return Result;
EndFunction
```
