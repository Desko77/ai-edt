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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.layout.Anchors;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.ops.LayoutContext;
import ru.aiedt.mcp.oform.ops.OperationResult;
import ru.aiedt.mcp.oform.ops.OperationsCatalog;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * An element moves to another panel, to another page of the same parent, or to the form. The box
 * stays, both pages are recomputed, and anchors between the element and the neighbours the move
 * separates are released both ways. The same parent and the same page change no byte. A move
 * into the element itself or into its descendant is refused, and so is a parent or a page the
 * form does not have. The write round-trips byte for byte on layouts of versions 25, 26 and 27.
 */
public class AnElementIsMovedTest
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

    private static List<Integer> boxOf(FormLayout.Element element)
    {
        return List.of(Integer.valueOf(element.box[0]), Integer.valueOf(element.box[1]), Integer.valueOf(element.box[2]),
            Integer.valueOf(element.box[3]));
    }

    private static FormLayout.Element child(FormLayout.Element parent, String name)
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> anchorsOf(LayoutEditor editor, String name)
    {
        FormLayout.Element element = editor.layout().elementNamed(name);
        assertNotNull(name, element);
        return (Map<String, Object>)element.details.get("anchors"); //$NON-NLS-1$
    }

    private static void assertRoundTrip(String text)
    {
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
    }

    /** The element lands in the other panel, on the page named, with the box it had. */
    @Test
    public void anElementMovesBetweenPanels()
    {
        LayoutEditor editor = editor();
        Map<String, List<Integer>> boxes = new LinkedHashMap<>();
        for (FormLayout.Element element : editor.layout().allElements())
        {
            if (element.box != null)
            {
                boxes.put(element.name, boxOf(element));
            }
        }
        List<String> released = new ArrayList<>();
        LayoutEditor.Outcome moved = editor.moveElement("Наименование", "Панель1", "Общие", released); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(moved.message, moved.done);
        assertTrue(moved.message, moved.message.contains("Панель1")); //$NON-NLS-1$
        assertTrue(moved.message, moved.message.contains("Общие")); //$NON-NLS-1$
        String text = editor.text();
        assertRoundTrip(text);
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        FormLayout.Element field = layout.elementNamed("Наименование"); //$NON-NLS-1$
        assertEquals("Общие", field.page); //$NON-NLS-1$
        assertEquals(boxes.get("Наименование"), boxOf(field)); //$NON-NLS-1$
        assertNotNull(child(layout.elementNamed("Панель1"), "Наименование")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(child(layout.elementNamed("Панель1"), "Артикул")); //$NON-NLS-1$ //$NON-NLS-2$
        boolean stillOnTheForm = false;
        for (FormLayout.Element element : layout.elements)
        {
            if ("Наименование".equals(element.name)) //$NON-NLS-1$
            {
                stillOnTheForm = true;
            }
        }
        assertFalse(stillOnTheForm);
        for (FormLayout.Element element : layout.allElements())
        {
            if (element.box != null && boxes.containsKey(element.name))
            {
                assertEquals(element.name, boxes.get(element.name), boxOf(element));
            }
        }
        assertEquals(boxes.size(), layout.allElements().size());
    }

    /** Another page of the same parent keeps the holder and releases anchors to the page left behind. */
    @Test
    public void anElementMovesBetweenPagesOfOneParent()
    {
        LayoutEditor editor = editor();
        assertTrue(editor.addElement("Label", "НадписьРядом", "Панель1", "Прочее", new int[] { 6, 8, 80, 27 }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Рядом", null, null).done); //$NON-NLS-1$
        assertTrue(editor.addElement("Label", "Сосед", "Панель1", "Прочее", new int[] { 90, 8, 160, 27 }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Сосед", null, null).done); //$NON-NLS-1$
        // two neighbours, so the two directions do not close a cycle of anchors
        assertTrue(Anchors.set(editor.root(), "НадписьРядом", "top", "ФлагАктивен.bottom", Integer.valueOf(2)).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(Anchors.set(editor.root(), "Сосед", "left", "НадписьРядом.right", null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(Anchors.set(editor.root(), "НадписьРядом", "bottom", "form", Integer.valueOf(3)).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        List<Integer> box = boxOf(editor.layout().elementNamed("НадписьРядом")); //$NON-NLS-1$
        List<String> released = new ArrayList<>();
        LayoutEditor.Outcome moved = editor.moveElement("НадписьРядом", "Панель1", "Общие", released); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(moved.message, moved.done);
        String text = editor.text();
        assertRoundTrip(text);
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        assertEquals("Общие", layout.elementNamed("НадписьРядом").page); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Прочее", layout.elementNamed("ФлагАктивен").page); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(child(layout.elementNamed("Панель1"), "НадписьРядом")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(child(layout.elementNamed("Панель1"), "ФлагАктивен")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(box, boxOf(layout.elementNamed("НадписьРядом"))); //$NON-NLS-1$
        assertEquals("none", anchorsOf(editor, "НадписьРядом").get("top")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("none", anchorsOf(editor, "Сосед").get("left")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("form+3", anchorsOf(editor, "НадписьРядом").get("bottom")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Прочее", layout.elementNamed("Сосед").page); //$NON-NLS-1$ //$NON-NLS-2$
        String listed = released.toString();
        assertTrue(listed, listed.contains("НадписьРядом.top -> ФлагАктивен.bottom+2")); //$NON-NLS-1$
        assertTrue(listed, listed.contains("Сосед.left -> НадписьРядом.right")); //$NON-NLS-1$
        assertFalse(listed, listed.contains("form")); //$NON-NLS-1$
    }

    /** The form is the parent named {@code form}; the element leaves the panel. */
    @Test
    public void anElementMovesToTheForm()
    {
        LayoutEditor editor = editor();
        List<Integer> box = boxOf(editor.layout().elementNamed("ФлагАктивен")); //$NON-NLS-1$
        List<String> released = new ArrayList<>();
        LayoutEditor.Outcome moved = editor.moveElement("ФлагАктивен", "form", "Основная", released); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(moved.message, moved.done);
        assertTrue(moved.message, moved.message.contains("the form")); //$NON-NLS-1$
        String text = editor.text();
        assertRoundTrip(text);
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        FormLayout.Element flag = layout.elementNamed("ФлагАктивен"); //$NON-NLS-1$
        assertEquals("Основная", flag.page); //$NON-NLS-1$
        assertEquals(box, boxOf(flag));
        assertNull(child(layout.elementNamed("Панель1"), "ФлагАктивен")); //$NON-NLS-1$ //$NON-NLS-2$
        boolean onTheForm = false;
        for (FormLayout.Element element : layout.elements)
        {
            if ("ФлагАктивен".equals(element.name)) //$NON-NLS-1$
            {
                onTheForm = true;
            }
        }
        assertTrue(onTheForm);
    }

    /** The parent and the page the element already has leave the layout byte for byte. */
    @Test
    public void theSameParentAndPageChangesNothing()
    {
        LayoutEditor editor = editor();
        String before = editor.text();
        List<String> released = new ArrayList<>();
        LayoutEditor.Outcome same = editor.moveElement("Наименование", "form", "Основная", released); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(same.message, same.done);
        assertEquals("unchanged", same.message); //$NON-NLS-1$
        assertTrue(released.isEmpty());
        assertEquals(before, editor.text());
        LayoutEditor.Outcome spelled = editor.moveElement("Наименование", "FORM", "основная", released); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("unchanged", spelled.message); //$NON-NLS-1$
        assertEquals(before, editor.text());
    }

    /** A neighbour that anchored to the element loses that anchor when the element changes parent. */
    @Test
    public void anIncomingAnchorIsReleased()
    {
        LayoutEditor editor = editor();
        assertTrue(Anchors.set(editor.root(), "НадписьНаименование", "top", "Наименование.bottom", Integer.valueOf(4)).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        List<String> released = new ArrayList<>();
        LayoutEditor.Outcome moved = editor.moveElement("Наименование", "Панель1", "Общие", released); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(moved.message, moved.done);
        assertEquals("none", anchorsOf(editor, "НадписьНаименование").get("top")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(released.toString(), released.toString().contains("НадписьНаименование.top -> Наименование.bottom+4")); //$NON-NLS-1$
        assertEquals("Основная", editor.layout().elementNamed("НадписьНаименование").page); //$NON-NLS-1$ //$NON-NLS-2$
        assertRoundTrip(editor.text());
    }

    /** An anchor of the element to a former neighbour goes; an anchor to the form stays. */
    @Test
    public void anOutgoingAnchorIsReleased()
    {
        LayoutEditor editor = editor();
        assertTrue(Anchors.set(editor.root(), "Наименование", "top", "НадписьНаименование.bottom", Integer.valueOf(4)).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(Anchors.set(editor.root(), "Наименование", "right", "form", null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<String> released = new ArrayList<>();
        LayoutEditor.Outcome moved = editor.moveElement("Наименование", "Панель1", "Общие", released); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(moved.message, moved.done);
        assertEquals("none", anchorsOf(editor, "Наименование").get("top")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("form", anchorsOf(editor, "Наименование").get("right")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String listed = released.toString();
        assertTrue(listed, listed.contains("Наименование.top -> НадписьНаименование.bottom+4")); //$NON-NLS-1$
        assertFalse(listed, listed.contains("form")); //$NON-NLS-1$
        assertRoundTrip(editor.text());
    }

    /** No such element, no such parent, a parent that is not a panel, and no such page are refused. */
    @Test
    public void theContractRefusalsNameTheCause()
    {
        LayoutEditor editor = editor();
        String before = editor.text();
        LayoutEditor.Outcome missing = editor.moveElement("Нигде", "form", "Основная", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(missing.done);
        assertTrue(missing.message, missing.message.contains("No element")); //$NON-NLS-1$
        LayoutEditor.Outcome noParent = editor.moveElement("Наименование", "НетПанели", "Основная", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(noParent.done);
        assertTrue(noParent.message, noParent.message.contains("No parent")); //$NON-NLS-1$
        LayoutEditor.Outcome notPanel = editor.moveElement("ФлагАктивен", "Наименование", "Основная", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(notPanel.done);
        assertTrue(notPanel.message, notPanel.message.contains("not a panel")); //$NON-NLS-1$
        LayoutEditor.Outcome noPage = editor.moveElement("Наименование", "Панель1", "НетСтраницы", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(noPage.done);
        assertTrue(noPage.message, noPage.message.contains("No page")); //$NON-NLS-1$
        assertTrue(noPage.message, noPage.message.contains("Общие")); //$NON-NLS-1$
        assertEquals(before, editor.text());
    }

    /** A panel cannot be moved into itself or into a panel that sits inside it. */
    @Test
    public void anElementCannotMoveIntoItselfOrItsDescendant()
    {
        LayoutEditor editor = editor();
        assertTrue(editor.addElement("Panel", "Вложенная", "Панель1", "Общие", new int[] { 1, 1, 40, 40 }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            null, null, null).done);
        String before = editor.text();
        LayoutEditor.Outcome itself = editor.moveElement("Панель1", "Панель1", "Общие", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(itself.done);
        assertTrue(itself.message, itself.message.contains("itself")); //$NON-NLS-1$
        LayoutEditor.Outcome into = editor.moveElement("Панель1", "Вложенная", "Страница1", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(into.done);
        assertTrue(into.message, into.message.contains("descendant")); //$NON-NLS-1$
        assertEquals(before, editor.text());
    }

    /** The page left behind shrinks to what remains; the page landed on grows to the element's box. */
    @Test
    public void bothPagesAreRecomputed()
    {
        LayoutEditor editor = editor();
        assertTrue(editor.addElement("Panel", "ПанельГраниц", null, null, new int[] { 8, 200, 400, 400 }, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, null).done);
        assertTrue(editor.addPage("ПанельГраниц", "Вторая", "Вторая").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(editor.setTabs("ПанельГраниц", false).done); //$NON-NLS-1$
        assertTrue(editor.addElement("Label", "Узкая", "ПанельГраниц", "Страница1", new int[] { 17, 23, 173, 41 }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "У", null, null).done); //$NON-NLS-1$
        assertTrue(editor.addElement("Label", "Широкая", "ПанельГраниц", "Страница1", new int[] { 19, 29, 641, 97 }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Ш", null, null).done); //$NON-NLS-1$
        LayoutEditor.Outcome moved = editor.moveElement("Широкая", "ПанельГраниц", "Вторая", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(moved.message, moved.done);
        String text = editor.text();
        assertRoundTrip(text);
        assertTrue(text, text.contains("{2,173,1,1,3,0,0,0,0}")); //$NON-NLS-1$
        assertTrue(text, text.contains("{2,41,0,1,4,0,0,0,0}")); //$NON-NLS-1$
        assertTrue(text, text.contains("{2,641,1,1,3,1,0,0,0}")); //$NON-NLS-1$
        assertTrue(text, text.contains("{2,97,0,1,4,1,0,0,0}")); //$NON-NLS-1$
        assertFalse(text, text.contains("{2,641,1,1,3,0,0,0,0}")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        assertEquals("Вторая", layout.elementNamed("Широкая").page); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of(Integer.valueOf(19), Integer.valueOf(29), Integer.valueOf(641), Integer.valueOf(97)),
            boxOf(layout.elementNamed("Широкая"))); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, layout.elementNamed("ПанельГраниц").details.get("tabs")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Versions 25, 26 and 27: the moved element reads back, and the text round-trips byte for byte. */
    @Test
    public void theMoveRoundTripsOnEveryMeasuredVersion() throws java.io.IOException
    {
        String[][] fixtures = { { "fixtures/settings-form-v25.form", "Report" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "fixtures/settings-form-v26.form", "Report" }, //$NON-NLS-1$ //$NON-NLS-2$
            { "fixtures/processing-form-v27.form", "Report" } }; //$NON-NLS-1$ //$NON-NLS-2$
        int[] box = { 12, 18, 140, 37 };
        for (String[] fixture : fixtures)
        {
            String text = ALayoutIsReadOffTheContainerTest.fixture(fixture[0]);
            LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), fixture[1], "ru"); //$NON-NLS-1$
            assertTrue(fixture[0], editor.addElement("Panel", "ProbePanel", null, null, new int[] { 8, 8, 300, 200 }, //$NON-NLS-1$ //$NON-NLS-2$
                null, null, null).done);
            assertTrue(fixture[0], editor.addElement("Label", "ProbeMove", null, null, box, "Probe", null, null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            LayoutEditor.Outcome moved = editor.moveElement("ProbeMove", "ProbePanel", "Страница1", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(fixture[0] + " " + moved.message, moved.done); //$NON-NLS-1$
            String after = editor.text();
            assertEquals(fixture[0], after, BraceTree.serialize(BraceTree.parse(after)));
            FormLayout layout = LayoutReader.read(BraceTree.parse(after), fixture[1]);
            FormLayout.Element probe = layout.elementNamed("ProbeMove"); //$NON-NLS-1$
            assertNotNull(fixture[0], probe);
            assertEquals(fixture[0], "Страница1", probe.page); //$NON-NLS-1$
            assertEquals(fixture[0], List.of(Integer.valueOf(box[0]), Integer.valueOf(box[1]), Integer.valueOf(box[2]),
                Integer.valueOf(box[3])), boxOf(probe));
            assertNotNull(fixture[0], child(layout.elementNamed("ProbePanel"), "ProbeMove")); //$NON-NLS-1$ //$NON-NLS-2$
            String frozen = after;
            LayoutEditor.Outcome again = editor.moveElement("ProbeMove", "ProbePanel", "Страница1", new ArrayList<>()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals(fixture[0], "unchanged", again.message); //$NON-NLS-1$
            assertEquals(fixture[0], frozen, editor.text());
        }
    }

    /** The operation's answer lists the anchors the move released. */
    @Test
    public void theOperationListsTheReleasedAnchors()
    {
        LayoutEditor editor = editor();
        assertTrue(Anchors.set(editor.root(), "Наименование", "top", "НадписьНаименование.bottom", Integer.valueOf(4)).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        ToolResult answer = ToolResult.success();
        Map<String, String> params = Map.of("elementName", "Наименование", "parent", "Панель1", "page", "Общие"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        LayoutContext context = new LayoutContext(null, "Catalog.Products.Form.ItemForm", "Catalog", null, editor.root(), //$NON-NLS-1$ //$NON-NLS-2$
            editor, params, answer);
        OperationResult result = OperationsCatalog.find("move_element").layout.apply(context); //$NON-NLS-1$
        assertTrue(result.outcome.message, result.outcome.done);
        JsonObject json = JsonParser.parseString(answer.toJson()).getAsJsonObject();
        assertTrue(json.getAsJsonArray("anchorsRemoved").toString(), //$NON-NLS-1$
            json.getAsJsonArray("anchorsRemoved").toString().contains("Наименование.top -> НадписьНаименование.bottom+4")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Общие", editor.layout().elementNamed("Наименование").page); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A blank argument is refused by name, and an unchanged move reports that outcome. */
    @Test
    public void theOperationRefusesABlankArgumentAndReportsUnchanged()
    {
        LayoutEditor editor = editor();
        ToolResult missing = ToolResult.success();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("parent", "form"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("page", "Основная"); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutContext context = new LayoutContext(null, "Catalog.Products.Form.ItemForm", "Catalog", null, editor.root(), //$NON-NLS-1$ //$NON-NLS-2$
            editor, params, missing);
        OperationResult refused = OperationsCatalog.find("move_element").layout.apply(context); //$NON-NLS-1$
        assertFalse(refused.outcome.done);
        assertTrue(refused.outcome.message, refused.outcome.message.contains("elementName")); //$NON-NLS-1$
        String before = editor.text();
        ToolResult same = ToolResult.success();
        Map<String, String> again = Map.of("elementName", "Наименование", "parent", "form", "page", "Основная"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        LayoutContext sameContext = new LayoutContext(null, "Catalog.Products.Form.ItemForm", "Catalog", null, //$NON-NLS-1$ //$NON-NLS-2$
            editor.root(), editor, again, same);
        OperationResult unchanged = OperationsCatalog.find("move_element").layout.apply(sameContext); //$NON-NLS-1$
        assertEquals("unchanged", unchanged.outcome.message); //$NON-NLS-1$
        assertEquals(0, JsonParser.parseString(same.toJson()).getAsJsonObject().getAsJsonArray("anchorsRemoved").size()); //$NON-NLS-1$
        assertEquals(before, editor.text());
    }
}
