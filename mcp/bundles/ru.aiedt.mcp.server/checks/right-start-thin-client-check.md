# right-start-thin-client-check

**Category:** Role Rights  ·  **Severity:** Minor (Security)

Flags roles where `StartThinClient` is turned off without an equivalent connection method enabled elsewhere - the thin client is the recommended default way most users should connect.

## Why it matters
The thin client is the safer, lighter-weight baseline compared to the thick client. A role with it disabled and no other client right enabled simply cannot connect at all, and even when another client is enabled, disabling the thin client without reason usually just reflects an inconsistent setup rather than an intentional policy.

## How to fix
Keep `StartThinClient` enabled for most roles. When a role is meant to be web-only or thick-client-only, disable it deliberately and make sure at least one client right stays enabled, and capture the reasoning in your client-usage policy.

## Example
```xml
<!-- Role: Operator - no client right enabled at all, role cannot connect -->
<Rights>
  <Right>
    <Name>StartThinClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartThickClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartWebClient</Name>
    <Value>false</Value>
  </Right>
</Rights>
```
Keep the thin client on by default, or swap in a deliberate alternative:
```xml
<!-- Role: ExternalUser - web-only by design -->
<Rights>
  <Right>
    <Name>StartThinClient</Name>
    <Value>false</Value>
  </Right>
  <Right>
    <Name>StartWebClient</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
