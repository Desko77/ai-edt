/**
 * AI-EDT Ordinary Forms
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.layout.TableColumns;

/**
 * The columns of a table are read off the record with their bindings, and a column is added
 * with its cell control and removed again.
 */
public class ATableGetsItsColumnsTest
{
    private static final String LIST_TYPE = "5b7c8d9e-1f2a-4b3c-8d4e-5f6a7b8c9d0e"; //$NON-NLS-1$

    private static final String ARTICLE_UUID = "7a1f0c2e-5b6d-4e8f-9a0b-1c2d3e4f5a6b"; //$NON-NLS-1$

    private static final LayoutNames NAMES = new LayoutNames()
    {
        @Override
        public String typeName(String producedTypeUuid)
        {
            return LIST_TYPE.equals(producedTypeUuid) ? "CatalogList.Products" : null; //$NON-NLS-1$
        }

        @Override
        public String ownerKind(String producedTypeUuid)
        {
            return LIST_TYPE.equals(producedTypeUuid) ? "Catalog" : null; //$NON-NLS-1$
        }

        @Override
        public String memberName(String producedTypeUuid, String memberUuid)
        {
            return LIST_TYPE.equals(producedTypeUuid) && ARTICLE_UUID.equals(memberUuid) ? "Артикул" : null; //$NON-NLS-1$
        }

        @Override
        public String referencedKind(String producedTypeUuid, String memberUuid)
        {
            return null;
        }

        @Override
        public String memberTypeUuid(String producedTypeUuid, String memberUuid)
        {
            return null;
        }

        @Override
        public String memberUuid(String producedTypeUuid, String memberName)
        {
            return LIST_TYPE.equals(producedTypeUuid) && "Артикул".equalsIgnoreCase(memberName) ? ARTICLE_UUID : null; //$NON-NLS-1$
        }

        @Override
        public ListNode memberPattern(String producedTypeUuid, String memberUuid)
        {
            return LIST_TYPE.equals(producedTypeUuid) && ARTICLE_UUID.equals(memberUuid)
                ? BraceTree.parse("{\"Pattern\",{\"S\",25,1}}") : null; //$NON-NLS-1$
        }
    };

    private static String layoutText;

