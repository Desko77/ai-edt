# ql-using-for-update-check

**Category:** Query Language  ·  **Severity:** Minor

Flags use of the `FOR UPDATE` clause in query text.

## Why it matters
`FOR UPDATE` locks data at read time to avoid write conflicts later - a concept that belongs to unmanaged transaction locking. Under 1C's managed lock mode (the recommended approach), it has no effect and just adds noise to the query.

## How to fix
Remove the `FOR UPDATE` clause and set explicit locks in code with a `DataLock` object before reading the data that needs protecting.

## Example

```bsl
DataLock = New DataLock;
LockItem = DataLock.Add("AccumulationRegister.MutualSettlements");
LockItem.Mode = DataLockMode.Exclusive;
LockItem.SetValue("Document", DocumentRef);
DataLock.Lock();
```
