# doc-comment-description-ends-on-dot-check

**Category:** Documentation comments  ·  **Severity:** TRIVIAL (disabled by default)

Flags a multi-line (two or more lines) method description in a doc comment whose last line doesn't end with a period. Single-line descriptions are left alone.

## Why it matters
It's a minor formatting-consistency rule - having every multi-line description end the same way keeps generated documentation reading uniformly instead of some entries trailing off without punctuation.

## How to fix
Add a period to the end of the description's last line.

## Example

```bsl
// This function performs complex calculation
// of amounts for the reporting period
// and returns aggregated data.
Function CalculateReportData()
```
