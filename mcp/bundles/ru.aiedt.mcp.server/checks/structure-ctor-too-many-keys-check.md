# structure-ctor-too-many-keys-check

**Category:** BSL  ·  **Severity:** Minor (Code smell)

Flags a `New Structure("A, B, C, ...")` call whose comma-separated key list is longer than the configured maximum (3 keys by default).

## Why it matters
Matching a long flat list of key names against a parallel list of positional values is easy to get wrong - a review or edit can shift one value out of alignment with its key without any error, just a silently wrong field.

## How to fix
Past a handful of keys, build the structure with individual `.Insert("Key", Value)` calls so each key sits next to its value. For very wide structures, consider grouping related fields into sub-structures or a dedicated constructor function. Small structures of two or three keys are fine to keep inline.

## Example
```bsl
// hard to tell which value belongs to which key
Data = New Structure("Name, Code, Price, Quantity, Amount, Discount, Tax, Total",
    ProductName, ProductCode, UnitPrice, Qty, LineAmount, DiscountPct, TaxAmount, LineTotal);
```
`Insert` keeps each pair together and easy to review:
```bsl
Data = New Structure;
Data.Insert("Name", ProductName);
Data.Insert("Code", ProductCode);
Data.Insert("Price", UnitPrice);
Data.Insert("Quantity", Qty);
```
