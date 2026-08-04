# form-list-ref-user-visibility-enabled-check

**Category:** Forms - dynamic lists  ·  **Severity:** Minor (Code smell, trivial)

Flags a dynamic list's Ref field whose UserVisible property is left enabled.

## Why it matters

With UserVisible on, a user can add the Ref column through "Configure list..." and end up looking at a raw internal UUID that carries no useful information for them.

## How to fix

Set UserVisible = False on the Ref field, alongside Visible = False and UseAlways = True, so it stays usable in code but stays out of the user's view entirely.
