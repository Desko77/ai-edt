# right-start-automation-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags non-service roles that carry `StartAutomation`, the right to connect to the infobase via COM/OLE Automation.

## Why it matters
An OLE Automation connection gives a caller programmatic control over the infobase from outside the normal client. Leaving this open on user roles turns any machine that can reach the credentials into a potential automation client, which is a much larger attack surface than the UI alone.

## How to fix
Remove `StartAutomation` from regular user roles and keep it only on dedicated integration/service accounts. Where practical, prefer web or HTTP services for integration instead of COM automation, and keep a record of who legitimately needs this connection type.

## Example
```xml
<!-- Role: Developer - should NOT carry this right by default -->
<Rights>
  <Right>
    <Name>StartAutomation</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Restrict it to a dedicated integration account:
```xml
<!-- Role: IntegrationService -->
<Rights>
  <Right>
    <Name>StartAutomation</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
