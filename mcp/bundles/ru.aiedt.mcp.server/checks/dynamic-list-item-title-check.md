# dynamic-list-item-title-check

**Category:** Forms - dynamic lists  ·  **Severity:** Minor (Code smell, trivial)

Flags a dynamic list column whose Title property is left empty.

## Why it matters

A blank title leaves users looking at an unlabeled column, undermines localization, and reads as an unfinished form.

## How to fix

Set a localized Title (via NStr) on every field the user can actually see. Fields that are permanently hidden don't need one.
