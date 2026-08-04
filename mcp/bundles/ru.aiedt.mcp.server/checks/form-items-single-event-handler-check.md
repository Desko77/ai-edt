# form-items-single-event-handler-check

**Category:** Forms - events  ·  **Severity:** Minor (Code style, Standard 455)

Flags a handler procedure that is wired up to more than one form event or more than one form item.

## Why it matters

A handler shared across events has to branch on which event actually triggered it, which mixes unrelated concerns and makes the code more brittle every time one of the events changes.

## How to fix

Give each element/event its own generated-name handler, and if they genuinely share logic, extract that logic into a separate procedure both handlers call.

## Example

```bsl
&AtClient
Procedure FieldOnChange(Item)
    ProcessFieldChange();
EndProcedure

&AtClient
Procedure FieldOnActivate(Item)
    ProcessFieldActivation();
EndProcedure
```
