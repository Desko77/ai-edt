# not-support-goto-operator-web-check

**Category:** Web Client Compatibility  ·  **Severity:** Blocker

Flags use of the `Goto` operator in code that can run on the web client (`&AtClient`, `&AtClientAtServer`, `&AtClientAtServerNoContext`).

## Why it matters
The web client compiles BSL down to JavaScript, which has no equivalent of `Goto`. Code that relies on it simply fails at runtime in a browser, even though it compiles and runs fine on a thick client.

## How to fix
Rewrite the jump using structured control flow - `While`/`For`/`For Each` for loops, `If`/`Else` for conditional branches, `Return`/`Break`/`Continue` for early exits - and verify the result by testing it in the web client.

## Example

```bsl
// Bad
&AtClient
Procedure Process()
    I = 0;
    ~Start:
    I = I + 1;
    DoWork(I);
    If I < 10 Then Goto ~Start; EndIf;
EndProcedure

// Good
&AtClient
Procedure Process()
    For I = 1 To 10 Do
        DoWork(I);
    EndDo;
EndProcedure
```
