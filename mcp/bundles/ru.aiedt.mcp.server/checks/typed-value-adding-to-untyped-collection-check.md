# typed-value-adding-to-untyped-collection-check

**Category:** Strict Types  ·  **Severity:** Major (Code style)

In `@strict-types` modules, flags a collection (`Array`, `ValueList`, `Map`) whose type annotation does not declare an element type, when a typed value is actually being added to it.

## Why it matters
Strict typing needs to know what a collection holds to check the code that consumes it later. An `Array` annotated only as `Array` gives the checker nothing to work with once you add a `CatalogRef.Products` to it - every downstream use of that element is effectively untyped too.

## How to fix
Annotate the collection with its element type in the surrounding doc comment: `Array of <Type>`, `ValueList of <Type>`, or `Map of KeyAndValue:` with nested `* Key` / `* Value` type lines.

## Example
```bsl
// @strict-types
// Returns:
//  Array  ← no element type declared
Function GetProducts()
    Result = New Array;
    Result.Add(Product);
    Return Result;
EndFunction
```
Declare what the array actually contains:
```bsl
// @strict-types
// Returns:
//  Array of CatalogRef.Products - list of products
Function GetProducts()
    Result = New Array;
    Result.Add(Product);
    Return Result;
EndFunction
```
