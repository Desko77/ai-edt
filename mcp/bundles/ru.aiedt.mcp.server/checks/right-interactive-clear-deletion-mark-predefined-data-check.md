# right-interactive-clear-deletion-mark-predefined-data-check

**Category:** Role Rights  ·  **Severity:** Major (Security)

Flags non-administrative roles that carry `InteractiveClearDeletionMarkPredefinedData`, the right to lift a deletion mark from a predefined (developer-defined) item.

## Why it matters
Predefined items are part of the configuration's baseline data; letting ordinary roles flip their deletion state interactively invites accidental or unreviewed changes to data the configuration expects to always be present.

## How to fix
Take this right away from user-facing roles and leave it on Administrator. Any change to predefined data's deletion state should be a deliberate, documented action, not an incidental interactive one.

## Example
```xml
<!-- Role: Operator - should NOT carry this right -->
<Rights>
  <Right>
    <Name>InteractiveClearDeletionMarkPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.PaymentTypes</Object>
  </Right>
</Rights>
```
Keep it on Administrator only:
```xml
<!-- Role: Administrator -->
<Rights>
  <Right>
    <Name>InteractiveClearDeletionMarkPredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>
```
