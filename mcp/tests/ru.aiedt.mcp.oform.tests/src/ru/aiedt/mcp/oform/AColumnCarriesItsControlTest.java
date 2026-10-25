/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.layout.TableColumns;
import ru.aiedt.mcp.oform.ops.ColumnControlOperations;
import ru.aiedt.mcp.oform.ops.ColumnControls;
import ru.aiedt.mcp.oform.ops.LayoutContext;
import ru.aiedt.mcp.oform.ops.OperationResult;
import ru.aiedt.mcp.oform.ops.OperationsCatalog;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * A column carries in its cells the control the writer names: the record holds the uuid of the
 * control at body position 38 and the definition of the control as a blob at position 39, cut
 * from the real columns of the corpus, and the reader names the control back. A pair of control
 * and value type the corpus does not carry, a control name nobody registered and an
 * {@code editable} that contradicts the control are refused by name.
 */
public class AColumnCarriesItsControlTest
{
    /** The check-box definition of the corpus with the number flag in place (254 columns). */
    private static final String CHECK_BOX_TEXT = "{1,{{{10,1,{3,4,{0}},{3,4,{0}},{6,3,0,1},0,{3,3,{-22}},{3,4,{0}},{3,4,{0}},{3,3,{-7}},{3,3,{-21}},{3,0,{0},0,0,0,48312c09-257f-4b29-b280-284dd89efc1e},{1,0}},4,{1,0},1,0,1},1,0,%s},{0}}"; //$NON-NLS-1$

    /** The choice-field definition of the corpus with the boolean and reference flags in place (21 columns). */
    private static final String SELECT_FIELD_TEXT = "{2,{{10,1,{3,4,{0}},{3,4,{0}},{6,3,0,1},0,{3,3,{-22}},{3,4,{0}},{3,4,{0}},{3,3,{-7}},{3,3,{-21}},{3,1,{-18},0,0,0},{1,0}},21,0,0,1,0,1,%s,0,0,0,1,%s,0,255,0,0,4,0,{\"U\"},{\"U\"},\"\",0,1,1,0,0,0,{3,0,{0},\"\",-1,-1,1,0},{3,0,{0},\"\",-1,-1,1,0},0,0,0,{0,0,0},{1,0},0,0,0,0},{0}}"; //$NON-NLS-1$

    private static final String REF_TYPE = "7a1f0c2e-5b6d-4e8f-9a0b-1c2d3e4f5a6b"; //$NON-NLS-1$

    private static String layoutText;

    private static String processingText;

