# empty-except-statement

**Category:** Error handling  ·  **Severity:** Minor (Code style, Standard 499)

Flags a `Try...Except...EndTry` block whose Except section has no executable statements - empty, or containing only comments.

## Why it matters

An empty except silently swallows whatever went wrong, leaving no trace of the failure and potentially an inconsistent state with no way to diagnose it later.

## How to fix

Do something with the error: log it with WriteLogEvent, re-raise it with added context, return a sensible fallback value, or notify the user - pick whatever fits the situation, but never leave the block empty or comment-only.

## Example

```bsl
// Wrong: error is discarded
Try
    DocumentObject.Write();
Except
    // nothing here
EndTry;

// Right: error is logged
Try
    DocumentObject.Write();
Except
    WriteLogEvent("Document.Processing", EventLogLevel.Error, , , ErrorDescription());
EndTry;
```
