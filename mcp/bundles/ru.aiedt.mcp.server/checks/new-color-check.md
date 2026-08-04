# new-color-check

**Category:** UI Styling  ·  **Severity:** Minor

Flags direct construction of colors via `New Color(R, G, B)` on form items instead of referencing a style element.

## Why it matters
A color built inline is invisible to the configuration's style system: it can't be swapped for a theme, and every place that needs "the same red" has to repeat the same magic numbers, which drifts over time.

## How to fix
Replace the literal `New Color(...)` with the matching `StyleColors.*` item (add a new style item first if nothing existing fits).

## Example

```bsl
// Bad
Items.TotalAmount.TextColor = New Color(255, 0, 0);

// Good
Items.TotalAmount.TextColor = StyleColors.ErrorTextColor;
```
