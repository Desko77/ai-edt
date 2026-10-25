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

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.ops.LayoutContext;
import ru.aiedt.mcp.oform.ops.MoveOperations;
import ru.aiedt.mcp.oform.ops.OperationResult;
import ru.aiedt.mcp.oform.ops.OperationSpec;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The move catalog's own handler at work: an element is moved to another panel and to another
 * page of the form with the box it had, the page it lands on grows to the element's geometry,
 * and the answer lists the anchors the move released. A target the form does not have - no such
 * page, a parent that is the element itself - and a blank element, parent or page are refused by
 * name, and a refusal leaves the layout as it was.
 */
public class TheMoveOperationsCatalogTest
{
    private static String layoutText;

    /** Reads the fixture once. */
    @BeforeClass
    public static void theFixture() throws IOException
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
    }

    /**
     * @param params the operation's arguments
     * @return a context over a fresh editor of the fixture
     */
    private static LayoutContext context(Map<String, String> params)
    {
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        return new LayoutContext(null, "Catalog.Products.Form.ItemForm", "Catalog", null, editor.root(), editor, //$NON-NLS-1$ //$NON-NLS-2$
            params, ToolResult.success());
    }

    /**
     * @return the spec of the move in the move catalog's own list
     */
    private static OperationSpec spec()
    {
        List<OperationSpec> specs = MoveOperations.specs();
        assertEquals(1, specs.size());
        return specs.get(0);
    }

    /** The catalog declares one operation, an edit of an existing form that writes. */
    @Test
    public void theCatalogDeclaresTheMove()
    {
        OperationSpec move = spec();
        assertEquals("move_element", move.name); //$NON-NLS-1$
        assertEquals(OperationSpec.Kind.LAYOUT, move.kind);
        assertTrue(move.writes);
        assertTrue(move.needsProject);
        List<String> params = move.params.stream().map(param -> param.name).toList();
        for (String expected : new String[] { "elementName", "parent", "page" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue(expected, params.contains(expected));
        }
    }

    /** An element is moved to another panel on the page named, with the box it had. */
    @Test
    public void anElementIsReparentedWithItsBoxKept()
    {
        LayoutContext moved = context(Map.of("elementName", "Наименование", "parent", "Панель1", "page", "Общие")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        OperationResult result = spec().layout.apply(moved); //$NON-NLS-1$
        assertTrue(result.outcome.message, result.outcome.done);
        String text = moved.editor.text();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        FormLayout.Element field = layout.elementNamed("Наименование"); //$NON-NLS-1$
        assertEquals("Общие", field.page); //$NON-NLS-1$
        assertEquals(List.of(110, 20, 400, 39), //$NON-NLS-1$
            List.of(Integer.valueOf(field.box[0]), Integer.valueOf(field.box[1]), Integer.valueOf(field.box[2]),
                Integer.valueOf(field.box[3])));
        assertNotNull("the element sits under the panel now", childNamed(layout.elementNamed("Панель1"), "Наименование")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject answer = JsonParser.parseString(moved.answer.toJson()).getAsJsonObject();
        assertTrue(answer.has("anchorsRemoved")); //$NON-NLS-1$
    }

    /** An element is moved to another page of the form, and a fresh panel takes a fresh element with its geometry. */
    @Test
    public void anElementIsMovedToAnotherPageAndIntoAFreshPanel()
    {
        LayoutContext paged = context(Map.of("elementName", "Наименование", "parent", "form", "page", "Дополнительно")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        OperationResult page = spec().layout.apply(paged); //$NON-NLS-1$
        assertTrue(page.outcome.message, page.outcome.done);
        assertEquals("Дополнительно", LayoutReader.read(paged.editor.root(), "Catalog").elementNamed("Наименование").page); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.addElement("Panel", "ПанельЦели", null, null, new int[] { 8, 8, 300, 200 }, null, null, //$NON-NLS-1$ //$NON-NLS-2$
            null).done);
        assertTrue(editor.addElement("Label", "НадписьЦель", null, null, new int[] { 12, 18, 140, 37 }, "Цель", null, //$NON-NLS-1$ //$NON-NLS-2$
            null).done);
        LayoutContext intoPanel = new LayoutContext(null, "Catalog.Products.Form.ItemForm", "Catalog", null, //$NON-NLS-1$ //$NON-NLS-2$
            editor.root(), editor, Map.of("elementName", "НадписьЦель", "parent", "ПанельЦели", "page", "Страница1"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            ToolResult.success());
        OperationResult inside = spec().layout.apply(intoPanel); //$NON-NLS-1$
        assertTrue(inside.outcome.message, inside.outcome.done);
        String text = editor.text();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        // the panel's first page grew to the element's geometry: its rows carry the element's box
        assertTrue(text, text.contains("{2,140,1,1,3,0,0,0,0}")); //$NON-NLS-1$
        assertTrue(text, text.contains("{2,37,0,1,4,0,0,0,0}")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        assertEquals("Страница1", layout.elementNamed("НадписьЦель").page); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(childNamed(layout.elementNamed("ПанельЦели"), "НадписьЦель")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A target the form does not have refuses by name, and the layout stays as it was. */
    @Test
    public void aRejectedTargetLeavesTheContentUnchanged()
    {
        LayoutContext noPage = context(Map.of("elementName", "Наименование", "parent", "Панель1", "page", "НетСтраницы")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        String before = noPage.editor.text();
        OperationResult refused = spec().layout.apply(noPage); //$NON-NLS-1$
        assertFalse(refused.outcome.done);
        assertTrue(refused.outcome.message, refused.outcome.message.contains("No page")); //$NON-NLS-1$
        assertTrue(refused.outcome.message, refused.outcome.message.contains("НетСтраницы")); //$NON-NLS-1$
        assertEquals(before, noPage.editor.text());

        LayoutContext itself = context(Map.of("elementName", "Панель1", "parent", "Панель1", "page", "Общие")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OperationResult self = spec().layout.apply(itself); //$NON-NLS-1$
        assertFalse(self.outcome.done);
        assertTrue(self.outcome.message, self.outcome.message.contains("itself")); //$NON-NLS-1$
    }

    /** A blank element, parent or page refuses by name before anything is looked at. */
    @Test
    public void blankArgumentsAreRefusedByName()
    {
        String[][] cases = { { "move_element needs elementName", null, "form", "Основная" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            { "move_element needs parent", "Наименование", null, "Основная" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            { "move_element needs page", "Наименование", "form", null } }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String[] one : cases)
        {
            java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
            if (one[1] != null)
            {
                params.put("elementName", one[1]); //$NON-NLS-1$
            }
            if (one[2] != null)
            {
                params.put("parent", one[2]); //$NON-NLS-1$
            }
            if (one[3] != null)
            {
                params.put("page", one[3]); //$NON-NLS-1$
            }
            LayoutContext blank = context(params);
            String before = blank.editor.text();
            OperationResult result = spec().layout.apply(blank); //$NON-NLS-1$
            assertFalse(one[0], result.outcome.done);
            assertTrue(one[0], result.outcome.message.contains(one[0]));
            assertEquals(one[0], before, blank.editor.text());
        }
    }

    /**
     * @param parent the element to look under
     * @param name the child's name
     * @return the child, or {@code null}
     */
    private static FormLayout.Element childNamed(FormLayout.Element parent, String name)
    {
        for (FormLayout.Element one : parent.children)
        {
            if (name.equals(one.name))
            {
                return one;
            }
        }
        return null;
    }
}
