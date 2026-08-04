# nstr-string-literal-format-check

**Category:** Localization  ·  **Severity:** Major

Validates that the string literal passed to `NStr()` follows the platform's `lang = 'text'` localization syntax.

## Why it matters
`NStr` parses its argument at runtime looking for a language code, an `=`, and single-quoted text, with entries separated by `;`. Anything that deviates from this - a missing language code, unbalanced quotes, a `:` instead of `=`, an unofficial language code - either fails to resolve or silently returns the wrong text.

## How to fix
Use a real two-letter ISO 639-1 code, wrap the text in single quotes, separate multiple languages with `;`, and double up any literal apostrophe inside the text (`Can''t`).

## Example

```bsl
// Bad
NStr("Save document?")

// Good
NStr("en = 'Save document?'; ru = 'Сохранить документ?'")
```
