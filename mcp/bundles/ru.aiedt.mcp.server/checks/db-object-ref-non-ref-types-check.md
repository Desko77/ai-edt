# db-object-ref-non-ref-types-check

**Category:** Database objects / query performance  ·  **Severity:** MAJOR

Flags the same underlying problem as `db-object-ref-non-ref-type` - a composite attribute type combining reference types with non-reference ones (`String`, `Number`, `Date`, `Boolean`, `UUID`, `ValueStorage`) - but frames it specifically around fields used in query joins, filters, and ordering.

## Why it matters
A field used in a `WHERE`/`JOIN`/`ORDER BY` needs a predictable, indexable type to be fast. When it can hold either a reference or a primitive value, the platform has to check which kind of value it's dealing with before it can even attempt a lookup, which defeats straightforward index usage and makes joins noticeably slower than they would be against a reference-only field.

## How to fix
Keep the attribute reference-only and move any primitive value it currently also carries into its own attribute (or, if the primitive really represents another linked entity, into a small lookup catalog referenced normally). Mixed value types remain intentional and fine for characteristic/value-type attributes designed for that purpose - the issue is specifically composite types on regular stored fields.

## Example

```xml
<mdclass:Document xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
  <name>Order</name>
  <attributes>
    <name>RelatedEntity</name>
    <type>
      <types>CatalogRef.Products</types>
      <types>DocumentRef.Invoice</types>
    </type>
  </attributes>
  <attributes>
    <name>ExternalCode</name>
    <type>
      <types>String</types>
      <stringQualifiers>
        <length>100</length>
      </stringQualifiers>
    </type>
  </attributes>
</mdclass:Document>
```
