# lock-out-of-try-check

**Category:** Data locking  ·  **Severity:** Major (Error)

Flags a `DataLock.Lock()` call that isn't wrapped inside a Try block.

## Why it matters

Acquiring a lock can fail - timeout, conflict, deadlock - and failure throws an exception. Without a surrounding Try, that exception goes unhandled instead of being reported or retried gracefully.

## How to fix

Wrap `Lock.Lock()`, and the work that depends on it, in Try/Except, and either handle the exception meaningfully or re-raise it - don't leave the except block empty.

## Example

```bsl
// Wrong: Lock() can fail here, unhandled
Lock.Lock();
DataObject.Write();

// Right
Try
    Lock.Lock();
    DataObject.Write();
Except
    Raise;
EndTry;
```
