/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.Anchors;
import ru.aiedt.mcp.oform.layout.CommandPanels;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * Removal preserves what it does not name. An element removed from the item form leaves no
 * dangling anchor behind it: a surviving field whose edge explicitly named the removed label
 * reads back with that edge cleared, while its own record, handlers, data binding and every
 * unrelated anchor stay. A column removed from the list form's table leaves every surviving
 * column's record byte for byte and in order. A submenu removed from a command panel takes its
 * whole subtree of nested actions and leaves every surviving button - name, caption, kind,
 * action, picture, procedure and nested descendants - as it was, in order. Each proof runs the
 * real removal on a measured fixture, serializes, reparses, and reads the outcome back.
 */
public class ARemovalPreservesItsRemainingOrdinaryRecordsTest
{
    /**
     * Removing the label the surviving field is anchored to drops exactly that anchor: the
     * removal contract promises the element goes with its data binding and every anchor that
     * named it, so the survivor must read back with the edge cleared and nothing else touched.
     *
     * @throws Exception when the measured fixture cannot be read
     */
    @Test
    public void aRemovedElementLeavesNoDanglingAnchorBehindIt() throws Exception
    {
        String layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(editor.layout().elementNamed("НадписьНаименование")); //$NON-NLS-1$
        assertNotNull(editor.layout().elementNamed("Наименование")); //$NON-NLS-1$
        LayoutEditor.Outcome bound = Anchors.set(editor.root(), "Наименование", "top", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "НадписьНаименование.bottom", Integer.valueOf(4)); //$NON-NLS-1$
        assertTrue(bound.message, bound.done);
        assertEquals("НадписьНаименование.bottom+4", anchorsOf(editor, "Наименование").get("top")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(editor.text(), editor.text().contains("{2,11,1,4}")); //$NON-NLS-1$

        LayoutEditor.Outcome removed = editor.removeElement("НадписьНаименование"); //$NON-NLS-1$
        assertTrue(removed.message, removed.done);

        String text = editor.text();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        FormLayout after = LayoutReader.read(BraceTree.parse(text), "Catalog", ALayoutIsReadOffTheContainerTest.NAMES); //$NON-NLS-1$
        assertNull(after.elementNamed("НадписьНаименование")); //$NON-NLS-1$
        FormLayout.Element survivor = after.elementNamed("Наименование"); //$NON-NLS-1$
        assertNotNull(survivor);
        assertEquals("Объект.Наименование", after.bindings.get("12")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, survivor.handlers.size());
        assertEquals("НаименованиеПриИзменении", survivor.handlers.get(0).procedure); //$NON-NLS-1$
        LayoutEditor reread = LayoutEditor.over(BraceTree.parse(text), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("none", anchorsOf(reread, "Наименование").get("top")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(text, text.contains("{2,11,1,4}")); //$NON-NLS-1$
        Map<String, Object> button = anchorsOf(reread, "КнопкаЗаписать"); //$NON-NLS-1$
        assertEquals("form-8", button.get("bottom")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("form-113", button.get("right")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Активен", after.bindings.get("16")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Объект.Артикул", after.bindings.get("13")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Removing the middle column of the list form's table removes its record alone: both
     * surviving columns keep their exact serialized records, in order, and their full semantic
     * content - kind, name, caption, width, control, value type and binding path. Byte identity
     * of the survivors is warranted here, not merely hoped for: the production removal drops the
     * addressed record from the columns list and rewrites the list's count, and nothing else.
     *
     * @throws Exception when the measured fixture cannot be read
     */
    @Test
    public void aRemovedColumnLeavesEverySurvivingRecordUntouched() throws Exception
    {
        String layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/list-form-v27.form"); //$NON-NLS-1$
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout before = LayoutReader.read(editor.root(), "Catalog"); //$NON-NLS-1$
        FormLayout.Element table = before.elementNamed("СправочникСписок"); //$NON-NLS-1$
        assertNotNull(table);
        assertEquals(3, table.columns.size());
        assertEquals("Картинка", table.columns.get(0).name); //$NON-NLS-1$
        assertEquals("Код", table.columns.get(1).name); //$NON-NLS-1$
        assertEquals("Наименование", table.columns.get(2).name); //$NON-NLS-1$
        List<String> printsBefore = columnFingerprints(table);
        List<String> rawBefore = columnRecords(editor.root());
        assertEquals(3, rawBefore.size());

        LayoutEditor.Outcome removed = editor.removeColumn("СправочникСписок", "Код"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(removed.message, removed.done);
        assertFalse(editor.removeColumn("СправочникСписок", "Код").done); //$NON-NLS-1$ //$NON-NLS-2$

        String text = editor.text();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        FormLayout after = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        FormLayout.Element shrunk = after.elementNamed("СправочникСписок"); //$NON-NLS-1$
        assertNotNull(shrunk);
        assertEquals(2, shrunk.columns.size());
        assertEquals(List.of(printsBefore.get(0), printsBefore.get(2)), columnFingerprints(shrunk));
        assertEquals(List.of(rawBefore.get(0), rawBefore.get(2)), columnRecords(BraceTree.parse(text)));
    }

    /**
     * Removing the populated submenu removes its whole subtree - the submenu record, its
     * buttons block and every action under it - and nothing else: every surviving button of the
     * panel, the empty submenu's own block included, keeps its name, caption, kind, action,
     * picture, procedure and nested descendants, in order. The comparison is over complete
     * semantic fingerprints rather than raw bytes: the panel's content block carries service
     * positions a removal may legitimately renumber, so byte invariance of the survivors is not
     * part of the contract and is not demanded.
     *
     * @throws Exception when the measured fixture cannot be read
     */
    @Test
    public void aRemovedSubmenuLeavesEverySurvivingButtonUntouched() throws Exception
    {
        String layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/panel-submenu-form-v27.form"); //$NON-NLS-1$
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout.Element panel = LayoutReader.read(editor.root(), "Catalog").elementNamed("Text24"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(panel);
        assertEquals(10, panel.buttons.size());
        assertEquals(10, childrenOf(buttonNamed(panel, "Text11")).size()); //$NON-NLS-1$
        assertTrue(childrenOf(buttonNamed(panel, "Text18")).isEmpty()); //$NON-NLS-1$
        List<String> before = buttonFingerprints(panel);
        List<String> expected = new ArrayList<>(before);
        expected.remove(panel.buttons.indexOf(buttonNamed(panel, "Text11"))); //$NON-NLS-1$

        LayoutEditor.Outcome removed = CommandPanels.removeButton(editor.root(), "Text24", "Text11"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(removed.message, removed.done);
        assertTrue(removed.message, removed.message.contains("submenu block")); //$NON-NLS-1$

        String text = editor.text();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        FormLayout.Element after = LayoutReader.read(BraceTree.parse(text), "Catalog").elementNamed("Text24"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(after);
        assertEquals(9, after.buttons.size());
        assertNull(buttonNamed(after, "Text11")); //$NON-NLS-1$
        for (String print : buttonFingerprints(after))
        {
            assertFalse(print, print.contains("standard:writeAndClose")); //$NON-NLS-1$
        }
        assertEquals(expected, buttonFingerprints(after));
    }

    /**
     * The anchor words of an element as the reader reports them.
     *
     * @param editor the editor whose layout to read
     * @param name the element
     * @return edge to anchor word
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> anchorsOf(LayoutEditor editor, String name)
    {
        FormLayout.Element element = editor.layout().elementNamed(name);
        assertNotNull(name, element);
        return (Map<String, Object>)element.details.get("anchors"); //$NON-NLS-1$
    }

    /**
     * A complete semantic fingerprint of every column of a table: each public field the reader
     * fills, the binding path segment by segment included.
     *
     * @param table the table element
     * @return one fingerprint per column, in order
     */
    private static List<String> columnFingerprints(FormLayout.Element table)
    {
        List<String> out = new ArrayList<>();
        for (FormLayout.Column column : table.columns)
        {
            StringBuilder print = new StringBuilder();
            print.append(column.kind).append('|').append(column.name).append('|').append(column.title).append('|')
                .append(column.width).append('|').append(column.valueType).append('|').append(column.control).append('|')
                .append(column.data).append('|').append(column.valueTableColumn).append('|');
            for (FormLayout.Segment segment : column.path)
            {
                print.append(segment.kind).append(':').append(segment.value).append(';');
            }
            out.add(print.toString());
        }
        return out;
    }

    /**
     * The serialized record of every column of the list fixture's table, in order, located by
     * the same measured navigation the column tests use for this fixture.
     *
     * @param root the brace tree of the list form
     * @return the column records, serialized
     */
    private static List<String> columnRecords(ListNode root)
    {
        ListNode body = (ListNode)root.get(1);
        ListNode rootPanel = (ListNode)body.get(2);
        ListNode children = (ListNode)rootPanel.get(2);
        ListNode table = (ListNode)children.get(1);
        ListNode columns = LayoutReader.columnsList((ListNode)table.get(2));
        List<String> out = new ArrayList<>();
        for (int i = 1; i < columns.size(); i++)
        {
            out.add(BraceTree.serialize((ListNode)columns.get(i)));
        }
        return out;
    }

    /**
     * A complete semantic fingerprint of every button of a panel: name, caption, procedure and
     * every detail the reader reports - kind, action, picture and, for a submenu, the nested
     * descendants with their own actions and kinds, printed recursively by the details map.
     *
     * @param panel the command panel
     * @return one fingerprint per button, in order
     */
    private static List<String> buttonFingerprints(FormLayout.Element panel)
    {
        List<String> out = new ArrayList<>();
        for (FormLayout.Button button : panel.buttons)
        {
            out.add(button.name + '|' + button.caption + '|' + button.procedure + '|' + button.details);
        }
        return out;
    }

    /**
     * The button of a panel with a name.
     *
     * @param panel the command panel
     * @param name the button's name
     * @return the button, or {@code null}
     */
    private static FormLayout.Button buttonNamed(FormLayout.Element panel, String name)
    {
        for (FormLayout.Button button : panel.buttons)
        {
            if (button.name != null && button.name.equals(name))
            {
                return button;
            }
        }
        return null;
    }

    /**
     * The nested descendants a submenu carries, as the reader reports them.
     *
     * @param button the button; must exist and be a submenu
     * @return the children maps, empty when the submenu's block is empty
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> childrenOf(FormLayout.Button button)
    {
        assertNotNull(button);
        Object children = button.details.get("children"); //$NON-NLS-1$
        return children instanceof List ? (List<Map<String, Object>>)children : List.of();
    }
}
