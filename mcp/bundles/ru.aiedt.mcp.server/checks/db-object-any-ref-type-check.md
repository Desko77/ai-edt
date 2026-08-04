# db-object-any-ref-type-check

**Category:** Database objects / metadata design  ·  **Severity:** MAJOR

Flags a stored attribute (catalog/document attribute, register dimension or resource) typed as an unqualified reference type - `AnyRef`, or a bare `CatalogRef`/`DocumentRef`/etc. with no specific object named - instead of listing the concrete types it can actually hold.

## Why it matters
An unqualified reference type can point at literally any object of that kind (or, for `AnyRef`, any object at all), so the platform can't build an efficient index or plan for it, and every query touching that field has to account for every possible target type. It also leaves the reader guessing what the field is actually meant to contain.

## How to fix
Replace the generic type with the specific type(s) the attribute is meant to hold - a single `CatalogRef.Products`, or a short composite list like `DocumentRef.Invoice, DocumentRef.Order, DocumentRef.Contract` if it genuinely needs to reference more than one kind of object. If the existing data already contains a mix, query for the distinct types actually stored before narrowing the definition. Generic types remain appropriate for characteristic value types and other places explicitly designed for arbitrary references.

## Example

```xml
<mdclass:Document uuid="..." name="Payment">
  <attributes uuid="...">
    <name>PaymentBasis</name>
    <type>
      <types>DocumentRef.Invoice</types>
      <types>DocumentRef.Order</types>
      <types>DocumentRef.Contract</types>
    </type>
  </attributes>
</mdclass:Document>
```
