# form-data-to-value-check

**Category:** Forms - server code  ·  **Severity:** Major (Code smell)

Flags calls to `FormDataToValue()` in form module code that aren't actually needed for an object method like `Write()` or `Post()`.

## Why it matters

The conversion allocates a full second copy of the data and costs extra time, and the copy is disconnected from the form until it's explicitly written back with `ValueToFormData` - a common source of changes that silently don't stick.

## How to fix

Read and modify form attributes directly wherever possible. Reserve `FormDataToValue`/`ValueToFormData` for the narrow window right around calls that truly require a full object, such as `Write()` or posting.

## Example

```bsl
// Wrong: converts just to read a property
DocumentObject = FormDataToValue(Object, Type("DocumentObject.Order"));
If DocumentObject.Posted Then

// Right: form data already exposes the same property
If Object.Posted Then
```
