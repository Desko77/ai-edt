# data-composition-conditional-appearance-use-check

**Category:** Forms / performance  ·  **Severity:** MINOR

Flags a form that leans on data-composition **conditional appearance** for its formatting - especially when a form accumulates many rules - since the platform re-evaluates every rule on every data change.

## Why it matters
A handful of simple conditional-appearance rules on rarely-changing data is fine, but once a form has many rules, or the underlying data changes frequently, or the list is large, that re-evaluation cost adds up and formatting logic ends up scattered across designer settings instead of code, which makes it harder to trace and maintain.

## How to fix
For forms where this becomes a real cost, move the formatting into code - set item appearance directly in an event handler such as `ItemsOnActivateRow`, or pre-calculate a display/status column once instead of re-evaluating filter conditions per row. Keep declarative conditional appearance for the simple, low-churn cases where it's genuinely the clearer option.

## Example

```bsl
&AtClient
Procedure ItemsOnActivateRow(Item)
    CurrentData = Items.Items.CurrentData;
    If CurrentData = Undefined Then
        Return;
    EndIf;

    If CurrentData.Amount > 1000 Then
        Items.Amount.TextColor = WebColors.Red;
    Else
        Items.Amount.TextColor = New Color;
    EndIf;
EndProcedure
```
