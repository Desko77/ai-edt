# data-exchange-load

**Category:** Object modules / data exchange  ·  **Severity:** MAJOR

Flags object-module event handlers - `BeforeWrite`, `OnWrite`, `Posting`, `UndoPosting` and similar - that never check `DataExchange.Load` before running their business logic.

## Why it matters
Standard 773 expects incoming exchange/replication data to skip validation, calculations, and related-object updates that were already handled on the source system - that data is arriving pre-validated. Without the check, every import re-runs full business logic on every object, which both wastes time and can reject data that was perfectly valid on the sending side.

## How to fix
Add `If DataExchange.Load Then Return; EndIf;` as the first statement in the handler, right after any bookkeeping that genuinely must run regardless of the write's origin (like stamping a modification timestamp). Everything else - validation, totals, posting logic - goes after that check, never before it.

## Example

```bsl
Procedure BeforeWrite(Cancel, WriteMode, PostingMode)
    If DataExchange.Load Then
        Return;
    EndIf;

    If Not ValueIsFilled(Customer) Then
        Cancel = True;
        Message("Customer is required!");
    EndIf;
EndProcedure
```
