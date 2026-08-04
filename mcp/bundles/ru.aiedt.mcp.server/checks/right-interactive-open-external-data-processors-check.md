# right-interactive-open-external-data-processors-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags non-administrative roles that carry `InteractiveOpenExtDataProcessors`, the right to load and run an external data processor file from the interface.

## Why it matters
An external data processor is a standalone file that can contain any code its author chose to put in it. A role that can open one can effectively run arbitrary code inside the infobase, making this right a direct security exposure wherever it is granted too broadly.

## How to fix
Restrict this right to Administrator. For legitimate business needs, route external tools through SSL's additional-data-processors catalog, which adds a place to vet and approve them before use.

## Example
```xml
<!-- Role: Operator - should NOT carry this right -->
<Rights>
  <Right>
    <Name>InteractiveOpenExtDataProcessors</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Keep it on Administrator only:
```xml
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>InteractiveOpenExtDataProcessors</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
