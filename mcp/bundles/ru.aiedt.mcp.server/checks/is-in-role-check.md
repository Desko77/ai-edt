# is-in-role-check

**Category:** Roles and access rights  ·  **Severity:** Major (Warning, Standard 488)

Flags use of `IsInRole()` and points to `AccessRight()` as the preferred alternative.

## Why it matters

`IsInRole` ties code to one specific role's name, so it breaks the moment that role gets renamed or a right ends up being granted through a different role instead. `AccessRight()` checks the actual permission, regardless of which role happens to supply it.

## How to fix

Replace `IsInRole("RoleName")` checks with the equivalent `AccessRight("RightName", MetadataObject)` call. The `exceptionRoles` parameter can whitelist the rare cases where a genuinely role-specific check is intentional.

## Example

```bsl
// Wrong: fragile, must list every role that has the right
If IsInRole("Accountant") Or IsInRole("Manager") Then

// Right: works no matter which role grants the right
If AccessRight("Read", Metadata.Documents.Invoice) Then
```
