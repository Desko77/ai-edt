# module-structure-event-regions-check

**Category:** Module Structure  ·  **Severity:** Minor

Flags object-module event handlers (`BeforeWrite`, `OnWrite`, `Posting`, `Filling`, etc.) placed outside the `EventHandlers` region, and non-event methods placed inside it.

## Why it matters
Keeping the `EventHandlers` region reserved for actual platform events - and nothing else - lets a reader trust that everything in it is called by the platform, not by application code, which matters when tracing how an object gets modified.

## How to fix
Move every recognized event handler into `#Region EventHandlers`, and relocate any non-event helper method that ended up there into `Private`.

## Example

```bsl
#Region EventHandlers
Procedure BeforeWrite(Cancel)
    ValidateData(Cancel);
EndProcedure
#EndRegion
```
