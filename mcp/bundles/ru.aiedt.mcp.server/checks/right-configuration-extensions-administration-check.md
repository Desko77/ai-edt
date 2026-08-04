# right-configuration-extensions-administration-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags non-administrative roles that carry `ConfigurationExtensionsAdministration`, the right to install, remove and manage configuration extensions.

## Why it matters
An extension can contain arbitrary code that runs inside the infobase. A role that can install extensions can, in effect, deploy new code into the system - that's a code-execution capability disguised as a configuration right, not something to spread across ordinary roles.

## How to fix
Limit this right to the Administrator role and treat every extension installation as a deliberate, reviewed administrative action rather than something a broad set of roles can trigger.

## Example
```xml
<!-- Role: Developer - should NOT carry this right -->
<Rights>
  <Right>
    <Name>ConfigurationExtensionsAdministration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Keep it on Administrator only:
```xml
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>ConfigurationExtensionsAdministration</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
