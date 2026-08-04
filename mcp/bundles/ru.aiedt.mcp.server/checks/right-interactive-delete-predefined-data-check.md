# right-interactive-delete-predefined-data-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags any role - other than a full-rights maintenance role - that carries `InteractiveDeletePredefinedData`, the right to delete data items the developer defined as part of the configuration itself.

## Why it matters
This is one of the most dangerous rights available: it targets data the configuration's own logic relies on being present. Granting it broadly risks a user quietly breaking assumptions baked into the solution, with no obvious error until something downstream fails.

## How to fix
Disable this right everywhere except a full-rights role kept for maintenance. Predefined data should change through configuration updates authored by the developer, not through an end user's interactive delete action.

## Example
```xml
<!-- Role: Manager - should NOT carry this right -->
<Rights>
  <Right>
    <Name>InteractiveDeletePredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.PaymentMethods</Object>
  </Right>
</Rights>
```
Only a maintenance-only full-rights role may carry it, and only when truly needed:
```xml
<!-- Role: FullRights (maintenance only) -->
<Rights>
  <Right>
    <Name>InteractiveDeletePredefinedData</Name>
    <Value>true</Value>
    <Object>Catalog.Countries</Object>
  </Right>
</Rights>
```
