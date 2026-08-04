# form-module-missing-pragma

**Category:** Forms - compilation directives  ·  **Severity:** Minor (Code style, Standard 748)

Flags a form module method that has no compilation directive at all - no `&AtClient`, `&AtServer`, or similar.

## Why it matters

Without an explicit directive, the method's execution context isn't obvious from reading the code, which makes it easy to misuse and harder to reason about client/server round trips.

## How to fix

Add the directive that matches what the method actually does: `&AtServer` or `&AtServerNoContext` for database access, `&AtClient` for UI work, `&AtClientAtServerNoContext` for pure calculations that don't touch either.

## Example

```bsl
// Wrong: no directive at all
Function CalculateTotal()
    Return Items.Total("Amount");
EndFunction

// Right: explicit directive
&AtServer
Function CalculateTotal()
    Return Items.Total("Amount");
EndFunction
```
