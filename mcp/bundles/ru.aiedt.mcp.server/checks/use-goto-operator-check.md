# use-goto-operator-check

**Category:** BSL  ·  **Severity:** Major (Code smell)

Flags use of the `Goto` operator together with its labels (`~Label:`).

## Why it matters
Jump-based control flow is harder to trace than loops, branches and exceptions - following where execution goes next means scanning the whole procedure for labels instead of reading top to bottom. On top of that, `Goto` is not supported in the web client at all, so code relying on it can fail outright on that platform.

## How to fix
Replace the goto pattern with its structured equivalent: a `For`/`While` loop instead of a jump-back label, `Return`/`Break`/`Continue` for early exits, `Try/Except` for error handling, and a flag combined with `Break` (or an extracted function using `Return`) to escape nested loops.

## Example
```bsl
For I = 1 To N Do
    For J = 1 To M Do
        If Found Then Goto ~Exit; EndIf;
    EndDo;
EndDo;
~Exit:
```
A small helper function replaces the jump with a normal `Return`:
```bsl
Function FindElement()
    For I = 1 To N Do
        For J = 1 To M Do
            If Found Then
                Return New Structure("I, J", I, J);
            EndIf;
        EndDo;
    EndDo;
    Return Undefined;
EndFunction
```
