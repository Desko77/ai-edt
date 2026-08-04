# module-unused-method-check

**Category:** Dead Code  ·  **Severity:** Minor

Flags procedures and functions defined in a module but never called from anywhere within that same module.

## Why it matters
An uncalled method is usually leftover code from a removed feature, but the check can also legitimately misfire on platform event handlers, `Export` methods called from other modules, `NotifyDescription` callbacks, and dynamically dispatched (`Execute`-based) calls - all of which look "unused" from a single-module analysis.

## How to fix
Search the whole configuration for references before deleting anything. If the method truly has no caller, remove it; if it's a legitimate handler/callback that the checker can't see, exclude it via the `excludeMethodNamePattern` setting instead of suppressing the whole check.

## Example

```
excludeMethodNamePattern = On.*|Before.*|After.*|.*Callback
```
