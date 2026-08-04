# bsl-canonical-pragma

**Category:** BSL syntax / annotations  ·  **Severity:** BLOCKER

Flags a method pragma (`&AtClient`, `&Before`, `&Around`, ...) whose letter case does not match its canonical spelling.

## Why it matters
Pragma names are meant to be written one specific way; mixing case (`&ATCLIENT`, `&atserver`, `&Atclient`) is inconsistent with the rest of the codebase and undermines the readability and tooling support that a fixed convention provides.

## How to fix
Rewrite the pragma using its canonical form - `&AtClient`, `&AtServer`, `&AtServerNoContext`, `&AtClientAtServer`, `&AtClientAtServerNoContext` for regular modules, and `&Before`, `&After`, `&Around`, `&ChangeAndValidate` for extension modules (same rule applies to their Russian equivalents, e.g. `&НаКлиенте`). Most 1C:EDT installs offer a quick-fix for this via the lightbulb / `Ctrl+1`.

## Example

```bsl
// Before
&atclient
Procedure Test()
EndProcedure

// After
&AtClient
Procedure Test()
EndProcedure
```
