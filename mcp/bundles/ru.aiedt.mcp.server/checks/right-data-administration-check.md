# right-data-administration-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags non-administrative roles that carry `DataAdministration`, the right covering infobase-wide maintenance: purging marked-for-deletion objects, restructuring, and testing/repair operations.

## Why it matters
These operations act on the whole infobase at once and can destroy or restructure data outside any single document or catalog. That scope makes the right suitable for scheduled maintenance and administrators, not for day-to-day user roles.

## How to fix
Remove `DataAdministration` from user-facing roles and keep it on Administrator. For recurring cleanup, wire it into a scheduled job instead of granting the interactive right broadly.

## Example
```xml
<!-- Role: Manager - should NOT carry this right -->
<Rights>
  <Right>
    <Name>DataAdministration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Keep it on Administrator only:
```xml
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>DataAdministration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
