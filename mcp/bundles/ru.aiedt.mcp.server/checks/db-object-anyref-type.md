# db-object-anyref-type

**Category:** Database objects / metadata design  ·  **Severity:** MAJOR

Flags a database object attribute (catalog, document, register, etc.) typed as `AnyRef` - the type that can point to any reference object in the whole configuration.

## Why it matters
Standard 728 treats `AnyRef` as too loose for a stored field: there's no way to validate that the reference actually points at something sensible, the database can't build a proper index for it, and code reading the attribute has to branch on runtime type instead of relying on the metadata. Type mismatches typically surface late, at runtime, rather than being caught up front.

## How to fix
Replace `AnyRef` with a composite type listing only the specific catalog/document/etc. types the attribute is meant to hold. If the same composite list is reused across several attributes, define it once as a `DefinedType` and reference that instead. Before narrowing an existing attribute, check what types are actually stored in the data (`SELECT DISTINCT VALUETYPE(...)`) so nothing currently valid gets excluded.

## Example

```xml
<Attribute>
    <Name>RelatedObject</Name>
    <Type>
        <Type>CatalogRef.Products</Type>
        <Type>CatalogRef.Services</Type>
        <Type>DocumentRef.Invoice</Type>
    </Type>
</Attribute>
```
