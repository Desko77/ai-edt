# unknown-form-parameter-access-check

**Category:** BSL / forms  ·  **Severity:** Major (Error)

Flags access to `Parameters.X` inside a form module where `X` is not declared in that form's parameter list - typically a typo, a parameter that was later removed, or code copy-pasted from a different form.

## Why it matters
Nothing stops you from typing `Parameters.Onwer` instead of `Parameters.Owner` - it just silently returns nothing useful, or blows up at runtime depending on how it's used. Because the mistake compiles fine, it tends to surface late, often only when that exact code path finally runs in production.

## How to fix
Either add the missing parameter to the form's parameter list with the correct type, or fix the reference so it points at a parameter the form's callers actually declare and pass in.

## Example
```bsl
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    If Parameters.Property("Onwer") Then // typo - not declared on this form
        FillByOwner(Parameters.Onwer);
    EndIf;
EndProcedure
```
Fix the name (and confirm it is declared under Form Properties -> Parameters):
```bsl
&AtServer
Procedure OnCreateAtServer(Cancel, StandardProcessing)
    If Parameters.Property("Owner") Then
        FillByOwner(Parameters.Owner);
    EndIf;
EndProcedure
```
