# ql-constants-in-binary-operation-check

**Category:** Query Language  ·  **Severity:** Major

Flags binary operations (typically string concatenation with `+`) between literals or parameters inside query text, such as building a `LIKE` pattern by concatenating pieces at query-parse time.

## Why it matters
1C queries are meant to be portable across different DBMS backends, and expression evaluation at the query-text level (rather than at the BSL level) is one of the places where behavior can diverge between database engines. It's also just harder to read than a single literal.

## How to fix
Build the final string in BSL and pass it in as one query parameter, or merge adjacent literals into a single string literal directly in the query text.

## Example

```bsl
// Bad
"WHERE Products.Code LIKE ""123"" + ""%"""

// Good
"WHERE Products.Code LIKE ""123%"""
```
