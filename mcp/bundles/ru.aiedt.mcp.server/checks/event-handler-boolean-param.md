# event-handler-boolean-param

**Category:** Object/form events  ·  **Severity:** Major (Warning)

Flags code that resets `Cancel` back to `False`, or `StandardProcessing`/`Perform` back to `True`, inside an event handler.

## Why it matters

Several handlers can run for the same event in sequence. Explicitly resetting a boolean parameter back to its starting value can silently undo a decision an earlier handler already made - for example, un-canceling an operation another handler just canceled.

## How to fix

Only ever assign the "action" value: `Cancel = True`, or `StandardProcessing`/`Perform = False`. Never assign the opposite - it's already the default, so writing it just risks overwriting someone else's decision.

## Example

```bsl
// Wrong: Else branch can override an earlier Cancel = True
Procedure BeforeWrite(Cancel)
    If SomeCondition Then
        Cancel = True;
    Else
        Cancel = False;
    EndIf;
EndProcedure

// Right: only ever set the action value
Procedure BeforeWrite(Cancel)
    If Not ValidateDocument() Then
        Cancel = True;
    EndIf;
EndProcedure
```
