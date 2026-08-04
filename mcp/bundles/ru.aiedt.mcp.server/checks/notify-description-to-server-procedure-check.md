# notify-description-to-server-procedure-check

**Category:** Client-Server Boundaries  ·  **Severity:** Critical

Flags a `NotifyDescription` constructed with the name of a server-side (`&AtServer` / `&AtServerNoContext`) procedure as its callback.

## Why it matters
`NotifyDescription` is the platform's mechanism for resuming client-side code after an asynchronous client operation (file dialogs, `ShowQueryBox`, file transfer, etc.) completes. The callback it invokes must live on the client - pointing it at a server procedure means the callback can never actually run, and the async operation silently goes nowhere.

## How to fix
Point the `NotifyDescription` at a client procedure; have that client procedure call the server explicitly afterward if server-side work is still needed.

## Example

```bsl
&AtClient
Procedure ConfirmDeletion()
    NotifyHandler = New NotifyDescription("ConfirmDeletionResultAtClient", ThisObject);
    ShowQueryBox(NotifyHandler, "Delete selected items?", QuestionDialogMode.YesNo);
EndProcedure

&AtClient
Procedure ConfirmDeletionResultAtClient(Result, AdditionalParameters) Export
    If Result = DialogReturnCode.Yes Then
        DeleteItemsAtServer();
    EndIf;
EndProcedure
```
