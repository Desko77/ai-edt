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

import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.ops.LayoutContext;
import ru.aiedt.mcp.oform.ops.PropertyHandlers;
import ru.aiedt.mcp.oform.ops.StyleOperations;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The style theme declares no operation of its own: it registers its properties, and the
 * registered handlers do the work. Through them visibility is set and read back, the read-only
 * flag is measured on a field alone, a panel draws and drops its tab strip, a font is set from a
 * family and a size and reset by auto, and an edge is anchored to a neighbour with its offset
 * and released to none. A target the form does not have, a value outside the measured encodings
 * and the edit format are refused, and a refusal leaves the layout as it was.
 */
public class TheStyleOperationsPropertiesTest
{
    private static String layoutText;

    /** Reads the fixture and registers the theme's properties once. */
    @BeforeClass
    public static void theFixtureAndTheTheme() throws IOException
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        StyleOperations.specs();
    }

    /**
     * @param params the property's arguments
     * @return a context over a fresh editor of the fixture
     */
    private static LayoutContext context(Map<String, String> params)
    {
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        return new LayoutContext(null, "Catalog.Products.Form.ItemForm", "Catalog", null, editor.root(), editor, //$NON-NLS-1$ //$NON-NLS-2$
            params, ToolResult.success());
    }

    /**
     * @param name the property's name
     * @param params the property's arguments
     * @return what the registered handler answered
     */
    private static LayoutEditor.Outcome set(String name, Map<String, String> params)
    {
        PropertyHandlers.PropertyHandler handler = PropertyHandlers.find(name);
        assertNotNull("the property " + name + " is registered", handler); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutContext context = context(params);
        return handler.apply(context, params.get("elementName")); //$NON-NLS-1$
    }

    /**
     * @param name the property's name
     * @param params the property's arguments
     * @return the outcome together with the context it ran over
     */
    private static Map.Entry<LayoutEditor.Outcome, LayoutContext> applied(String name, Map<String, String> params)
    {
        PropertyHandlers.PropertyHandler handler = PropertyHandlers.find(name);
        assertNotNull(handler);
        LayoutContext context = context(params);
        return Map.entry(handler.apply(context, params.get("elementName")), context); //$NON-NLS-1$
    }

    /** The theme declares no operation of its own and registers its properties under their names. */
    @Test
    public void theThemeRegistersItsPropertiesAndDeclaresNoOperation()
    {
        assertTrue("the style theme adds no operation of its own", StyleOperations.specs().isEmpty()); //$NON-NLS-1$
        List<String> names = PropertyHandlers.names();
        for (String expected : new String[] { "visible", "enabled", "readOnly", "tabs", "font", "textColor", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "backColor", "borderColor", "alignment", "format", "editFormat", "anchor" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            assertTrue(expected, names.contains(expected));
        }
        assertNotNull(PropertyHandlers.find("EDITFORMAT")); //$NON-NLS-1$
    }

    /** Visibility is set and read back through the registered handler; a bogus target refuses with nothing changed. */
    @Test
    public void visibilityIsSetAndReadBack()
    {
        Map.Entry<LayoutEditor.Outcome, LayoutContext> hidden = applied("visible", //$NON-NLS-1$
            Map.of("elementName", "Наименование", "value", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(hidden.getKey().message, hidden.getKey().done);
        FormLayout.Element concealed = LayoutReader.read(hidden.getValue().editor.root(), "Catalog") //$NON-NLS-1$
            .elementNamed("Наименование"); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, concealed.details.get("visible")); //$NON-NLS-1$

        Map.Entry<LayoutEditor.Outcome, LayoutContext> shown = applied("visible", //$NON-NLS-1$
            Map.of("elementName", "Наименование", "value", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(shown.getKey().message, shown.getKey().done);
        FormLayout.Element field = LayoutReader.read(shown.getValue().editor.root(), "Catalog").elementNamed("Наименование"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("back at the default the layout names no visibility", field.details.get("visible")); //$NON-NLS-1$ //$NON-NLS-2$

        LayoutEditor.Outcome bogus = set("visible", Map.of("elementName", "Нигде", "value", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(bogus.done);
        assertTrue(bogus.message, bogus.message.contains("Нигде")); //$NON-NLS-1$
        LayoutEditor.Outcome noValue = set("visible", Map.of("elementName", "Наименование")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(noValue.done);
        assertTrue(noValue.message, noValue.message.contains("set_property visible needs")); //$NON-NLS-1$
    }

    /** The read-only flag is a field's alone; every other kind is refused by name with nothing changed. */
    @Test
    public void theReadOnlyFlagIsAFieldAlone()
    {
        LayoutEditor.Outcome field = set("readOnly", Map.of("elementName", "Наименование", "value", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(field.message, field.done);
        LayoutEditor.Outcome label = set("readOnly", Map.of("elementName", "НадписьНаименование", "value", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(label.done);
        assertTrue(label.message, label.message.contains("Label")); //$NON-NLS-1$
    }

    /** A panel draws its tab strip and drops it again through the registered handler. */
    @Test
    public void tabsAreDrawnAndDropped()
    {
        Map.Entry<LayoutEditor.Outcome, LayoutContext> off = applied("tabs", //$NON-NLS-1$
            Map.of("elementName", "Панель1", "value", "false")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(off.getKey().message, off.getKey().done);
        assertEquals(Boolean.FALSE, panelOf(off.getValue()).details.get("tabs")); //$NON-NLS-1$

        Map.Entry<LayoutEditor.Outcome, LayoutContext> on = applied("tabs", //$NON-NLS-1$
            Map.of("elementName", "Панель1", "value", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(on.getKey().message, on.getKey().done);
        assertEquals(Boolean.TRUE, panelOf(on.getValue()).details.get("tabs")); //$NON-NLS-1$

        LayoutEditor.Outcome noPages = set("tabs", Map.of("elementName", "НадписьНаименование", "value", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse("an element with no pages list takes no tab strip", noPages.done); //$NON-NLS-1$
    }

    /** A font is set from a family with a size and read back, and auto resets it to the form's font. */
    @Test
    public void aFontIsSetAndReset()
    {
        Map.Entry<LayoutEditor.Outcome, LayoutContext> set = applied("font", //$NON-NLS-1$
            Map.of("elementName", "Наименование", "fontFamily", "Verdana", "fontSize", "10", "bold", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(set.getKey().message, set.getKey().done);
        FormLayout.Element field = LayoutReader.read(set.getValue().editor.root(), "Catalog").elementNamed("Наименование"); //$NON-NLS-1$ //$NON-NLS-2$
        @SuppressWarnings("unchecked")
        Map<String, Object> read = (Map<String, Object>)field.details.get("font"); //$NON-NLS-1$
        assertNotNull(read);
        assertEquals("Verdana", read.get("family")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(10), read.get("size")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, read.get("bold")); //$NON-NLS-1$

        Map.Entry<LayoutEditor.Outcome, LayoutContext> auto = applied("font", //$NON-NLS-1$
            Map.of("elementName", "Наименование", "value", "auto")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(auto.getKey().message, auto.getKey().done);
        assertNull(LayoutReader.read(auto.getValue().editor.root(), "Catalog").elementNamed("Наименование") //$NON-NLS-1$ //$NON-NLS-2$
            .details.get("font")); //$NON-NLS-1$

        LayoutEditor.Outcome noElement = set("font", Map.of("value", "auto")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(noElement.done);
        assertTrue(noElement.message, noElement.message.contains("set_property font needs elementName")); //$NON-NLS-1$
    }

    /** An edge is anchored to a neighbour with its offset, and released to none. */
    @Test
    public void anEdgeIsAnchoredAndReleased()
    {
        Map.Entry<LayoutEditor.Outcome, LayoutContext> bound = applied("anchor", Map.of("elementName", //$NON-NLS-1$
            "НадписьНаименование", "edge", "top", "value", "Наименование.bottom", "offset", "2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(bound.getKey().message, bound.getKey().done);
        assertEquals("Наименование.bottom+2", anchorsOf(bound.getValue(), "НадписьНаименование").get("top")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map.Entry<LayoutEditor.Outcome, LayoutContext> released = applied("anchor", //$NON-NLS-1$
            Map.of("elementName", "НадписьНаименование", "edge", "top", "value", "none")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(released.getKey().message, released.getKey().done);
        assertEquals("none", anchorsOf(released.getValue(), "НадписьНаименование").get("top")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        LayoutEditor.Outcome bogus = set("anchor", Map.of("elementName", "НадписьНаименование", "edge", "top", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "value", "Нигде.bottom")); //$NON-NLS-1$
        assertFalse("a target the form does not have takes no anchor", bogus.done); //$NON-NLS-1$
    }

    /** The edit format is not measured: the handler refuses it whatever the arguments. */
    @Test
    public void theEditFormatIsAlwaysRefused()
    {
        LayoutEditor.Outcome refused = set("editFormat", Map.of("elementName", "Наименование", "value", "ДФ=dd")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(refused.done);
        assertTrue(refused.message, refused.message.contains("not measured")); //$NON-NLS-1$
    }

    /**
     * @param context the edit's context
     * @return the panel of the fixture as the reader sees it
     */
    private static FormLayout.Element panelOf(LayoutContext context)
    {
        return LayoutReader.read(context.editor.root(), "Catalog").elementNamed("Панель1"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param context the edit's context
     * @param name the element's name
     * @return the element's anchors as the reader sees them
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> anchorsOf(LayoutContext context, String name)
    {
        FormLayout.Element element = LayoutReader.read(context.editor.root(), "Catalog").elementNamed(name); //$NON-NLS-1$
        assertNotNull(name, element);
        return (Map<String, Object>)element.details.get("anchors"); //$NON-NLS-1$
    }
}
