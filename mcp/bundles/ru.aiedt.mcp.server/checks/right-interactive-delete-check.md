# right-interactive-delete-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags non-administrative roles that carry `InteractiveDelete`, the right to remove an object straight from the UI without going through a deletion mark first.

## Why it matters
Interactive deletion is instant and irreversible from the user's point of view. Letting regular roles delete objects directly removes the safety margin that the mark-then-purge pattern is meant to provide.

## How to fix
Reserve `InteractiveDelete` for Administrator. Give regular roles `InteractiveSetDeletionMark` instead, and clear marked objects through a scheduled or administrator-run procedure.

## Example
```xml
<!-- Role: user-facing - should NOT carry direct interactive delete -->
<Rights>
  <Right>
    <Name>InteractiveDelete</Name>
    <Value>true</Value>
    <Object>Document.Invoice</Object>
  </Right>
</Rights>
```
Swap it for the deletion-mark right on regular roles, keep `InteractiveDelete` for Administrator:
```xml
<Rights>
  <Right>
    <Name>InteractiveSetDeletionMark</Name>
    <Value>true</Value>
    <Object>Catalog.Products</Object>
  </Right>
  <Right>
    <Name>InteractiveDelete</Name>
    <Value>false</Value>
    <Object>Catalog.Products</Object>
  </Right>
</Rights>
```
