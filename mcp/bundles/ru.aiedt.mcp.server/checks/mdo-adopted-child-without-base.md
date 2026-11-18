# mdo-adopted-child-without-base

**Category:** Metadata / Extension export  ·  **Severity:** Error

The pre-export source scanner found an adopted attribute or tabular section without a non-empty `extendedConfigurationObject` reference. The finding names the metadata object and source line. Both observed spellings of the adoption token are accepted case-insensitively.

## Why it matters

Adoption requires a link to the base metadata object. A child marked as adopted without that link can pass ordinary model checks while remaining invalid for export or a database update. This source check is not a platform export verdict and does not validate the destination base.

## What to do

Inspect the exact child and its intended ownership. Use the supported EDT borrowing workflow to establish the correct base link where adoption is intended. If the child should be owned by the extension, review its creation and ownership instead. Do not insert an arbitrary UUID, delete unrelated adopted metadata, or retry a database update to repair the source.

The check only reads source metadata. It does not borrow, repair or remove anything. A clean result does not prove that every extension dependency or platform constraint is valid.
