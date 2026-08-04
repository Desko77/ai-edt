# right-start-thick-client-check

**Category:** Role Rights  ·  **Severity:** Major (Security)

Flags roles whose `StartThickClient` setting does not match how that role is expected to connect - the thick client has broader capabilities than the thin or web client, including direct file-system access.

## Why it matters
Because the thick client can touch the local file system and more of the machine it runs on, granting it indiscriminately widens what a compromised or careless session can do compared to working through the thin or web client.

## How to fix
Reserve `StartThickClient` for roles that genuinely need its extra capabilities (administrators, local power users); steer everyone else toward the thin client and disable this right for them, recording why any exception needs the thick client.

## Example
```xml
<!-- Role: ExternalUser - should NOT carry this right -->
<Rights>
  <Right>
    <Name>StartThickClient</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Point regular roles at the thin client instead:
```xml
<!-- Role: User -->
<Rights>
  <Right>
    <Name>StartThickClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartThinClient</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
