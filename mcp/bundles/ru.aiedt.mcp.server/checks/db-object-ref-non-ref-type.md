# db-object-ref-non-ref-type

**Category:** Database objects / metadata design  ·  **Severity:** MAJOR

Flags an attribute whose composite type mixes one or more reference types (`CatalogRef.X`, `DocumentRef.Y`, ...) with a primitive type such as `String`, `Number`, `Boolean`, or `Date`.

## Why it matters
Standard 728 treats a reference attribute as something that should only ever hold references. Once it can also hold a plain string or number, every piece of code that reads it has to branch on `TypeOf(...)` to figure out which case it's in, which is exactly the kind of ambiguity a strongly-typed attribute is supposed to prevent - and it usually signals two different concepts (an internal reference and an external code/name) were forced into one field.

## How to fix
Split the attribute in two: keep the reference-only type on the original attribute, and add a separate primitive-typed attribute for the non-reference case (e.g. `Customer: CatalogRef.Customers` plus `ExternalCustomerName: String`). If existing data already has values of the primitive type stored in the reference field, migrate them into the new attribute before removing the mixed type.

## Example

```xml
<Attribute>
    <Name>Customer</Name>
    <Type>
        <Type>CatalogRef.Customers</Type>
    </Type>
</Attribute>
<Attribute>
    <Name>ExternalCustomerName</Name>
    <Type>
        <Type>String</Type>
    </Type>
    <Length>100</Length>
</Attribute>
```
