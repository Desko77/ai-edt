# server-execution-safe-mode-check

**Category:** BSL / server code  ·  **Severity:** Critical (Security)

Flags server-side calls to `Execute()` or `Eval()` that run without safe mode (`SetSafeMode(True)`) active around them.

## Why it matters
`Execute()` and `Eval()` run arbitrary dynamic code on the server. Without safe mode, that code has full access to the file system, COM objects, external connections and other privileged operations - so if the executed string ever traces back to user input, it is a direct code-injection path into the server process.

## How to fix
Wrap the call in `SetSafeMode(True)` / `SetSafeMode(False)`, and put it inside `Try/Except` so safe mode gets turned back off (and the error re-raised) even when the dynamic code fails. Where possible, avoid `Execute`/`Eval` altogether - a lookup table or explicit `If/ElsIf` branch over known method names is safer than building and running a string.

## Example
```bsl
&AtServer
Procedure ProcessDataAtServer()
    Execute(Parameters.CustomCode); // runs with full server privileges
EndProcedure
```
Guard it with safe mode and exception handling:
```bsl
&AtServer
Procedure ProcessDataAtServer()
    SetSafeMode(True);
    Try
        Execute(Parameters.CustomCode);
    Except
        SetSafeMode(False);
        Raise;
    EndTry;
    SetSafeMode(False);
EndProcedure
```
