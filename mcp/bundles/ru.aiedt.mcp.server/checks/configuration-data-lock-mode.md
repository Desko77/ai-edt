# configuration-data-lock-mode

**Category:** Configuration / concurrency  ·  **Severity:** MAJOR

Flags a configuration whose **Data lock control mode** property is set to `Automatic` or `AutomaticAndManaged` instead of `Managed`.

## Why it matters
Under automatic locking the platform decides what to lock on its own, and it tends to lock more broadly than the code actually needs - entire tables instead of individual records - which increases contention and produces lock-wait errors under concurrent load. Managed mode puts that decision in the developer's hands via explicit `DataLock` objects, which scales much better in a multi-user system.

## How to fix
Switch the configuration's data lock mode to `Managed`, then go through existing transactions and add explicit `DataLock` calls wherever the platform used to lock implicitly - typically right before reading or writing the records a transaction depends on.

## Example

```bsl
BeginTransaction();
Try
    Lock = New DataLock;
    LockItem = Lock.Add("Document.Invoice");
    LockItem.SetValue("Ref", DocumentRef);
    Lock.Lock();

    DocumentObject = DocumentRef.GetObject();
    DocumentObject.Write();

    CommitTransaction();
Except
    RollbackTransaction();
    Raise;
EndTry;
```
