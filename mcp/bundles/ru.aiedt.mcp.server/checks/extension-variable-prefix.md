# extension-variable-prefix

**Category:** Extensions  ·  **Severity:** Major (Warning, Standard 640)

Flags a module-level variable added inside an extension's module whose name doesn't start with the extension's prefix.

## Why it matters

Same collision risk as unprefixed methods: an unprefixed variable can clash with one the base configuration or another extension introduces down the line.

## How to fix

Prefix the variable and update its usages. If it's only used to pass a short-lived flag between handlers, consider replacing the module variable with `AdditionalProperties` instead.

## Example

```bsl
// Extension prefix: CustomExt_

// Wrong
Var ProductCache;

// Right
Var CustomExt_ProductCache;
```
