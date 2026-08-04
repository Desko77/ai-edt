# doc-comment-use-minus-check

**Category:** Documentation comments  ·  **Severity:** Minor (Code style)

Flags dash-like characters other than the plain hyphen-minus (U+002D) - em dash, en dash, figure dash, and similar look-alikes - used as separators inside documentation comments.

## Why it matters

The doc-comment parser expects a literal hyphen-minus between name, type, and description. Look-alike dashes are easy to introduce by pasting from Word or a web page, and they quietly break parsing.

## How to fix

Retype the separator using the ordinary keyboard hyphen (the key next to "0").

## Example

```bsl
// Wrong: em dash instead of hyphen-minus
// Parameters:
//  Value (em dash) String (em dash) value

// Right: plain hyphen-minus
// Parameters:
//  Value - String - value
```
