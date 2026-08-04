# ql-temp-table-index

**Category:** Query Language / Performance  ·  **Severity:** Major

Flags temporary tables used in a `JOIN` or inside an `IN (...)` subquery without an `INDEX BY` clause on the fields involved.

## Why it matters
Without an index, every join or `IN` lookup against the temp table forces a full scan of it; for anything beyond a trivial number of rows this becomes a measurable cost. Tables under roughly 1000 rows are small enough that indexing them isn't worth it.

## How to fix
Add `INDEX BY <fields>` right after the temp table's `INTO` definition, covering the fields used on the join condition or the `IN (...)` left-hand side.

## Example

```bsl
"SELECT Orders.Product AS Product, Orders.Quantity AS Quantity
|INTO TempOrders
|FROM Document.SalesOrder.Products AS Orders
|INDEX BY Product"
```
