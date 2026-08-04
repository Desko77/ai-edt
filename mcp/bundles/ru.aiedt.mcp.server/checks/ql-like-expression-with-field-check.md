# ql-like-expression-with-field-check

**Category:** Query Language  ·  **Severity:** Major

Flags a `LIKE` (or `ESCAPE`) comparison whose right-hand operand is a table field instead of a literal or parameter.

## Why it matters
`LIKE` patterns built from another field's runtime value defeat the DBMS's ability to use an index for the comparison and are prohibited by the platform's query rules; the pattern needs to be known at query-compile time.

## How to fix
Build the pattern in BSL and pass it as a query parameter, or use a fixed string literal directly in the query text.

## Example

```bsl
// Bad
"WHERE Products.Code LIKE Products.SearchPattern"

// Good
"WHERE Products.Code LIKE &SearchPattern"
```
