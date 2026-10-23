/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.ElementKinds;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutNames;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.layout.TableColumns;

/**
 * A record of every writable kind is created by the editor on a real form of each measured
 * version - 25, 26 and 27 - and what the write left is serialized, parsed again and read: the
 * new element answers by its own unique name with exactly the kind asked for, its name, its
 * full box and the page it landed on. An element the fixture already holds of the same kind
 * proves nothing here, so every probe name is new; a label inside a panel of its own is read
 * under that panel on the page it was born with. What the additions were not obliged to touch
 * is checked to be untouched: the registry of the data bindings, the records, boxes and pages
 * of the elements the fixture held, the form's handlers, its title, its pages and the records
 * the reader cannot place. The counts are kept: seventeen kinds on three versions make
 * fifty-one creations and fifty-one readbacks, and both counters are asserted.
 */
public class ACreatedKindIsReadBackOnEveryMeasuredVersionTest
{
    /** A real form of each measured version, as the earlier version cycle reads them. */
    private static final List<String> FIXTURES = List.of("fixtures/settings-form-v25.form", //$NON-NLS-1$
        "fixtures/settings-form-v26.form", "fixtures/processing-form-v27.form"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The version each fixture records, asserted off the text itself and not off the name. */
    private static final Map<String, String> VERSIONS = Map.of("fixtures/settings-form-v25.form", "25", //$NON-NLS-1$ //$NON-NLS-2$
        "fixtures/settings-form-v26.form", "26", //$NON-NLS-1$ //$NON-NLS-2$
        "fixtures/processing-form-v27.form", "27"); //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * The seventeen kinds, spelled here so that a kind the editor can no longer create, or a new
     * one without this readback, is a failure rather than a silent skip.
     */
    private static final List<String> KINDS = List.of("Label", "Field", "Button", "CheckBox", "Group", "Panel", "Table", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "RadioBtn", "Separator", "Image", "SelectField", "CommandPanel", "TableField", "FieldHtml", "ListField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "TextDocumentField", "Chart"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The uuid of the value list the table probe shows; the measured settings block comes with it. */
    private static final String LIST_TYPE = "5b7c8d9e-1f2a-4b3c-8d4e-5f6a7b8c9d0e"; //$NON-NLS-1$

    /** The pattern of the value list every list field of the corpus is bound to. */
    private static final String VALUE_LIST = "{\"Pattern\",{\"#\",4772b3b4-f4a3-49c0-a1a5-8cb5961511a3}}"; //$NON-NLS-1$

    /** The fixture texts, read once; every edit works on its own parse of its own copy. */
    private static final Map<String, String> TEXTS = new LinkedHashMap<>();

    /** Reads the fixtures once and asserts the version each records. */
    @BeforeClass
    public static void theFixtures() throws IOException
    {
        for (String fixture : FIXTURES)
        {
            String text = ALayoutIsReadOffTheContainerTest.fixture(fixture);
            assertTrue(fixture, text.startsWith("{" + VERSIONS.get(fixture) + ",")); //$NON-NLS-1$ //$NON-NLS-2$
            TEXTS.put(fixture, text);
        }
    }

    /** The seventeen kinds are spelled here once each and are the kinds the editor declares writable. */
    @Test
    public void theSeventeenKindsAreSpelledHereOnceEach()
    {
        assertEquals(17, KINDS.size());
        assertEquals(17, new HashSet<>(KINDS).size());
        assertEquals(new HashSet<>(KINDS), ElementKinds.WRITABLE);
    }

    /**
     * On a form of every measured version every writable kind is created as its own new record,
     * the written text is parsed again and read, and each new element answers by its own name
     * with its kind, its name, its full box and the page it stands on; a label inside a new
     * panel answers under that panel. The fixture's own registry, elements, handlers, title,
     * pages and unread records are what they were, and the counts of creations and readbacks
     * both close at fifty-one.
     */
    @Test
    public void everyKindIsCreatedAndReadBackOnEveryMeasuredVersion()
    {
        int created = 0;
        int readBack = 0;
        for (String fixture : FIXTURES)
        {
            String label = fixture + " v" + VERSIONS.get(fixture); //$NON-NLS-1$
            FormLayout before = LayoutReader.read(BraceTree.parse(TEXTS.get(fixture)), "Report"); //$NON-NLS-1$
            before.resolve(LayoutNames.NONE);
            LayoutEditor editor = LayoutEditor.over(BraceTree.parse(TEXTS.get(fixture)), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            for (int i = 0; i < KINDS.size(); i++)
            {
                String kind = KINDS.get(i);
                LayoutEditor.Outcome added = addKind(editor, kind, "Kind" + kind, //$NON-NLS-1$
                    new int[] { 10 + i * 40, 600, 40 + i * 40, 640 });
                assertTrue(label + " " + kind + ": " + added.message, added.done); //$NON-NLS-1$ //$NON-NLS-2$
                assertNotNull(label + " " + kind, added.elementId); //$NON-NLS-1$
                created++;
            }
            // the parent route: a panel of its own holds a nested label on the page it is born with
            assertTrue(label, editor.addElement("Panel", "KindHolderPanel", null, null, //$NON-NLS-1$ //$NON-NLS-2$
                new int[] { 700, 10, 900, 300 }, null, null, null).done);
            assertTrue(label, editor.addElement("Label", "KindNestedLabel", "KindHolderPanel", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                LayoutEditor.BORN_PAGE_NAME, new int[] { 5, 5, 120, 24 }, "Nested", null, null).done); //$NON-NLS-1$
            String written = editor.text();
            assertEquals(label, written, BraceTree.serialize(BraceTree.parse(written)));
            assertTrue(label, written.indexOf('@') < 0);
            FormLayout after = LayoutReader.read(BraceTree.parse(written), "Report"); //$NON-NLS-1$
            after.resolve(LayoutNames.NONE);
            assertEquals(label, VERSIONS.get(fixture), after.version);
            String firstPage = before.pages.get(0).name;
            for (int i = 0; i < KINDS.size(); i++)
            {
                String kind = KINDS.get(i);
                FormLayout.Element element = after.elementNamed("Kind" + kind); //$NON-NLS-1$
                assertNotNull(label + " " + kind, element); //$NON-NLS-1$
                assertEquals(label + " " + kind, kind, element.type); //$NON-NLS-1$
                assertEquals(label + " " + kind, "Kind" + kind, element.name); //$NON-NLS-1$ //$NON-NLS-2$
                assertArrayEquals(label + " " + kind, new int[] { 10 + i * 40, 600, 40 + i * 40, 640 }, element.box); //$NON-NLS-1$
                assertEquals(label + " " + kind, Integer.valueOf(0), element.pageIndex); //$NON-NLS-1$
                assertEquals(label + " " + kind, firstPage, element.page); //$NON-NLS-1$
                readBack++;
            }
            FormLayout.Element holder = after.elementNamed("KindHolderPanel"); //$NON-NLS-1$
            assertNotNull(label, holder);
            assertEquals(label, "Panel", holder.type); //$NON-NLS-1$
            assertEquals(label, 1, holder.children.size());
            FormLayout.Element nested = holder.children.get(0);
            assertEquals(label, "KindNestedLabel", nested.name); //$NON-NLS-1$
            assertEquals(label, "Label", nested.type); //$NON-NLS-1$
            assertEquals(label, LayoutEditor.BORN_PAGE_NAME, nested.page);
            assertEquals(label, LayoutEditor.BORN_PAGE_NAME, holder.pages.get(0).name);
            // what the additions were not obliged to touch is untouched
            assertEquals(label, before.allElements().size() + KINDS.size() + 2, after.allElements().size());
            assertEquals(label + " registry", renderedPaths(before), renderedPaths(after)); //$NON-NLS-1$
            assertEquals(label + " bindings", before.bindings, after.bindings); //$NON-NLS-1$
            for (FormLayout.Element was : before.allElements())
            {
                if (was.name == null)
                {
                    continue;
                }
                FormLayout.Element is = after.elementNamed(was.name);
                assertNotNull(label + " " + was.name, is); //$NON-NLS-1$
                assertEquals(label + " " + was.name, was.type, is.type); //$NON-NLS-1$
                assertArrayEquals(label + " " + was.name, was.box, is.box); //$NON-NLS-1$
                assertEquals(label + " " + was.name, was.page, is.page); //$NON-NLS-1$
                assertEquals(label + " " + was.name, was.data, is.data); //$NON-NLS-1$
            }
            assertEquals(label + " title", before.title, after.title); //$NON-NLS-1$
            assertEquals(label + " unread", before.unread, after.unread); //$NON-NLS-1$
            assertEquals(label + " handlers", procedures(before), procedures(after)); //$NON-NLS-1$
            assertEquals(label + " pages", pageNames(before.pages), pageNames(after.pages)); //$NON-NLS-1$
        }
        assertEquals(51, created);
        assertEquals(51, readBack);
    }

    /**
     * Adds an element of a kind over the routes the measured templates take: a list field is
     * given the value list pattern it requires, a table takes the pattern of the list it shows
     * together with the settings block of those rows.
     *
     * @param editor the editor over a fresh parse of a fixture
     * @param kind the kind to create
     * @param name the name of the new element, unique on the form
     * @param box left, top, right, bottom
     * @return the outcome of the addition
     */
    private static LayoutEditor.Outcome addKind(LayoutEditor editor, String kind, String name, int[] box)
    {
        ListNode data = null;
        ListNode settings = null;
        if ("ListField".equals(kind)) //$NON-NLS-1$
        {
            data = BraceTree.parse(VALUE_LIST);
        }
        else if ("Table".equals(kind)) //$NON-NLS-1$
        {
            data = BraceTree.parse("{\"Pattern\",{\"#\"," + LIST_TYPE + "}}"); //$NON-NLS-1$ //$NON-NLS-2$
            settings = TableColumns.settingsBlock("CatalogList.Products", LIST_TYPE); //$NON-NLS-1$
        }
        return editor.addElement(kind, name, null, null, box, null, null, data, settings);
    }

    /**
     * The registry of the data bindings with every path rendered as {@code kind:value} pairs, so
     * two layouts compare without relying on the identity of segment objects.
     *
     * @param layout the layout
     * @return element id to its rendered path
     */
    private static Map<String, String> renderedPaths(FormLayout layout)
    {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<FormLayout.Segment>> path : layout.paths.entrySet())
        {
            StringBuilder text = new StringBuilder();
            for (FormLayout.Segment segment : path.getValue())
            {
                text.append(segment.kind).append(':').append(segment.value).append(','); //$NON-NLS-1$
            }
            out.put(path.getKey(), text.toString());
        }
        return out;
    }

    /**
     * The procedures the form's handlers are bound to, in their order.
     *
     * @param layout the layout
     * @return the procedures
     */
    private static List<String> procedures(FormLayout layout)
    {
        List<String> out = new ArrayList<>();
        for (FormLayout.Handler handler : layout.formHandlers)
        {
            out.add(handler.procedure);
        }
        return out;
    }

    /**
     * The names of the pages, in their order.
     *
     * @param pages the pages
     * @return the names
     */
    private static List<String> pageNames(List<FormLayout.Page> pages)
    {
        List<String> out = new ArrayList<>();
        for (FormLayout.Page page : pages)
        {
            out.add(page.name);
        }
        return out;
    }
}
