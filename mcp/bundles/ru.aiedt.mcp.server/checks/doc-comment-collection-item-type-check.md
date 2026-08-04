# doc-comment-collection-item-type-check

**Category:** Documentation comments  ·  **Severity:** MINOR

Flags a documented collection type - `Array`, `Map`, `ValueList`, `FixedArray`, `FixedMap` - in a `Parameters:` or `Returns:` doc-comment section that doesn't say what's inside it, e.g. plain `Array` instead of `Array of CatalogRef.Products`.

## Why it matters
"Array" on its own tells a reader nothing about what to expect from the collection; the item type is what actually makes the documentation usable as an API reference instead of a restatement of the variable's outer type.

## How to fix
Append the item type after the collection type: `Array of <ItemType>`, `Map of KeyAndValue` with a nested `* Key -` / `* Value -` description, `ValueList of <ItemType>`. Prefer a concrete type over `Arbitrary` whenever the actual type is known.

## Example

```bsl
// Returns:
//  Array of CatalogRef.Products - list of products
Function GetProducts()
```
