# form-list-ref-use-always-flag-disabled-check

**Category:** Forms - dynamic lists  ·  **Severity:** Major (Error)

Flags a dynamic list's Ref field whose UseAlways property is set to False.

## Why it matters

Without UseAlways, the platform can skip loading Ref for rows the user hasn't scrolled to, so code reading `RowData.Ref` may see Undefined for some rows and a real value for others - an intermittent bug that's hard to reproduce.

## How to fix

Set UseAlways = True on the Ref field so it's always populated, regardless of what's currently visible on screen.
