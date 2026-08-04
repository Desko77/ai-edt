# ql-camel-case-string-literal-check

**Category:** Query Language  ·  **Severity:** Minor

Flags field and table aliases in query text that don't follow CamelCase (e.g. lowercase or `snake_case` aliases).

## Why it matters
Query aliases end up in `QueryResult` column names and are read the same way as any other identifier in code; inconsistent casing makes generated result structures harder to work with alongside the rest of the naming convention.

## How to fix
Rename the alias so each word starts with a capital letter and there are no underscores.

## Example

```bsl
// Bad
"SELECT Products.Name AS product_name FROM Catalog.Products AS Products"

// Good
"SELECT Products.Name AS ProductName FROM Catalog.Products AS Products"
```
