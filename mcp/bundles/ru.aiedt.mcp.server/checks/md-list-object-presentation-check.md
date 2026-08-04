# md-list-object-presentation-check

**Category:** Metadata - presentation  ·  **Severity:** Minor (Code smell, trivial)

Flags a metadata object - catalog, document, and similar - where neither Object presentation nor List presentation is filled in.

## Why it matters

Without them, the platform falls back to the synonym or the raw technical name in titles, messages, and reports, which is often not the wording a user should actually see.

## How to fix

Fill in Object presentation (the singular form, e.g. "Product") and List presentation (the plural form, e.g. "Products"), with a translation for every language the configuration supports.
