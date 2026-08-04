# right-interactive-set-deletion-mark-predefined-data-check

**Category:** Role Rights  ·  **Severity:** Major (Security)

Flags non-administrative roles that carry `InteractiveSetDeletionMarkPredefinedData`, the right to mark a predefined (developer-defined) catalog item for deletion.

## Why it matters
Predefined items anchor configuration logic that expects them to stay in place. Letting ordinary roles flag them for deletion opens the door to breaking that logic through what looks like a routine interactive action.

## How to fix
Disable this right for regular roles and keep it on Administrator only - changing the state of predefined data should stay under administrative control.

## Example
```xml
<!-- Role: regular user - should NOT carry this right -->
<Rights>
  <Right>
    <Name>InteractiveSetDeletionMarkPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Units</Object>
  </Right>
</Rights>
```
Keep it on Administrator only:
```xml
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>InteractiveSetDeletionMarkPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>
```
