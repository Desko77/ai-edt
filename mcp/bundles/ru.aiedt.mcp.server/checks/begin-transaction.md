# begin-transaction

**Category:** Transaction handling  ·  **Severity:** MINOR

Flags a `BeginTransaction()` (`НачатьТранзакцию()`) call that is not immediately followed by `Try`, or that has other statements sitting between it and the `Try` block.

## Why it matters
If the code between `BeginTransaction` and `Try` throws, the transaction is never rolled back and can leave a lock on the database or an inconsistent state behind. The standard pattern exists precisely so every code path after a transaction starts is covered by exception handling.

## How to fix
Put `Try` right after `BeginTransaction()`. Move anything that was sitting between them into the `Try` block. Make sure the matching `Except` rolls back the transaction, logs the error, and re-raises it to the caller.

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
```
