# string-literal-type-annotation-check

**Category:** BSL  ·  **Severity:** Major (Error)

Flags plain string literals used where an actual type reference belongs - most often `TypeOf(Value) = "String"` instead of `TypeOf(Value) = Type("String")`.

## Why it matters
A bare string is just text as far as the compiler is concerned: no validation, no autocomplete, and no protection if the configuration's type gets renamed. A typo like `"Sting"` silently never matches anything, whereas `Type("String")` is checked and stays correct through refactoring.

## How to fix
Replace the string literal with `Type("TypeName")` in every type comparison, `TypeDescription` construction, or similar type-context usage.

## Example
```bsl
If TypeOf(Ref) = "CatalogRef.Products" Then // string, not validated
```
Use the `Type()` function instead:
```bsl
If TypeOf(Ref) = Type("CatalogRef.Products") Then
```
