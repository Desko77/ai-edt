# form-module-pragma-check

**Category:** Forms - compilation directives  ·  **Severity:** Major (Code smell)

Flags a form module method whose compilation directive doesn't match what it actually does - for example an `&AtClient` procedure calling `Object.Write()`, or an `&AtServer` procedure calling `ShowMessageBox`.

## Why it matters

A mismatched directive either fails outright at runtime, because the operation isn't available in that context, or silently forces an unnecessary client-server round trip.

## How to fix

Choose the directive that fits the method's real work - database access needs a server directive, UI needs `&AtClient` - and split the method into separate client and server procedures if it genuinely needs both.

## Example

```bsl
// Wrong: Write() requires server context
&AtClient
Procedure SaveDataAtClient()
    Object.Write();
EndProcedure

// Right
&AtServer
Procedure SaveDataAtServer()
    Object.Write();
EndProcedure
```
