# is-in-role-method-role-exist-check

**Category:** Roles and access rights  ·  **Severity:** Major (Error)

Flags a string literal passed to `IsInRole()` that doesn't match any role actually defined in the configuration.

## Why it matters

Catches typos and stale references left behind after a role was renamed or deleted - problems that would otherwise stay invisible until they throw at runtime.

## How to fix

Correct the role name, or better, replace the call with `AccessRight()` entirely. Note that the check can only see string-literal arguments; role names built dynamically at runtime aren't verified.

## Example

```bsl
// Wrong: typo, role doesn't exist under this name
If IsInRole("FullAcess") Then

// Right
If IsInRole("FullAccess") Then
```
