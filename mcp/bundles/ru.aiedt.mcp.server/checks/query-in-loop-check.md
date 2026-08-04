# query-in-loop-check

**Category:** Performance  ·  **Severity:** Critical

Per standard #735, flags a database query - or a call to a method that itself contains a query - executed inside a `For`, `For Each`, or `While` loop.

## Why it matters
Each loop iteration that runs a query is a separate database round-trip; on a few dozen rows this is unnoticeable, but on realistic data volumes it turns an operation that should take a fraction of a second into one that takes minutes.

## How to fix
Replace the per-iteration query with a single batch query - using `IN (&ArrayOfRefs)`, a temporary table, or a join - executed once before the loop, and iterate over its result (or a `Map` built from it) instead.

## Example

```bsl
// Bad
For Each OrderRef In OrderRefs Do
    Query.SetParameter("Ref", OrderRef);
    Result = Query.Execute(); // one round-trip per order
EndDo;

// Good
Query.SetParameter("OrderRefs", OrderRefs);
Query.Text = "SELECT * FROM Document.SalesOrder WHERE Ref IN (&OrderRefs)";
Result = Query.Execute(); // one round-trip total
```