    @BeforeClass
    public static void theForms() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/list-form-v27.form"); //$NON-NLS-1$
        processingText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/processing-form-v27.form"); //$NON-NLS-1$
    }

    /**
     * A check box over a string column is refused: the corpus holds 193 such records and the
     * platform reads every one of them as no control (measured on the stand, a check box over a
     * boolean column is read as Флажок, over a string column as Не определено).
     */
    @Test
    public void aCheckBoxOverAStringColumnIsRefused()
    {
        LayoutContext context = contextOnTheValueTable(Map.of("elementName", "Text63", "name", "Проба", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "type", "String", "control", "checkBox")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OperationResult result = OperationsCatalog.find("ADD_COLUMN").layout.apply(context); //$NON-NLS-1$
        assertFalse(result.outcome.done);
        assertTrue(result.outcome.message, result.outcome.message.contains("read back as no control")); //$NON-NLS-1$
    }

    /** The check box of a boolean column (43 of 254). */
    @Test
    public void aCheckBoxCellIsWrittenForABooleanColumn()
    {
        FormLayout.Column column = addAndReadBack("checkBox", BraceTree.parse("{\"Pattern\",{\"B\"}}"), //$NON-NLS-1$ //$NON-NLS-2$
            TableColumns.CHECK_BOX, String.format(CHECK_BOX_TEXT, "0")); //$NON-NLS-1$
        assertEquals("CheckBox", column.control); //$NON-NLS-1$
        assertEquals("Boolean", column.valueType); //$NON-NLS-1$
    }

    /** A check box over a number column is refused for the same reason as over a string one. */
    @Test
    public void aCheckBoxOverANumberColumnIsRefused()
    {
        LayoutContext context = contextOnTheValueTable(Map.of("elementName", "Text63", "name", "Проба", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "type", "Number", "control", "checkBox")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OperationResult result = OperationsCatalog.find("ADD_COLUMN").layout.apply(context); //$NON-NLS-1$
        assertFalse(result.outcome.done);
        assertTrue(result.outcome.message, result.outcome.message.contains("read back as no control")); //$NON-NLS-1$
    }

    /** The choice field of a string column (10 of the 21 the corpus carries). */
    @Test
    public void aChoiceFieldCellIsWrittenForAStringColumn()
    {
        FormLayout.Column column = addAndReadBack("selectField", BraceTree.parse("{\"Pattern\",{\"S\",25,1}}"), //$NON-NLS-1$ //$NON-NLS-2$
            TableColumns.SELECT_FIELD, String.format(SELECT_FIELD_TEXT, "0", "0")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("SelectField", column.control); //$NON-NLS-1$
        assertEquals("String(25)", column.valueType); //$NON-NLS-1$
    }

    /** The choice field of a number column (2 of 21). */
    @Test
    public void aChoiceFieldCellIsWrittenForANumberColumn()
    {
        FormLayout.Column column = addAndReadBack("selectField", BraceTree.parse("{\"Pattern\",{\"N\",15,2,0}}"), //$NON-NLS-1$ //$NON-NLS-2$
            TableColumns.SELECT_FIELD, String.format(SELECT_FIELD_TEXT, "0", "0")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("SelectField", column.control); //$NON-NLS-1$
    }

    /** The choice field of a boolean column (1 of 21): its boolean flag is 1. */
    @Test
    public void aChoiceFieldCellIsWrittenForABooleanColumn()
    {
        FormLayout.Column column = addAndReadBack("selectField", BraceTree.parse("{\"Pattern\",{\"B\"}}"), //$NON-NLS-1$ //$NON-NLS-2$
            TableColumns.SELECT_FIELD, String.format(SELECT_FIELD_TEXT, "1", "0")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("SelectField", column.control); //$NON-NLS-1$
    }

    /** The choice field of a reference column (2 of 21): its reference flag is 1. */
    @Test
    public void aChoiceFieldCellIsWrittenForAReferenceColumn()
    {
        FormLayout.Column column = addAndReadBack("selectField", BraceTree.parse("{\"Pattern\",{\"#\"," + REF_TYPE + "}}"), //$NON-NLS-1$ //$NON-NLS-2$
            TableColumns.SELECT_FIELD, String.format(SELECT_FIELD_TEXT, "0", "1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("SelectField", column.control); //$NON-NLS-1$
    }

    /** The choice field of an untyped column (6 of 21). */
    @Test
    public void aChoiceFieldCellIsWrittenForAnUntypedColumn()
    {
        FormLayout.Column column = addAndReadBack("selectField", BraceTree.parse("{\"Pattern\"}"), //$NON-NLS-1$ //$NON-NLS-2$
            TableColumns.SELECT_FIELD, String.format(SELECT_FIELD_TEXT, "0", "0")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("SelectField", column.control); //$NON-NLS-1$
        assertEquals("Any", column.valueType); //$NON-NLS-1$
    }

    /** A control nobody registered is refused with the registered ones named. */
    @Test
    public void anUnknownControlIsRefusedByName()
    {
        LayoutContext context = contextOnTheValueTable(Map.of("elementName", "Text63", "name", "Проба", "type", "String", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "control", "image")); //$NON-NLS-1$ //$NON-NLS-2$
        OperationResult result = OperationsCatalog.find("ADD_COLUMN").layout.apply(context); //$NON-NLS-1$
        assertFalse(result.outcome.done);
        assertTrue(result.outcome.message, result.outcome.message.contains("Unknown column control image")); //$NON-NLS-1$ //$NON-NLS-2$
        for (String name : ColumnControls.names())
        {
            assertTrue(name, result.outcome.message.contains(name)); //$NON-NLS-1$
        }
    }

    /** An editable that contradicts the control is refused. */
    @Test
    public void aContradictingEditableIsRefused()
    {
        LayoutContext context = contextOnTheValueTable(Map.of("elementName", "Text63", "name", "Проба", "type", "Boolean", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "control", "checkBox", "editable", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OperationResult result = OperationsCatalog.find("ADD_COLUMN").layout.apply(context); //$NON-NLS-1$
        assertFalse(result.outcome.done);
        assertTrue(result.outcome.message, result.outcome.message.contains("Contradiction")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.outcome.message, result.outcome.message.contains("checkBox")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A pair the corpus does not carry is refused, naming the control and the types it was measured with. */
    @Test
    public void anUnmeasuredPairIsRefused()
    {
        LayoutContext context = contextOnTheValueTable(Map.of("elementName", "Text63", "name", "Проба", "type", "Date", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "control", "checkBox")); //$NON-NLS-1$ //$NON-NLS-2$
        OperationResult result = OperationsCatalog.find("ADD_COLUMN").layout.apply(context); //$NON-NLS-1$
        assertFalse(result.outcome.done);
        assertTrue(result.outcome.message, result.outcome.message.contains("checkBox")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.outcome.message, result.outcome.message.contains("Date")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.outcome.message, result.outcome.message.contains("Boolean")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The row number column of a tabular section takes no control: a control asked by name is
     * refused rather than dropped, none and no control at all pass, and another column kind
     * is not the row number.
     */
    @Test
    public void aControlAskedOnTheRowNumberColumnIsRefused()
    {
        List<FormLayout.Segment> rowNumber = List.of(new FormLayout.Segment(FormLayout.Segment.STANDARD, "-2")); //$NON-NLS-1$
        assertTrue(ColumnControlOperations.isRowNumberOfATabularSection(TableColumns.TABULAR_SECTION, rowNumber));
        assertFalse(ColumnControlOperations.isRowNumberOfATabularSection(TableColumns.LIST, rowNumber));
        assertFalse(ColumnControlOperations.isRowNumberOfATabularSection(TableColumns.TABULAR_SECTION, List.of()));
        String refusal = ColumnControlOperations.rowNumberRefusal(ColumnControls.find("checkBox")); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("control=checkBox")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("control=none")); //$NON-NLS-1$
        assertEquals(null, ColumnControlOperations.rowNumberRefusal(ColumnControls.find("none"))); //$NON-NLS-1$
        assertEquals(null, ColumnControlOperations.rowNumberRefusal(null));
    }

    /** The default control follows editable: a field when editable, none when not; the answer names it. */
    @Test
    public void theDefaultControlFollowsEditable()
    {
        LayoutContext withField = contextOnTheValueTable(Map.of("elementName", "Text63", "name", "Проба", "type", "String")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(OperationsCatalog.find("ADD_COLUMN").layout.apply(withField).outcome.done); //$NON-NLS-1$
        assertEquals("field", answerOf(withField).get("control").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(withField.editor.text().contains(TableColumns.FIELD));
        LayoutContext withNone = contextOnTheValueTable(Map.of("elementName", "Text63", "name", "Проба", "type", "String", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "editable", "false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(OperationsCatalog.find("ADD_COLUMN").layout.apply(withNone).outcome.done); //$NON-NLS-1$
        assertEquals("none", answerOf(withNone).get("control").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        String text = withNone.editor.text();
        ListNode record = (ListNode)((ListNode)((ListNode)columnsOf(BraceTree.parse(text), "Text63").get(2)).get(1)).get(1); //$NON-NLS-1$
        assertEquals(39, ((ListNode)record.get(1)).size());
    }

    /**
     * A column with the control and the type pattern is added to the list form's table: the
     * record carries the uuid of the control and a blob that inflates to the definition of the
     * control with the flags the corpus varies with the type, the reader names the control back
     * and the text round-trips byte for byte.
     *
     * @return the column as read back
     */
    private FormLayout.Column addAndReadBack(String control, ListNode pattern, String uuid, String blob)
    {
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor.Outcome outcome = editor.addColumn("СправочникСписок", TableColumns.LIST, "Проба", "Проба", null, pattern, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List.of(), null, control);
        assertTrue(outcome.message, outcome.done);
        String text = editor.text();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        assertFalse(text, text.contains("@")); //$NON-NLS-1$
        ListNode root = BraceTree.parse(text);
        ListNode record = (ListNode)((ListNode)((ListNode)columnsOf(root, "СправочникСписок").get(4)).get(1)).get(1); //$NON-NLS-1$
        ListNode body = (ListNode)record.get(1);
        assertEquals(40, body.size());
        assertEquals(uuid, ((BraceTree.Token)body.get(38)).text());
        String controlText = TableColumns.blobText((ListNode)body.get(39));
        assertNotNull(controlText);
        assertEquals(blob, controlText.replace("\r\n", "")); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout layout = LayoutReader.read(root, "Catalog"); //$NON-NLS-1$
        assertEquals(0, layout.unread.size());
        return layout.elementNamed("СправочникСписок").columns.get(3); //$NON-NLS-1$
    }

    /** The operation's context over the processing form's value table, without a project. */
    private static LayoutContext contextOnTheValueTable(Map<String, String> params)
    {
        ListNode tree = BraceTree.parse(processingText);
        LayoutEditor editor = LayoutEditor.over(tree, "DataProcessor", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        return new LayoutContext(null, "DataProcessor.Probe.Form.Form", "DataProcessor", null, tree, editor, params, //$NON-NLS-1$ //$NON-NLS-2$
            ToolResult.success());
    }

    /** The answer the operation built, as JSON. */
    private static JsonObject answerOf(LayoutContext context)
    {
        return JsonParser.parseString(context.answer.toJson()).getAsJsonObject();
    }

    /** The columns list of the named table, the count token first. */
    private static ListNode columnsOf(ListNode root, String tableName)
    {
        ListNode table = findTable(root, tableName);
        assertNotNull(tableName, table);
        return LayoutReader.columnsList((ListNode)table.get(2));
    }

    /** The record of the named table, depth first: an element record is six items with the name in the identity at [4]. */
    private static ListNode findTable(ListNode node, String tableName)
    {
        if (node.size() >= 6 && node.get(4) instanceof ListNode)
        {
            ListNode identity = (ListNode)node.get(4);
            if (tableName.equals(LayoutReader.elementName(identity)))
            {
                return node;
            }
        }
        for (int i = 0; i < node.size(); i++)
        {
            if (node.get(i) instanceof ListNode)
            {
                ListNode found = findTable((ListNode)node.get(i), tableName);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }
}
