# export-variable-in-object-module

**Category:** Module structure  ·  **Severity:** Major (Warning, Standard 439)

Flags a module-level variable declared `Export` inside an object module.

## Why it matters

An exported variable can be read and overwritten from anywhere that holds the object, which breaks encapsulation and makes it hard to track down where and why its value changed.

## How to fix

Replace it with `AdditionalProperties` for passing short-lived flags between event handlers, a proper metadata attribute for state that needs to persist, or an explicit method parameter.

## Example

```bsl
// Wrong: anyone can read or overwrite this
Var ProcessingMode Export;

// Right: pass the flag explicitly instead
DocumentObject.AdditionalProperties.Insert("ProcessingMode", "Auto");
```
