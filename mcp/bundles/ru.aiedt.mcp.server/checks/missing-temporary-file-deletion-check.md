# missing-temporary-file-deletion-check

**Category:** Resource Management  ·  **Severity:** Major

Flags calls to `GetTempFileName()` whose resulting file path is never passed to `DeleteFiles()`.

## Why it matters
Files created this way live in the OS temp folder and are not cleaned up automatically. Left unhandled - especially on an exception path - they accumulate, waste disk space, and can leak data that should not remain on disk.

## How to fix
Wrap the work in `Try...Except` and call `DeleteFiles()` on every exit path, including the error path, so the file is removed regardless of how the procedure ends.

## Example

```bsl
TempFile = GetTempFileName("xml");
Try
    WriteDataToXML(TempFile);
    SendFileByEmail(TempFile);
Except
    WriteLogEvent("Export", EventLogLevel.Error);
    Raise;
EndTry;
DeleteFiles(TempFile); // always runs
```
