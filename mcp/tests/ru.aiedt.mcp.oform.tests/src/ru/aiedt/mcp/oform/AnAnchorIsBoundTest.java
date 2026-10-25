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

import ru.aiedt.mcp.oform.layout.Anchors;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;

/**
 * The anchors of an element's four edges: bound to the form, to a neighbour of the same parent
 * and page, or to none - each with the back reference the target carries for it, kept in step on
 * every bind, rebind and removal. A binding to the element itself, to an element of another
 * parent or page, and a chain of anchors that would close a cycle are refused; so are an edge and
 * a value the measured format does not name. An anchor taken away and put back leaves the form as
 * it was, byte for byte.
 */
public class AnAnchorIsBoundTest
{
    private static String layoutText;

    @BeforeClass
    public static void theFixture() throws java.io.IOException
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
    }

    private static LayoutEditor editor()
    {
        return LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> anchorsOf(LayoutEditor editor, String name)
    {
        FormLayout.Element element = editor.layout().elementNamed(name);
        assertNotNull(name, element);
        return (Map<String, Object>)element.details.get("anchors"); //$NON-NLS-1$
    }

    /** An edge binds the form: the slot names the target 0 and the root panel carries the back reference. */
    @Test
    public void anEdgeIsBoundToTheForm()
    {
        LayoutEditor editor = editor();
        LayoutEditor.Outcome bound = Anchors.set(editor.root(), "Панель1", "top", "form", Integer.valueOf(4)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(bound.message, bound.done);
        String text = editor.text();
        assertEquals(text, text, BraceTree.serialize(BraceTree.parse(text)));
        // the slot at position 6 names the form's top edge with the offset
        assertTrue(text, text.contains("{2,0,0,4}")); //$NON-NLS-1$
        assertEquals("form+4", anchorsOf(editor, "Панель1").get("top")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // the root panel's first back-reference list grew by {0, <the panel's id>, 0}
        assertTrue(text, text.contains("{0,15,0}")); //$NON-NLS-1$
        // without an offset the word carries none
        assertTrue(Anchors.set(editor.root(), "Панель1", "top", "form", null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("form", anchorsOf(editor, "Панель1").get("top")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** An edge binds a neighbour of the same parent and page; the neighbour carries the back reference. */
    @Test
    public void anEdgeIsBoundToANeighbour()
    {
        LayoutEditor editor = editor();
        LayoutEditor.Outcome bound = Anchors.set(editor.root(), "Наименование", "top", "НадписьНаименование.bottom", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Integer.valueOf(4));
        assertTrue(bound.message, bound.done);
        assertTrue(bound.message, bound.message.contains("НадписьНаименование.bottom")); //$NON-NLS-1$
        String text = editor.text();
        assertTrue(text, text.contains("{2,11,1,4}")); //$NON-NLS-1$
        Map<String, Object> anchors = anchorsOf(editor, "Наименование"); //$NON-NLS-1$
        assertEquals("НадписьНаименование.bottom+4", anchors.get("top")); //$NON-NLS-1$ //$NON-NLS-2$
        // the label's second list (its bottom edge) grew to one entry naming the field
        assertTrue(text, text.contains("1,\r\n{0,12,0}")); //$NON-NLS-1$
    }

    /** A rebind moves the back reference to the new target's list; none takes it away entirely. */
    @Test
    public void anAnchorIsReboundAndRemoved()
    {
        LayoutEditor editor = editor();
        assertTrue(Anchors.set(editor.root(), "Наименование", "top", "НадписьНаименование.bottom", Integer.valueOf(4)).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(Anchors.set(editor.root(), "Наименование", "top", "КнопкаЗаписать.bottom", null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String text = editor.text();
        // the button's bottom list now holds the field's entry
        assertTrue(text, text.contains("{0,12,0}")); //$NON-NLS-1$
        assertEquals("КнопкаЗаписать.bottom", anchorsOf(editor, "Наименование").get("top")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        LayoutEditor.Outcome removed = Anchors.set(editor.root(), "Наименование", "top", "none", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(removed.message, removed.done);
        text = editor.text();
        assertEquals(text, text, BraceTree.serialize(BraceTree.parse(text)));
        assertEquals("none", anchorsOf(editor, "Наименование").get("top")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // the button's own bottom list carries no entry of the field any more
        assertFalse(text, text.contains("{0,12,0}")); //$NON-NLS-1$
    }

    /** An anchor taken away and put back leaves the form as it was, byte for byte, on every version. */
    @Test
    public void anAnchorTakenAwayAndPutBackIsByteForByte() throws java.io.IOException
    {
        // per version: the element to bind, and the panel beside it on the form
        String[][] pairs = { {"fixtures/settings-form-v25.form", "Text7", "Text10"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"fixtures/settings-form-v26.form", "Text7", "Text11"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"fixtures/processing-form-v27.form", "Text11", "Text14"}}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String[] pair : pairs)
        {
            String fixture = pair[0];
            String text = ALayoutIsReadOffTheContainerTest.fixture(fixture);
            LayoutEditor first = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            String target = pair[2] + ".right"; //$NON-NLS-1$
            assertTrue(fixture, Anchors.set(first.root(), pair[1], "left", target, Integer.valueOf(6)).done); //$NON-NLS-1$ //$NON-NLS-2$
            String bound = first.text();
            LayoutEditor second = LayoutEditor.over(BraceTree.parse(bound), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(fixture, Anchors.set(second.root(), pair[1], "left", "none", null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            LayoutEditor third = LayoutEditor.over(BraceTree.parse(second.text()), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(fixture, Anchors.set(third.root(), pair[1], "left", target, Integer.valueOf(6)).done); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(fixture, bound, third.text());
        }
        // the fixture's own binding: the button Text67 of the v27 processing form binds its left
        // edge to the field Text66; taken away and put back, the original bytes come back
        String text = ALayoutIsReadOffTheContainerTest.fixture("fixtures/processing-form-v27.form"); //$NON-NLS-1$
        LayoutEditor away = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(Anchors.set(away.root(), "Text67", "left", "none", null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        LayoutEditor back = LayoutEditor.over(BraceTree.parse(away.text()), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(Anchors.set(back.root(), "Text67", "left", "Text66.right", Integer.valueOf(4)).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(text, back.text());
    }

    /** A binding to the element itself, to another parent's element or another page is refused. */
    @Test
    public void theTargetMustBeANeighbourOfTheSameParentAndPage()
    {
        LayoutEditor editor = editor();
        LayoutEditor.Outcome itself = Anchors.set(editor.root(), "Наименование", "left", "Наименование.right", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(itself.done);
        assertTrue(itself.message, itself.message.contains("itself")); //$NON-NLS-1$
        // the checkbox sits in the panel, the label on the form
        LayoutEditor.Outcome otherParent = Anchors.set(editor.root(), "ФлагАктивен", "top", "НадписьНаименование.bottom", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(otherParent.done);
        assertTrue(otherParent.message, otherParent.message.contains("same")); //$NON-NLS-1$
        // the article field sits on the second page, the name field on the first
        LayoutEditor.Outcome otherPage = Anchors.set(editor.root(), "Артикул", "left", "Наименование.right", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(otherPage.done);
        assertTrue(otherPage.message, otherPage.message.contains("page")); //$NON-NLS-1$
        LayoutEditor.Outcome missing = Anchors.set(editor.root(), "Наименование", "left", "Нигде.right", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(missing.done);
        LayoutEditor.Outcome badEdge = Anchors.set(editor.root(), "Наименование", "middle", "form", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(badEdge.done);
        assertTrue(badEdge.message, badEdge.message.contains("top, bottom, left, right")); //$NON-NLS-1$
        LayoutEditor.Outcome badValue = Anchors.set(editor.root(), "Наименование", "left", "НадписьНаименование", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(badValue.done);
    }

    /** A chain of anchors that would close a cycle is refused before anything is written. */
    @Test
    public void aCycleIsRefused()
    {
        LayoutEditor editor = editor();
        assertTrue(Anchors.set(editor.root(), "Наименование", "top", "НадписьНаименование.bottom", null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        LayoutEditor.Outcome cycle = Anchors.set(editor.root(), "НадписьНаименование", "left", "Наименование.right", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(cycle.done);
        assertTrue(cycle.message, cycle.message.contains("cycle")); //$NON-NLS-1$
        // the refused write changed nothing
        LayoutEditor fresh = editor();
        assertTrue(Anchors.set(fresh.root(), "Наименование", "top", "НадписьНаименование.bottom", null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(fresh.text(), editor.text());
    }

    /** What the fixture carries: edges bound to the form and to missing elements read as words. */
    @Test
    public void theFixtureAnchorsAreRead()
    {
        LayoutEditor editor = editor();
        Map<String, Object> button = anchorsOf(editor, "КнопкаЗаписать"); //$NON-NLS-1$
        assertEquals("form-8", button.get("bottom")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("form-113", button.get("right")); //$NON-NLS-1$ //$NON-NLS-2$
        // the label's left edge names an element the sanitized fixture no longer carries
        assertEquals("element#213.right", anchorsOf(editor, "НадписьНаименование").get("left")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // the edges without a binding read as none, the bound edge as its word
        Map<String, Object> panel = anchorsOf(editor, "Панель1"); //$NON-NLS-1$
        assertEquals("form-8", panel.get("right")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("none", panel.get("bottom")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("none", panel.get("left")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
