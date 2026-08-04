# right-delete-check

**Category:** Role Rights  ·  **Severity:** Critical (Security)

Flags non-administrative roles that carry the direct `Delete` right on an object, letting a user remove records outright instead of going through the deletion-mark workflow.

## Why it matters
Direct deletion is irreversible and skips the review step that the "mark for deletion, then purge later" pattern gives most 1C solutions. Spreading this right beyond administrators turns an accidental click into permanent data loss with no safety net.

## How to fix
Grant `Delete` only to administrative roles. Give regular users the interactive deletion-mark right instead, and let a controlled administrative process (manual or scheduled) purge marked objects afterward.

## Example
```xml
<!-- Role: user-facing - should NOT carry direct Delete -->
<Rights>
  <Right>
    <Name>Delete</Name>
    <Value>true</Value>
    <Object>Catalog.Products</Object>
  </Right>
</Rights>
```
Give regular users the mark-for-deletion right instead, and reserve `Delete` for Administrator:
```xml
<Rights>
  <Right>
    <Name>InteractiveSetDeletionMark</Name>
    <Value>true</Value>
    <Object>Catalog.Products</Object>
  </Right>
</Rights>
```
