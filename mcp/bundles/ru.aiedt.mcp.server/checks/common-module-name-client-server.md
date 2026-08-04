# common-module-name-client-server

**Category:** Naming conventions (common modules)  ·  **Severity:** CRITICAL

Flags a common module enabled for both client and server (`Server = True`, `Client (managed application) = True`, not a server-call module) whose name doesn't end in `ClientServer` (or `КлиентСервер`).

## Why it matters
A module that runs in both contexts can only use APIs available on both sides - no direct database access, no UI calls. The `ClientServer` suffix (standard 469) warns anyone editing the module that adding a server-only or client-only call will break it somewhere.

## How to fix
Rename the module to end with `ClientServer` (or `КлиентСервер`) and update all call sites. Keep only code inside it that genuinely works unchanged on both client and server - move database access or UI-specific logic to a dedicated server or client module instead.

## Example

```bsl
// Belongs in a ClientServer module - works on both sides
Function IsBlankString(Value) Export
    Return Not ValueIsFilled(Value);
EndFunction
```
