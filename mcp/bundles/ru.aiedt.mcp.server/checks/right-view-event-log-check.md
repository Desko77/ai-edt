# right-view-event-log-check

**Category:** Role Rights  ·  **Severity:** Major (Security)

Flags non-administrative roles that carry `ViewEventLog`, the right to read the system's event log of user actions and system events.

## Why it matters
The event log records who did what across the whole system - including actions by other users. Opening it to regular roles leaks that audit trail to people it was never meant for and can expose sensitive operational detail.

## How to fix
Restrict `ViewEventLog` to Administrator and any dedicated auditor role. If regular users need visibility into a narrow slice of activity, build a scoped report instead of exposing the raw log.

## Example
```xml
<!-- Role: User - should NOT carry this right -->
<Rights>
  <Right>
    <Name>ViewEventLog</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Keep it on Administrator and a dedicated auditor role:
```xml
<!-- Role: Auditor -->
<Rights>
  <Right>
    <Name>ViewEventLog</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
