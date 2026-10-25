/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.ops;

import java.util.ArrayList;
import java.util.List;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.ConfigurationNames;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.StandardAttributes;
import ru.aiedt.mcp.oform.layout.TableColumns;
import ru.aiedt.mcp.oform.layout.TypePattern;

/**
 * The columns of a table: adding one with the control its cells carry, removing one. The
 * controls a cell may carry live in {@link ColumnControls}.
 */
public final class ColumnControlOperations
{
    private ColumnControlOperations()
    {
        // static catalog
    }

    /** @return the specs of this catalog */
    public static List<OperationSpec> specs()
    {
        List<OperationSpec> out = new ArrayList<>();
        out.add(OperationSpec.layout("add_column", //$NON-NLS-1$
            "elementName (a Table), name, caption, data (a member or standard attribute of the row, or " //$NON-NLS-1$
                + "the value table's column), type (String | Number | Date | Boolean when data names none), width, " //$NON-NLS-1$
                + "editable, control (the cell's control); the kind of column follows what the rows are: a list of an " //$NON-NLS-1$
                + "object, a tabular section, a value table or tree", //$NON-NLS-1$
            List.of(ParamSpec.string("elementName", "the Table."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("name", "the name of the new column."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("caption", "the column's caption."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("data", "the member or standard attribute of the row (Код, Наименование, Номенклатура, НомерСтроки), " //$NON-NLS-1$ //$NON-NLS-2$
                    + "or the column of the value table."), //$NON-NLS-1$
                ParamSpec.string("type", "String | Number | Date | Boolean, the values' type when data names none."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.integer("width", "the column's width."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.bool("editable", "the cell gets an editor (default true); given together with control, the two must agree."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("control", "the cell's control: " + String.join(", ", ColumnControls.names()) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " (default field when editable, none otherwise); a pair of control and value type outside the " //$NON-NLS-1$
                    + "measured ones is refused")), //$NON-NLS-1$
            ColumnControlOperations::addColumn));
        out.add(OperationSpec.layout("remove_column", "elementName (a Table) and name", //$NON-NLS-1$ //$NON-NLS-2$
            List.of(ParamSpec.string("elementName", "the Table."), //$NON-NLS-1$ //$NON-NLS-2$
                ParamSpec.string("name", "the name of the column to remove.")), //$NON-NLS-1$ //$NON-NLS-2$
            context -> {
                String element = context.string("elementName"); //$NON-NLS-1$
                String name = context.string("name"); //$NON-NLS-1$
                if (element == null || element.isEmpty() || name == null || name.isEmpty())
                {
                    return OperationResult.refused("remove_column needs elementName (the table) and name (the column)."); //$NON-NLS-1$
                }
                return OperationResult.of(context.editor.removeColumn(element, name));
            }));
        return out;
    }

    private static OperationResult addColumn(LayoutContext context)
    {
        String tableName = context.string("elementName"); //$NON-NLS-1$
        String name = context.string("name"); //$NON-NLS-1$
        if (tableName == null || tableName.isEmpty() || name == null || name.isEmpty())
        {
            return OperationResult.refused("add_column needs elementName (the table) and name (the column)."); //$NON-NLS-1$
        }
        String controlWord = context.string("control"); //$NON-NLS-1$
        ColumnControls.ColumnControl control = null;
        if (controlWord != null && !controlWord.trim().isEmpty())
        {
            control = ColumnControls.find(controlWord);
            if (control == null)
            {
                return OperationResult.refused("Unknown column control " + controlWord.trim() + "; registered: " //$NON-NLS-1$ //$NON-NLS-2$
                    + String.join(", ", ColumnControls.names()) + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        boolean editable = context.bool("editable", true); //$NON-NLS-1$
        if (control != null && context.has("editable") && editable != control.editor) //$NON-NLS-1$
        {
            return OperationResult.refused("Contradiction: editable=" + editable + " with control=" + control.name //$NON-NLS-1$ //$NON-NLS-2$
                + "; a column with a control is editable, and control=none shows only."); //$NON-NLS-1$
        }
        String controlName = control == null ? (editable ? "field" : "none") : control.name; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        FormLayout layout = context.editor.layout();
        FormLayout.Element table = layout.elementNamed(tableName);
        if (table == null)
        {
            return OperationResult.refused("No element named " + tableName + " on the form."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!"Table".equals(table.type)) //$NON-NLS-1$
        {
            return OperationResult.refused(tableName + " is a " + table.type + ", not a Table."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        LayoutNames names = ConfigurationNames.of(context.project, layout.producedTypeUuids());
        layout.resolve(names);
        String rowType = table.pattern == null ? null : table.pattern.singleProducedType();
        String rowTypeName = rowType == null ? null : names.typeName(rowType);
        String kind = TableColumns.kindForRows(rowTypeName, rowType);
        if (kind == null)
        {
            return OperationResult.refused("The rows of " + tableName + " are " + (table.valueType == null ? "of no known type" : table.valueType) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "; a column was measured for a list of an object, a tabular section, a value table and a value tree" //$NON-NLS-1$
                + (names == LayoutNames.NONE ? " (the configuration is not loaded in this environment)." : ".")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String data = context.string("data"); //$NON-NLS-1$
        String typeWord = context.string("type"); //$NON-NLS-1$
        List<FormLayout.Segment> path = new ArrayList<>();
        ListNode pattern = typeWord == null || typeWord.isEmpty() ? null : DataPaths.typePattern(typeWord);
        if (typeWord != null && !typeWord.isEmpty() && pattern == null)
        {
            return OperationResult.refused("Unknown type " + typeWord + "; known: String, Number, Date, Boolean."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String valueTableColumn = null;
        String bound = null;
        if (TableColumns.VALUE_TABLE.equals(kind))
        {
            valueTableColumn = data == null || data.trim().isEmpty() ? name : data.trim();
            bound = valueTableColumn;
        }
        else if (data != null && !data.trim().isEmpty())
        {
            String word = data.trim();
            String ownerKind = names.ownerKind(rowType);
            Integer code = TableColumns.TABULAR_SECTION.equals(kind) ? ("НомерСтроки".equalsIgnoreCase(word) ? Integer.valueOf(-2) : null) //$NON-NLS-1$
                : StandardAttributes.codeOf(ownerKind, word);
            String member = names.memberUuid(rowType, word);
            if (member != null)
            {
                path.add(new FormLayout.Segment(FormLayout.Segment.MEMBER, member));
                if (pattern == null)
                {
                    pattern = names.memberPattern(rowType, member);
                }
                bound = names.memberName(rowType, member);
            }
            else if (code != null)
            {
                path.add(new FormLayout.Segment(FormLayout.Segment.STANDARD, Integer.toString(code.intValue())));
                if (pattern == null)
                {
                    pattern = DataPaths.standardPattern(word);
                }
                bound = word;
            }
            else
            {
                return OperationResult.refused("No member or standard attribute named " + word + " in the rows of " + tableName //$NON-NLS-1$ //$NON-NLS-2$
                    + " (" + (rowTypeName != null ? rowTypeName : table.valueType) + ")."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (pattern == null && TableColumns.VALUE_TABLE.equals(kind))
        {
            pattern = BraceTree.parse("{\"Pattern\",{\"S\"}}"); //$NON-NLS-1$
        }
        if (isRowNumberOfATabularSection(kind, path))
        {
            String refusal = rowNumberRefusal(control);
            if (refusal != null)
            {
                return OperationResult.refused(refusal);
            }
            controlName = "none"; //$NON-NLS-1$
        }
        ColumnControls.ColumnControl cell = ColumnControls.find(controlName);
        if (cell != null && !cell.valueTypes.isEmpty())
        {
            List<String> letters = TypePattern.letters(pattern);
            boolean allowed = letters.isEmpty() ? cell.valueTypes.contains("none") //$NON-NLS-1$
                : letters.size() == 1 && cell.valueTypes.contains(letters.get(0));
            if (!allowed)
            {
                return OperationResult.refused("A " + cell.name + " cell is read by the platform over " //$NON-NLS-1$ //$NON-NLS-2$
                    + typeWords(cell.valueTypes) + "; the column's type (" + typeWords(letters) //$NON-NLS-1$
                    + ") is not among them, and a control written there is read back as no control."); //$NON-NLS-1$
            }
        }
        String caption = context.string("caption"); //$NON-NLS-1$
        if (caption == null)
        {
            caption = context.string("title"); //$NON-NLS-1$
        }
        LayoutEditor.Outcome outcome = context.editor.addColumn(tableName, kind, name, caption, context.integer("width"), //$NON-NLS-1$
            pattern, path, valueTableColumn, controlName);
        if (outcome.done)
        {
            context.answer.put("columnKind", kind); //$NON-NLS-1$
            if (bound != null)
            {
                context.answer.put("data", bound); //$NON-NLS-1$
            }
            context.answer.put("control", controlName); //$NON-NLS-1$
        }
        return OperationResult.of(outcome);
    }

    /**
     * Whether the column is the row number of a tabular section: the one standard attribute
     * {@code -2} over rows of that kind. Such a column carries no control in the corpus.
     *
     * @param kind the column kind of the table's rows
     * @param path the data path of the column
     * @return whether it is the row number
     */
    public static boolean isRowNumberOfATabularSection(String kind, List<FormLayout.Segment> path)
    {
        return TableColumns.TABULAR_SECTION.equals(kind) && path.size() == 1
            && path.get(0).kind == FormLayout.Segment.STANDARD && "-2".equals(path.get(0).value); //$NON-NLS-1$
    }

    /**
     * The refusal for a control asked on the row number column, or {@code null} when none was
     * asked or {@code none} was: the column takes no control, and a control asked by name is
     * not dropped in silence.
     *
     * @param control the control the arguments named, or {@code null}
     * @return the refusal, or {@code null}
     */
    public static String rowNumberRefusal(ColumnControls.ColumnControl control)
    {
        if (control == null || "none".equals(control.name)) //$NON-NLS-1$
        {
            return null;
        }
        return "The row number column of a tabular section carries no control; pass control=none or leave " //$NON-NLS-1$
            + "the control out (control=" + control.name + " was asked)."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The value type letters in words, for a refusal: {@code S} as String, {@code none} as no type. */
    private static String typeWords(List<String> letters)
    {
        List<String> out = new ArrayList<>();
        for (String letter : letters)
        {
            switch (letter)
            {
            case "S": //$NON-NLS-1$
                out.add("String"); //$NON-NLS-1$
                break;
            case "N": //$NON-NLS-1$
                out.add("Number"); //$NON-NLS-1$
                break;
            case "D": //$NON-NLS-1$
                out.add("Date"); //$NON-NLS-1$
                break;
            case "B": //$NON-NLS-1$
                out.add("Boolean"); //$NON-NLS-1$
                break;
            case "#": //$NON-NLS-1$
                out.add("a reference"); //$NON-NLS-1$
                break;
            default:
                out.add("no type"); //$NON-NLS-1$
                break;
            }
        }
        return String.join(", ", out); //$NON-NLS-1$
    }
}
