# right-interactive-open-external-reports-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags non-administrative roles that carry `InteractiveOpenExtReports`, the right to load and run an external report file from the interface.

## Why it matters
Just like external data processors, external reports are standalone files that can carry arbitrary code. Letting ordinary roles open them turns the "run a report" action into a potential code-execution path.

## How to fix
Restrict this right to Administrator. Where users need extra reports, add them to SSL's additional-reports catalog after review, instead of letting roles load arbitrary external report files directly.

## Example
```xml
<!-- Role: Analyst - should NOT carry this right -->
<Rights>
  <Right>
    <Name>InteractiveOpenExtReports</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Keep it on Administrator only:
```xml
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>InteractiveOpenExtReports</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
