# right-interactive-delete-marked-predefined-data-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags roles other than the system/full-rights role that carry `InteractiveDeleteMarkedPredefinedData`, the right to permanently purge predefined items already marked for deletion.

## Why it matters
Predefined items back configuration logic that assumes they exist. Purging them is a one-way trip, and it should never be reachable from an ordinary business role - only from a role reserved for system maintenance.

## How to fix
Drop this right from user roles entirely and keep it solely on the full-rights/service role. Route routine cleanup of marked objects through a scheduled job supervised by an administrator rather than an interactive right.

## Example
```xml
<!-- Role: Manager - should NOT carry this right -->
<Rights>
  <Right>
    <Name>InteractiveDeleteMarkedPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Currencies</Object>
  </Right>
</Rights>
```
Keep it on the full-rights/service role only:
```xml
<!-- Role: FullRights -->
<Rights>
  <Right>
    <Name>InteractiveDeleteMarkedPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>
```
