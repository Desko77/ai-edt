# commit-transaction

**Category:** Transaction handling  ·  **Severity:** MINOR

Flags a `CommitTransaction()` (`ЗафиксироватьТранзакцию()`) that sits outside a `Try` block, has no matching `BeginTransaction()`, has statements between it and `Except`, or whose `Except` block is empty or missing the `RollbackTransaction()` call.

## Why it matters
Each of these deviations breaks the transaction contract: committing outside `Try` leaves nothing to catch a failure, code placed after `Commit` but before `Except` can itself throw and go unhandled, and an empty or rollback-less `Except` silently swallows errors while leaving the database locked.

## How to fix
Wrap `BeginTransaction`/`CommitTransaction` in a `Try` block with `CommitTransaction()` as the very last statement inside `Try`. Any logic that must run after a successful commit belongs after `EndTry`, not between `Commit` and `Except`. The `Except` block must always call `RollbackTransaction()`, then log and re-raise the error.

## Example

```bsl
BeginTransaction();
Try
    ProcessData();
    CommitTransaction();
Except
    RollbackTransaction();
    WriteLogEvent("MyModule.Test", EventLogLevel.Error, , , ErrorDescription());
    Raise;
EndTry;
LogSuccess(); // runs after EndTry, not between Commit and Except
```
