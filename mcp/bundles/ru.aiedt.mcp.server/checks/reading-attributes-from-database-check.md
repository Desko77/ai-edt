# reading-attributes-from-database-check

**Category:** Performance  ·  **Severity:** Major

Flags reading a single attribute through an object reference (`Ref.SomeField`), which forces the platform to load the entire object from the database just to return that one field.

## Why it matters
`Ref.Attribute` is convenient but not free: behind the scenes it fetches every attribute of the object, so reading one field out of a fifty-field object wastes most of that fetch. Doing this inside a loop compounds the cost per iteration.

## How to fix
Use a query that selects only the fields actually needed (directly, or joined for a batch of references) instead of dereferencing the field through the reference.

## Example

```bsl
// Bad
CustomerName = CustomerRef.Description;

// Good
Query.Text = "SELECT Description FROM Catalog.Customers WHERE Ref = &Ref";
Query.SetParameter("Ref", CustomerRef);
CustomerName = Query.Execute().Unload()[0].Description;
```
