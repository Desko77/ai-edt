# form-commands-single-action-handler

**Category:** Forms - commands  ·  **Severity:** Major (Warning, Standard 455 section 2.4.3)

Flags a form command whose action handler is already assigned to a different command.

## Why it matters

Forcing one procedure to serve two commands means it has to branch on which command actually fired, which tangles unrelated logic together, weakens each command's single responsibility, and makes the handler more fragile to change.

## How to fix

Give each command its own dedicated handler. If the two commands genuinely need to do the same thing, extract that shared behavior into a separate procedure and call it from both handlers.

## Example

```bsl
&AtClient
Procedure Command1(Command)
    DoCommonAction();
EndProcedure

&AtClient
Procedure Command2(Command)
    DoCommonAction();
EndProcedure

&AtClient
Procedure DoCommonAction()
    // shared logic lives here, not in either command's handler
EndProcedure
```
