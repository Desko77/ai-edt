# right-administration-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags any role other than Administrator that carries the `Administration` right - the platform's top-level right covering user management, backups and configuration updates.

## Why it matters
`Administration` is the single most powerful right in the system. Granting it outside the Administrator role effectively creates extra administrators, defeating the whole purpose of role-based access control and multiplying the blast radius of a compromised account.

## How to fix
Strip `Administration` from every role except Administrator. When a role genuinely needs a slice of administrative capability, grant the narrower right that matches it (for example `DataAdministration`) instead of the blanket right.

## Example
```xml
<!-- Role: Manager - should NOT carry this right -->
<Rights>
  <Right>
    <Name>Administration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Keep it on the Administrator role only:
```xml
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>Administration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
