# form-self-reference-outdated

**Category:** Forms - legacy syntax  ·  **Severity:** Minor (Code style)

Flags use of the legacy `ThisForm` alias inside a form module.

## Why it matters

`ThisForm` is a pre-8.2 leftover kept only for backward compatibility. `ThisObject` (or simply referencing the property/item directly, without any prefix) is the current, consistent way to refer to the managed form.

## How to fix

Replace `ThisForm` with `ThisObject`, or drop the prefix entirely where a bare property or item reference already works.

## Example

```bsl
// Wrong
ThisForm.Title = "My Document";

// Right
ThisObject.Title = "My Document";
// or simply:
Title = "My Document";
```
