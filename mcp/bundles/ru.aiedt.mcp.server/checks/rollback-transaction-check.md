# rollback-transaction-check

**Category:** BSL / transactions  ·  **Severity:** Critical (Error)

Flags calls to `RollbackTransaction()` that are misplaced: missing a matching `BeginTransaction()`, missing the paired `CommitTransaction()` on the success path, sitting outside a `Try/Except` block, or preceded by other code inside the `Except` handler.

## Why it matters
An open transaction that is never committed or rolled back correctly can leave data half-written and hold locks that lead to deadlocks for other sessions. If anything runs before `RollbackTransaction()` inside the `Except` block and that code itself fails, the rollback never executes at all - so the ordering inside `Except` is not a style preference, it is what makes the pattern actually safe.

## How to fix
Wrap the transaction body in `BeginTransaction()` / `Try...CommitTransaction()...Except...RollbackTransaction()...Raise;...EndTry`, and make `RollbackTransaction()` the very first statement in the `Except` block - logging, notifications and re-raising come after it, never before.

## Example
```bsl
// Wrong: logging runs before the rollback: if WriteLogEvent throws, rollback never happens
Except
    WriteLogEvent("Error");
    RollbackTransaction();
    Raise;
EndTry;
```
Correct pattern, rollback first:
```bsl
BeginTransaction();
Try
    Object.Write();
    CommitTransaction();
Except
    RollbackTransaction();
    WriteLogEvent("WriteData", EventLogLevel.Error, , , ErrorDescription());
    Raise;
EndTry;
```
