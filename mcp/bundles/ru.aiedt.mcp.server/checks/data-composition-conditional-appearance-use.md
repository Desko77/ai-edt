# data-composition-conditional-appearance-use

**Category:** Reports / DCS  ·  **Severity:** MINOR

Flags a conditional-appearance item in a Data Composition Schema report that is configured but effectively inert - its `Use` flag (or its filter's/appearance's/field's `Use` flag) is `False`, or the underlying condition can never actually match.

## Why it matters
A conditional-appearance rule that's present but switched off is dead configuration: it does not affect what the user sees, it adds clutter to the report's settings, and it is easy to mistake for an active rule during review or troubleshooting.

## How to fix
Go through each `ConditionalAppearance` item and check that `Use = True` at every level - the item itself, its filter conditions, its appearance settings, and the fields it targets. If a rule truly isn't needed, delete it instead of leaving it disabled; if it should be active, turn on every `Use` flag involved and confirm the filter's field names and comparison values actually match your data.

## Example

```bsl
Item = ConditionalAppearance.Items.Add();
Item.Use = True;

Filter = Item.Filter.Items.Add(Type("DataCompositionFilterItem"));
Filter.LeftValue = New DataCompositionField("Balance");
Filter.ComparisonType = DataCompositionComparisonType.Less;
Filter.RightValue = 0;
Filter.Use = True;

Item.Appearance.SetParameterValue("TextColor", WebColors.Red);

Field = Item.Fields.Items.Add();
Field.Field = New DataCompositionField("Balance");
Field.Use = True;
```
