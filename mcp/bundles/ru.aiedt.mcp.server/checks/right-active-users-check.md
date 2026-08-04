# right-active-users-check

**Category:** Role Rights  ·  **Severity:** Major (Security)

Flags roles other than administrative ones that carry the `ActiveUsers` right, which lets a user see who is currently logged into the system.

## Why it matters
The list of active sessions is operational/administrative information - who is online, from where, since when. Handing that visibility to ordinary user roles leaks information they have no business need for and widens the audience that can profile system usage.

## How to fix
Remove `ActiveUsers` from regular roles and keep it only on administrative ones. If a subset of users genuinely need partial visibility, build a narrower report instead of granting the platform right outright.

## Example
```xml
<!-- Role: Operator - should NOT carry this right -->
<Rights>
  <Right>
    <Name>ActiveUsers</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Restrict it to the administrative role instead:
```xml
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>ActiveUsers</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
