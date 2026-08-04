# module-attachable-event-handler-name-check

**Category:** Naming Convention  ·  **Severity:** Minor

Per standard #492, flags event handlers that are attached programmatically (via `SetAction`) but whose procedure name does not start with the `Attachable_` (or `Подключаемый_`) prefix.

## Why it matters
Handlers wired up dynamically at runtime are not visible through the form designer's usual event-binding UI. The prefix is the only way to recognize, at a glance, that a procedure is actually in use as a dynamically attached handler rather than dead code.

## How to fix
Rename the handler procedure to carry the prefix and update the string passed to `SetAction` to match.

## Example

```bsl
Item.SetAction("OnChange", "Attachable_ItemOnChange");
```
