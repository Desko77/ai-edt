# common-module-named-self-reference

**Category:** Code smell (common modules)  ·  **Severity:** MINOR

Flags a call inside a common module that prefixes one of its own methods with the module's own name, e.g. `CommonUtilities.FormatDate(...)` written from inside `CommonUtilities` itself.

## Why it matters
The prefix is dead weight - calling your own exported method needs no qualification. It adds noise, and it creates an extra place that has to change if the module is ever renamed.

## How to fix
Drop the module-name prefix and call the method directly, including in recursive calls. Keep the prefix only when actually calling a different module.

## Example

```bsl
// Before, inside module CommonUtilities
FormattedDate = CommonUtilities.FormatDate(DocumentData.Date);

// After
FormattedDate = FormatDate(DocumentData.Date);
```
