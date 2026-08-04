# event-data-exchange-load

**Category:** Object events  ·  **Severity:** Major (Portability, Standard 773)

Flags `BeforeWrite`, `OnWrite`, `BeforeDelete`, `Posting`, and `UndoPosting` handlers that don't check `DataExchange.Load` and return early.

## Why it matters

Without the check, business validation and side effects re-run for every record arriving through data exchange or distributed infobase synchronization - which can reject perfectly valid incoming data or duplicate work that already happened on the sending side.

## How to fix

Add `If DataExchange.Load Then Return; EndIf;` as the first statement in the handler. Any logic that must still run during exchange (like stamping a modification date) belongs before this check, not after.

## Example

```bsl
Procedure BeforeWrite(Cancel)
    If DataExchange.Load Then
        Return;
    EndIf;

    If Not ValueIsFilled(Date) Then
        Cancel = True;
    EndIf;
EndProcedure
```