    @BeforeClass
    public static void theListForm() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/list-form-v27.form"); //$NON-NLS-1$
    }

    /** The three columns of the list form's table read back with their bindings in words. */
    @Test
    public void columnsAreRead()
    {
        FormLayout layout = LayoutReader.read(BraceTree.parse(layoutText), "Catalog", NAMES); //$NON-NLS-1$
        assertEquals(0, layout.unread.size());
        FormLayout.Element table = layout.elementNamed("СправочникСписок"); //$NON-NLS-1$
        assertNotNull(table);
        assertEquals("Table", table.type); //$NON-NLS-1$
        assertEquals("CatalogList.Products", table.valueType); //$NON-NLS-1$
        assertEquals(3, table.columns.size());
        FormLayout.Column picture = table.columns.get(0);
        assertEquals("Картинка", picture.name); //$NON-NLS-1$
        assertNull(picture.control);
        assertNull(picture.data);
        FormLayout.Column code = table.columns.get(1);
        assertEquals("Код", code.name); //$NON-NLS-1$
        assertEquals("Код", code.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Код", code.data); //$NON-NLS-1$
        assertEquals("Field", code.control); //$NON-NLS-1$
        assertEquals("String(9)", code.valueType); //$NON-NLS-1$
        assertEquals(TableColumns.LIST, code.kind);
        assertEquals("Наименование", table.columns.get(2).data); //$NON-NLS-1$
        assertEquals(BraceTree.serialize(BraceTree.parse(layoutText)), layoutText);
        Object map = layout.toMap().get("elements"); //$NON-NLS-1$
        assertTrue(map instanceof List && ((List<?>)map).size() == 1);
        assertTrue(layout.toMap().toString(), layout.toMap().toString().contains("columns=[{name=Картинка")); //$NON-NLS-1$
    }

    /** A column bound to a member, one to a standard attribute and one that only shows are added; one is removed. */
    @Test
    public void columnsAreAddedAndRemoved()
    {
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        List<FormLayout.Segment> member = List.of(new FormLayout.Segment(FormLayout.Segment.MEMBER, ARTICLE_UUID));
        LayoutEditor.Outcome article = editor.addColumn("СправочникСписок", TableColumns.LIST, "Артикул", "Артикул товара", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            NAMES.memberPattern(LIST_TYPE, ARTICLE_UUID), member, null, "field");
        assertTrue(article.message, article.done);
        List<FormLayout.Segment> owner = List.of(new FormLayout.Segment(FormLayout.Segment.STANDARD, "-5")); //$NON-NLS-1$
        assertTrue(editor.addColumn("СправочникСписок", TableColumns.LIST, "Владелец", null, Integer.valueOf(150), null, owner, null, "field").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.addColumn("СправочникСписок", TableColumns.LIST, "Остаток", "Остаток", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            BraceTree.parse("{\"Pattern\",{\"N\",15,2,0}}"), List.of(), null, "none").done); //$NON-NLS-1$
        assertFalse(editor.addColumn("СправочникСписок", TableColumns.LIST, "Код", null, null, null, List.of(), null, "field").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(editor.addColumn("Нет", TableColumns.LIST, "Х", null, null, null, List.of(), null, "field").done); //$NON-NLS-1$ //$NON-NLS-2$
        String text = editor.text();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        assertFalse(text, text.contains("@")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog", NAMES); //$NON-NLS-1$
        assertEquals(0, layout.unread.size());
        FormLayout.Element table = layout.elementNamed("СправочникСписок"); //$NON-NLS-1$
        assertEquals(6, table.columns.size());
        FormLayout.Column added = table.columns.get(3);
        assertEquals("Артикул", added.name); //$NON-NLS-1$
        assertEquals("Артикул товара", added.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Артикул", added.data); //$NON-NLS-1$
        assertEquals("Field", added.control); //$NON-NLS-1$
        assertEquals("String(25)", added.valueType); //$NON-NLS-1$
        assertEquals("Владелец", table.columns.get(4).data); //$NON-NLS-1$
        assertEquals("150", table.columns.get(4).width); //$NON-NLS-1$
        assertNull(table.columns.get(5).control);
        assertNull(table.columns.get(5).data);
        assertEquals("Number(15,2)", table.columns.get(5).valueType); //$NON-NLS-1$
        // the cell control of the member column inflates to the field definition with the pattern and its length
        ListNode root = BraceTree.parse(text);
        ListNode record = (ListNode)((ListNode)((ListNode)columnsOf(root).get(4)).get(1)).get(1);
        ListNode body = (ListNode)record.get(1);
        assertEquals(40, body.size());
        ListNode slot = (ListNode)body.get(39);
        assertEquals(2, slot.size());
        assertEquals("0", ((BraceTree.Token)slot.get(1)).text()); //$NON-NLS-1$
        String control = TableColumns.blobText(slot);
        assertNotNull(control);
        assertTrue(control, control.startsWith("{9,")); //$NON-NLS-1$
        assertTrue(control, control.replace("\r\n", "").contains("{\"Pattern\",{\"S\",25,1}}")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(control, control.replace("\r\n", "").contains(",1,0,0,25,0,0,4,0,")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.removeColumn("СправочникСписок", "Владелец").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(editor.removeColumn("СправочникСписок", "Владелец").done); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout after = LayoutReader.read(BraceTree.parse(editor.text()), "Catalog", NAMES); //$NON-NLS-1$
        assertEquals(5, after.elementNamed("СправочникСписок").columns.size()); //$NON-NLS-1$
        assertEquals("Остаток", after.elementNamed("СправочникСписок").columns.get(4).name); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A table is added over the list with the settings block of its rows, takes a column, and is bound to the list. */
    @Test
    public void aTableIsAdded()
    {
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        ListNode pattern = BraceTree.parse("{\"Pattern\",{\"#\"," + LIST_TYPE + "}}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(editor.addElement("Table", "Вторая", null, null, new int[] { 0, 200, 600, 400 }, null, null, pattern, null).done); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(TableColumns.settingsBlock("BusinessProcessList.X", "x")); //$NON-NLS-1$ //$NON-NLS-2$
        ListNode settings = TableColumns.settingsBlock("CatalogList.Products", LIST_TYPE); //$NON-NLS-1$
        assertNotNull(settings);
        LayoutEditor.Outcome added = editor.addElement("Table", "Вторая", null, null, new int[] { 0, 200, 600, 400 }, null, "Подсказка", pattern, settings); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(added.message, added.done);
        assertEquals("2", added.elementId); //$NON-NLS-1$
        assertTrue(editor.bindData("2", List.of(new FormLayout.Segment(FormLayout.Segment.ATTRIBUTE, "0"))).done); //$NON-NLS-1$ //$NON-NLS-2$
        List<FormLayout.Segment> code = List.of(new FormLayout.Segment(FormLayout.Segment.STANDARD, "-2")); //$NON-NLS-1$
        assertTrue(editor.addColumn("Вторая", TableColumns.LIST, "Код", "Код", null, BraceTree.parse("{\"Pattern\",{\"S\"}}"), code, null, "field").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String text = editor.text();
        assertFalse(text, text.contains("@")); //$NON-NLS-1$
        assertTrue(text, text.contains("{99f52caa-7b96-4bd4-a649-aedbd230a555,")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog", NAMES); //$NON-NLS-1$
        assertEquals(0, layout.unread.size());
        assertEquals(2, layout.elements.size());
        FormLayout.Element table = layout.elementNamed("Вторая"); //$NON-NLS-1$
        assertEquals("Table", table.type); //$NON-NLS-1$
        assertEquals("CatalogList.Products", table.valueType); //$NON-NLS-1$
        assertEquals("СправочникСписок", table.data); //$NON-NLS-1$
        assertEquals("Подсказка", table.tooltip.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, table.columns.size());
        assertEquals("Код", table.columns.get(0).data); //$NON-NLS-1$
        assertTrue(editor.bindHandler("Вторая", "Выбор", "ВтораяВыбор").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(1, LayoutReader.read(BraceTree.parse(editor.text()), "Catalog", NAMES).elementNamed("Вторая").handlers.size()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The kind of column follows what the rows are. */
    @Test
    public void theKindFollowsTheRows()
    {
        assertEquals(TableColumns.LIST, TableColumns.kindForRows("CatalogList.Products", LIST_TYPE)); //$NON-NLS-1$
        assertEquals(TableColumns.TABULAR_SECTION, TableColumns.kindForRows("DocumentTabularSection.Order.Goods", "x")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(TableColumns.VALUE_TABLE, TableColumns.kindForRows(null, "acf6192e-81ca-46ef-93a6-5a6968b78663")); //$NON-NLS-1$
        assertNull(TableColumns.kindForRows("CatalogObject.Products", "x")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(TableColumns.kindForRows(null, null));
        assertEquals(TableColumns.LIST, TableColumns.kindOf("48A6EBC3-FCC8-4F8F-B399-459FB32AA46B")); //$NON-NLS-1$
    }

    private static ListNode columnsOf(ListNode root)
    {
        ListNode body = (ListNode)root.get(1);
        ListNode rootPanel = (ListNode)body.get(2);
        ListNode children = (ListNode)rootPanel.get(2);
        ListNode table = (ListNode)children.get(1);
        return LayoutReader.columnsList((ListNode)table.get(2));
    }
}
