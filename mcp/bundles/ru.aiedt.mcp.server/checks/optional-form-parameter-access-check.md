# optional-form-parameter-access-check

**Category:** Form Development  ·  **Severity:** Major

Flags direct access to a form's optional opening parameter (`Parameters.SomeName`) without first checking whether it was actually passed.

## Why it matters
Optional parameters are, by definition, allowed to be absent from the `Parameters` structure depending on how the form was opened. Reading one directly throws as soon as the form is opened without it, which makes the form fragile to how it's invoked.

## How to fix
Guard every optional parameter read with `Parameters.Property("Name")` (or its two-argument form to grab a default in the same call) before using the value.

## Example

```bsl
// Bad
If Parameters.Mode = "Edit" Then

// Good
Mode = "";
If Parameters.Property("Mode", Mode) And Mode = "Edit" Then
```
