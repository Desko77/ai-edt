# md-standard-attribute-synonym-empty-check

**Category:** Metadata  ·  **Severity:** Minor

Flags catalogs whose standard `Owner` or `Parent` attribute has no synonym filled in.

## Why it matters
`Owner` and `Parent` are generic technical names. Without a synonym, forms, reports and query results show the literal word "Owner" or "Parent" to the end user instead of a business term like "Category" or "Parent Department", which is confusing and hurts localization.

## How to fix
Open the catalog's standard attributes and set a business-meaningful synonym for `Owner` and/or `Parent` (add translations for every configuration language in use).

## Example

```xml
<standardAttributes>
  <name>Owner</name>
  <synonym>
    <key>en</key>
    <value>Category</value>
  </synonym>
</standardAttributes>
```
