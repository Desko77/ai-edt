# module-accessibility-at-client-check

**Category:** Client-Server Boundaries  ·  **Severity:** Major

Per standard #680, flags object, manager and record set modules whose variables and procedures are not wrapped in a preprocessor guard that restricts them to server-side execution contexts.

## Why it matters
These module types are meant to run only on the server (or in a thick client / external connection). Without the guard, their code is technically reachable from thin/web client compilation, which the platform does not support for this module kind and which can produce confusing runtime failures.

## How to fix
Wrap the whole module body in a `#If Server Or ThickClientOrdinaryApplication Or ExternalConnection Then ... #Else ... #EndIf` block that raises an error in the `#Else` branch, or move individual methods behind `&AtServer`.

## Example

```bsl
#If Server Or ThickClientOrdinaryApplication Or ExternalConnection Then

Procedure DoSomething() Export
EndProcedure

#Else
    Raise NStr("en = 'Invalid client call of object.'");
#EndIf
```
