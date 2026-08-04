# doc-comment-parameter-section-check

**Category:** Documentation comments  ·  **Severity:** MINOR

Flags a method (exported methods only, by default) whose doc comment either has no `Parameters:` section at all, or has one that omits one or more of the method's actual parameters.

## Why it matters
A `Parameters:` section that doesn't cover every parameter is worse than no section at all in one sense - it looks complete, so a reader may trust it, while silently leaving part of the signature undocumented.

## How to fix
Add a `// Parameters:` section if it's missing, and list every parameter with its type and a short description, matching each parameter's actual name exactly. Whether the check looks only at exported methods or all of them is controlled by the `checkOnlyExportMethods` setting (on by default).

## Example

```bsl
// Calculates total.
//
// Parameters:
//  Amount - Number - base amount
//  Rate - Number - tax rate percentage
//
// Returns:
//  Number - calculated total with tax
Function CalculateTotal(Amount, Rate) Export
```
