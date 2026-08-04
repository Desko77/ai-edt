# use-non-recommended-methods-check

**Category:** BSL  ·  **Severity:** Minor (Code smell)

Flags calls to platform methods that still work but have been superseded by a better alternative - synchronous dialog and file methods (`DoMessageBox`, `GetFile`, `OpenFormModal`, `TextDocument.Read`), `CurrentDate()`, `Message()`, and similar.

## Why it matters
Most of the flagged methods are older synchronous APIs being phased out in favor of asynchronous, callback-based equivalents. Staying on them risks losing the call in a future platform version and misses the improvements the replacements bring - session/timezone-aware dates, non-blocking file transfers, notification-style messages instead of blocking dialogs.

## How to fix
Swap in the recommended replacement: `CurrentSessionDate()` for `CurrentDate()`, `ShowUserNotification`/`ShowMessageBox` for `Message`/`DoMessageBox`, `BeginGettingFiles`/`BeginPuttingFiles` for `GetFile`/`PutFile`, `OpenForm(..., Handler)` for `OpenFormModal`. Most of these swaps mean moving the follow-up logic into a `NotifyDescription` callback instead of expecting a return value inline.

## Example
```bsl
&AtClient
Procedure Notify()
    DoMessageBox("Operation complete!"); // blocking, deprecated
    ContinueProcessing();
EndProcedure
```
Async version with a callback:
```bsl
&AtClient
Procedure Notify()
    Handler = New NotifyDescription("NotifyComplete", ThisObject);
    ShowMessageBox(Handler, "Operation complete!");
EndProcedure

&AtClient
Procedure NotifyComplete(AdditionalParameters) Export
    ContinueProcessing();
EndProcedure
```
