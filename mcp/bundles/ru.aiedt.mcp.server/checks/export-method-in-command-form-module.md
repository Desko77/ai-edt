# export-method-in-command-form-module

**Category:** Module structure  ·  **Severity:** Minor (Warning, Standard 544)

Flags Export procedures and functions declared in form or command modules.

## Why it matters

Form and command modules can't be addressed from outside themselves, so Export has no real effect there - it just misleads readers into thinking the method is part of a public API.

## How to fix

Drop Export from methods that are only ever called from within the same form. If the logic genuinely needs to be reusable, move it to a common module instead. Attachable callback handlers (methods named like `Attachable_*` and wired up through `NotifyDescription`) are a legitimate exception and should keep Export.

## Example

```bsl
// Wrong: Export is meaningless in a form module
Function CalculateTotal() Export
    Return Object.Items.Total("Amount");
EndFunction

// Right: no Export needed for an internal form method
Function CalculateTotal()
    Return Object.Items.Total("Amount");
EndFunction
```
