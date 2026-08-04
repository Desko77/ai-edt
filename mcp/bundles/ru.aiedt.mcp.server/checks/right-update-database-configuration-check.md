# right-update-database-configuration-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags any role other than the full-rights role that carries `UpdateDatabaseConfiguration`, the right to change the database structure itself.

## Why it matters
Structural configuration updates are the most invasive operation available - they reshape the schema everything else depends on. Even a dedicated Administrator role usually should not carry this separately from the full-rights role reserved for deployment/maintenance, let alone developer or user roles in a production environment.

## How to fix
Keep this right on the full-rights role alone, run configuration updates through a controlled, scheduled process, and keep a change log of when the structure was updated and by whom.

## Example
```xml
<!-- Role: Developer - should NOT carry this right in production -->
<Rights>
  <Right>
    <Name>UpdateDatabaseConfiguration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Keep it on the full-rights role only, not even on a general Administrator role:
```xml
<!-- Role: FullRights -->
<Rights>
  <Right>
    <Name>UpdateDatabaseConfiguration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
