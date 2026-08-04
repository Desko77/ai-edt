# module-structure-event-form-regions-check

**Category:** Module Structure  ·  **Severity:** Minor

Flags form event handlers (`OnOpen`, item `OnChange`, table row events, etc.) that sit outside the standard form regions, and non-handler methods that end up inside those regions.

## Why it matters
Form modules follow a fixed region layout (`FormEventHandlers`, `FormHeaderItemsEventHandlers`, `FormTableItemsEventHandlers`, `FormCommandsEventHandlers`, `Private`) so that any developer can jump straight to the handler they need without scanning the whole file.

## How to fix
Move each handler into the region that matches its kind - form lifecycle events go to `FormEventHandlers`, item events to `FormHeaderItemsEventHandlers`, table events to `FormTableItemsEventHandlers` - and keep helper methods out of those regions, in `Private`.

## Example

```bsl
#Region FormEventHandlers
Procedure OnOpen(Cancel)
    InitializeForm();
EndProcedure
#EndRegion
```
