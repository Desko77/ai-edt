# md-object-name-length-check

**Category:** Metadata - naming  ·  **Severity:** Major (Error, trivial)

Flags a metadata object name longer than the configured maximum, 80 characters by default.

## Why it matters

Very long names risk running into database identifier limits once the platform adds its own prefix (like `_Document_...`), and they make queries and code noticeably harder to read and type.

## How to fix

Rename the object to something shorter but still descriptive, using IDE rename refactoring so references update automatically. Avoid inventing non-standard abbreviations just to shave off characters.
