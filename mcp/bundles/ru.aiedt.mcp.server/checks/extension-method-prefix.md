# extension-method-prefix

**Category:** Extensions  ·  **Severity:** Major (Warning, Standard 640)

Flags a new procedure or function added inside an extension's module whose name doesn't start with the extension's prefix.

## Why it matters

An unprefixed helper can collide with a method the base configuration or another extension adds later, and it obscures which extension actually owns the code.

## How to fix

Prefix the method name and update its call sites. Methods that override an existing base method, and `&Before`/`&After` handlers that intentionally reuse the original method's name, are exempt from this rule.

## Example

```bsl
// Extension prefix: MyCompany_

// Wrong
Procedure ProcessProduct() Export
EndProcedure

// Right
Procedure MyCompany_ProcessProduct() Export
EndProcedure
```
