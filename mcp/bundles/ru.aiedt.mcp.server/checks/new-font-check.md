# new-font-check

**Category:** UI Styling  ·  **Severity:** Minor

Flags direct construction of fonts via `New Font(...)` on form items instead of referencing a style element.

## Why it matters
Same problem as hardcoded colors: an inline font bypasses the configuration's style system, so it can't be themed centrally and tends to diverge slightly from similar-looking fonts set elsewhere.

## How to fix
Replace the literal `New Font(...)` with the matching `StyleFonts.*` item, adding a new style item first if none of the existing ones fit.

## Example

```bsl
// Bad
Items.Title.Font = New Font(, 14, True);

// Good
Items.Title.Font = StyleFonts.LargeTextFont;
```
