# ql-virtual-table-filters-check

**Category:** Query Language / Performance  ·  **Severity:** Major

Flags conditions on a virtual table (`Balance()`, `Turnovers()`, etc.) that are written in the query's `WHERE` clause instead of passed as the virtual table's own parameters.

## Why it matters
Virtual tables accept filter conditions as call parameters specifically so the platform can push them down efficiently when generating the underlying SQL. Filtering afterward in `WHERE` still returns correct results, but it hides that intent from the optimizer and can lead to noticeably worse execution plans.

## How to fix
Move every condition that relates to the virtual table into its parameter list; keep only conditions on other tables in `WHERE`.

## Example

```bsl
// Bad
"FROM AccumulationRegister.Stock.Balance() WHERE Warehouse = &Warehouse"

// Good
"FROM AccumulationRegister.Stock.Balance(, Warehouse = &Warehouse)"
```
