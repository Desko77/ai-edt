# common-module-missing-api

**Category:** Common modules  ·  **Severity:** MINOR

Flags a common module that has no exported procedure or function at all - meaning it exposes no public API for anything else to call.

## Why it matters
A common module exists to be reused elsewhere in the configuration; per 1C standard 455, if none of its methods are marked `Export`, nothing outside the module can actually use it, which usually means the module is either dead code or missing the interface it was created for.

## How to fix
Add the `Export` keyword to the methods meant to be called from other modules (typically organized under a `#Region Public` or `#Region Internal` section). If the module genuinely has no external callers, consider moving its logic into the module(s) that use it or removing it from the configuration.

## Example

```bsl
#Region Public

Function Sum(A, B) Export
    Return A + B;
EndFunction

#EndRegion
```
