# invocation-form-event-handler

**Category:** Forms - events  ·  **Severity:** Major (Warning)

Flags code that calls a form event handler procedure directly instead of letting the platform invoke it.

## Why it matters

The platform passes specific parameter values into event handlers and then acts on what they set - for instance, it interprets `Cancel` to decide whether to actually stop the operation. Calling the handler by hand skips that processing entirely, so the outcome doesn't match what a real event would produce.

## How to fix

Move the handler's logic into a separate procedure, then have both the event handler itself and any other caller invoke that procedure - never the handler.

## Example

```bsl
// Wrong: calling the event handler directly
Procedure RefreshData()
    OnOpen(False);
EndProcedure

// Right: extract the logic, call the extracted procedure
Procedure OnOpen(Cancel)
    InitializeFormData();
EndProcedure

Procedure RefreshData()
    InitializeFormData();
EndProcedure
```
