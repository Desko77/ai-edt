# md-object-attribute-comment-not-exist-check

**Category:** Metadata - standard attributes  ·  **Severity:** Minor (Code smell, trivial)

Flags a catalog or document that has no "Comment" attribute at all.

## Why it matters

Comment is the conventional place for a user to leave a free-form note. Skipping it pushes users toward ad-hoc workarounds and makes the object less consistent with the rest of the configuration.

## How to fix

Add a "Comment" attribute - String, unlimited length, multiline editing enabled - and place it on the object's form, typically in a collapsible group near the bottom.
