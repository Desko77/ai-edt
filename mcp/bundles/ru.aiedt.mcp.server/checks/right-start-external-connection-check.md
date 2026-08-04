# right-start-external-connection-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags non-service roles that carry `StartExternalConnection`, the right to reach the infobase through an external COM connection.

## Why it matters
External connections are a common integration mechanism, but every role that has this right is another way into the infobase that bypasses the regular client interface and its guardrails. Handing it to ordinary users multiplies that exposure without a corresponding business need.

## How to fix
Keep `StartExternalConnection` on integration/service accounts (and Administrator for maintenance), and remove it from regular user roles. Track which accounts use external connections so unexpected ones stand out.

## Example
```xml
<!-- Role: User - should NOT carry this right -->
<Rights>
  <Right>
    <Name>StartExternalConnection</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Restrict it to the accounts that actually need it:
```xml
<!-- Role: IntegrationService -->
<Rights>
  <Right>
    <Name>StartExternalConnection</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
