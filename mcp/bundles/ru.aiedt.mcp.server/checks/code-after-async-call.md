# code-after-async-call

**Category:** Async / client code  ·  **Severity:** MAJOR

Flags client-side code that appears directly after an asynchronous call (`ShowMessageBox`, `ShowQuestion`, `BeginPutFile`, and similar) - such lines run immediately, without waiting for the asynchronous operation to finish.

## Why it matters
1C's async client methods return control right away instead of blocking, so any statement written after one executes before the user has answered a dialog or the background operation has completed - the code looks sequential but does not behave that way, which leads to logic that runs on stale or missing results.

## How to fix
Move the follow-up logic into a `NotifyDescription` callback passed to the async method, or - on platform 8.3.18+ - use an `Async` procedure with `Await` so the continuation genuinely waits for the result.

## Example

```bsl
&AtClient
Procedure Test()
    Notification = New NotifyDescription("AfterQuestion", ThisObject);
    ShowQuestion(Notification, "Continue?", QuestionDialogMode.YesNo);
EndProcedure

&AtClient
Procedure AfterQuestion(Result, AdditionalParameters) Export
    If Result = DialogReturnCode.Yes Then
        Process();
    EndIf;
EndProcedure
```
