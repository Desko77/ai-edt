# md-object-attribute-comment-incorrect-type-check

**Category:** Metadata - standard attributes  ·  **Severity:** Minor (Code style)

Flags the same underlying problem as the sibling Comment-attribute check: a "Comment" attribute on a document or catalog that isn't configured as an unlimited-length, multiline String.

## Why it matters

A constrained or single-line Comment field defeats the purpose of a free-text notes field - long notes get truncated or become awkward to edit.

## How to fix

Reconfigure the attribute as String, length 0, with multiline editing enabled. Which object types get checked is controlled by the `checkDocuments`/`checkCatalogs` parameters.
