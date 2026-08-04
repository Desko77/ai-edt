# ql-join-to-sub-query-check

**Category:** Query Language / Performance  ·  **Severity:** Major

Flags `JOIN` clauses whose right-hand side is an inline subquery rather than a temporary table.

## Why it matters
The query optimizer generally has a much harder time producing a good execution plan for a join against an ad-hoc subquery than for a join against a materialized temporary table, and this tends to show up as real slowdowns once data volume grows.

## How to fix
Move the subquery into its own batch that writes `INTO` a temporary table, then join against that temporary table in the following batch (add an index on the join key if the table is large).

## Example

```bsl
"SELECT ProductPrices.Product, MAX(ProductPrices.Price) AS Price
|INTO TempPrices
|FROM InformationRegister.ProductPrices AS ProductPrices
|GROUP BY ProductPrices.Product
|;
|SELECT Products.Name, Prices.Price
|FROM Catalog.Products AS Products
|LEFT JOIN TempPrices AS Prices ON Products.Ref = Prices.Product"
```
