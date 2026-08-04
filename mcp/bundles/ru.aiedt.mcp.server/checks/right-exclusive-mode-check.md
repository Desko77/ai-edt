# right-exclusive-mode-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags non-administrative roles that carry the `ExclusiveMode` right, which lets a user force the infobase into exclusive mode and kick every other session out.

## Why it matters
Switching to exclusive mode disrupts every other connected user at once - it is an operational sledgehammer, not something a regular role should be able to trigger on a whim.

## How to fix
Restrict `ExclusiveMode` to Administrator. Where possible, rely on more targeted locking mechanisms instead of forcing everyone else off the system, and note down the specific cases where exclusive mode is genuinely required.

## Example
```xml
<!-- Role: Accountant - should NOT carry this right -->
<Rights>
  <Right>
    <Name>ExclusiveMode</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Keep it on Administrator only:
```xml
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>ExclusiveMode</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
