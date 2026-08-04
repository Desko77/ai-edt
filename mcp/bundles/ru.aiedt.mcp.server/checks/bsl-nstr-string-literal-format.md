# bsl-nstr-string-literal-format

**Category:** Localization  ·  **Severity:** MINOR

Validates the argument passed to `NStr()` (`НСтр()`): it must be a literal string (not a variable, expression, or concatenation), non-empty, structured as `"code = 'text'"` pairs with recognized language codes, and each translation must have real content with no trailing space or line break.

## Why it matters
`NStr` drives UI localization, and the platform parses its argument as key/value text at runtime - a variable or concatenated value can't be statically resolved, an unrecognized language code silently fails to match, and a trailing space or newline in a translated string is almost always an accidental leftover that ends up visible to users.

## How to fix
Pass the literal directly to `NStr` instead of building it from a variable or `+` concatenation; use real language codes such as `en`/`ru`; fill in any empty translation; and trim whitespace or line breaks at the end of each message. For parameterized text, format the template first and substitute values afterwards with `StringFunctionsClientServer.SubstituteParametersToString`.

## Example

```bsl
MessageTemplate = NStr("en = 'Document %1 has been saved'");
Message = StringFunctionsClientServer.SubstituteParametersToString(
    MessageTemplate, DocumentNumber);
```
