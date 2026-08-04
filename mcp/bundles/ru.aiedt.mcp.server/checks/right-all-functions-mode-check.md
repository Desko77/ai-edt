# right-all-functions-mode-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags non-administrative roles that carry the `AllFunctionsMode` right, which opens a raw mode for reaching any configuration object directly.

## Why it matters
This mode exists to bypass the interface you built - subsystems, commands, form restrictions - and go straight at the underlying objects. Handing it to regular roles undoes whatever access boundaries the interface was designed to enforce.

## How to fix
Keep `AllFunctionsMode` on the Administrator role only. Whatever a role legitimately needs to reach, expose it through a proper subsystem or command rather than falling back on this bypass.

## Example
```xml
<!-- Role: PowerUser - should NOT carry this right -->
<Rights>
  <Right>
    <Name>AllFunctionsMode</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
Restrict it to Administrator:
```xml
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>AllFunctionsMode</Name>
    <Value>true</Value>
  </Right>
</Rights>
```
