# deprecated-procedure-outside-deprecated-region

**Category:** Code organization (deprecation)  ·  **Severity:** MINOR

Flags an exported method annotated `&Deprecated` that doesn't live inside a `#Region Deprecated` block nested within `#Region Public`.

## Why it matters
Standard 644 groups every deprecated method under one region specifically so it's obvious, at a glance, which parts of a module's public interface exist only for backward compatibility - and so they can eventually be removed together instead of hunting them down one by one.

## How to fix
Add (or locate) a `#Region Deprecated` inside `#Region Public`, and move every `&Deprecated`-annotated method into it.

## Example

```bsl
#Region Public

Function ActiveMethod() Export
    // current implementation
EndFunction

#Region Deprecated

&Deprecated("Use ActiveMethod instead")
Function OldMethod() Export
    // legacy implementation
EndFunction

#EndRegion

#EndRegion
```
