# md-object-attribute-comment-check

**Category:** Metadata - standard attributes  ·  **Severity:** Minor (Code smell)

Flags a catalog's or document's "Comment" attribute whose type doesn't match what a free-text notes field needs: a plain String, unlimited length, with multiline editing turned on.

## Why it matters

A length-limited, non-String, or single-line Comment attribute can truncate or awkwardly render exactly the kind of free-form notes it's supposed to hold.

## How to fix

Set the attribute's type to String with length 0 (unlimited) and enable MultilineEdit. Avoid giving Comment a compound type - it should be String and nothing else.
