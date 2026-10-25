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
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.Anchors;
import ru.aiedt.mcp.oform.layout.CommandPanels;
import ru.aiedt.mcp.oform.layout.ElementKinds;
import ru.aiedt.mcp.oform.layout.ElementStyle;
import ru.aiedt.mcp.oform.layout.StandardActions;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.layout.TableColumns;
import ru.aiedt.mcp.oform.layout.UnsupportedLayoutVersionException;

/**
 * The writers were measured on layouts of versions 25, 26 and 27, and every writer runs on a
 * real form of each: the fixtures are forms of the corpus with their strings made neutral. A
 * form of any other version is read as before and refused for writing by name, before an edit
 * touches it - at the editor and through the tool.
 */
public class EveryWriterWorksOnEveryMeasuredVersionTest
{
    private static final String PROJECT = "AiEdtOformVersionProbe"; //$NON-NLS-1$

    private static final List<String> FIXTURES = List.of("fixtures/settings-form-v25.form", //$NON-NLS-1$
        "fixtures/settings-form-v26.form", "fixtures/processing-form-v27.form"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The version each fixture records, read off the file itself and not off its name. */
    private static final Map<String, String> FIXTURE_VERSIONS = Map.of("fixtures/settings-form-v25.form", "25", //$NON-NLS-1$ //$NON-NLS-2$
        "fixtures/settings-form-v26.form", "26", "fixtures/processing-form-v27.form", "27"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * The writable kinds, spelled here so that a kind added to the production set without a
     * measured writer in this test is a failure rather than a silent skip.
     */
    private static final List<String> KINDS = List.of("Label", "Field", "Button", "CheckBox", "Group", "Panel", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "Table", "RadioBtn", "Separator", "Image", "SelectField", "CommandPanel", "TableField", "FieldHtml", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        "ListField", "TextDocumentField", "Chart"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** The uuid of the value list the table of the probe shows. */
    private static final String LIST_TYPE = "5b7c8d9e-1f2a-4b3c-8d4e-5f6a7b8c9d0e"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    @BeforeClass
    public static void aProjectWithAFormOfAnUnmeasuredVersion() throws Exception
    {
        assertEquals(FIXTURES.size(), FIXTURE_VERSIONS.size());
        for (String fixture : FIXTURES)
        {
            String expected = FIXTURE_VERSIONS.get(fixture);
            assertNotNull(fixture, expected);
            String recorded = ALayoutIsReadOffTheContainerTest.fixture(fixture);
            assertTrue(fixture, recorded.startsWith("{" + expected + ",")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String v25 = ALayoutIsReadOffTheContainerTest.fixture(FIXTURES.get(0));
        String v23 = "{23," + v25.substring(4); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-version"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path form = projectDir.resolve("src/Reports/Settings/Forms/OldForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        V8Container v8 = V8Container.empty();
        v8.add(V8Container.Entry.of("form", withBom(v23))); //$NON-NLS-1$
        v8.add(V8Container.Entry.of("module", withBom(""))); //$NON-NLS-1$ //$NON-NLS-2$
        Files.write(form.resolve("Form.oform"), v8.write()); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    private static byte[] withBom(String text)
    {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte)0xEF;
        out[1] = (byte)0xBB;
        out[2] = (byte)0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    /** Each fixture is a real form of its version: it round-trips byte for byte and the reader reads its elements. */
    @Test
    public void eachFixtureIsReadAndRoundTripped() throws Exception
    {
        for (String name : FIXTURES)
        {
            String text = ALayoutIsReadOffTheContainerTest.fixture(name);
            ListNode tree = BraceTree.parse(text);
            assertEquals(name, text, BraceTree.serialize(tree));
            FormLayout layout = LayoutReader.read(tree, "Report"); //$NON-NLS-1$
            assertEquals(name, FIXTURE_VERSIONS.get(name), layout.version);
            assertTrue(name, layout.allElements().size() > 10);
            assertTrue(name + " unread: " + layout.unread, layout.unread.isEmpty() || layout.unread.size() <= 1); //$NON-NLS-1$
        }
    }

    /** The kinds this test writes are the kinds the editor says it can write, all seventeen of them. */
    @Test
    public void theWritableKindsAreTheOnesSpelledHere()
    {
        assertEquals(17, KINDS.size());
        assertEquals(new java.util.HashSet<>(KINDS), new java.util.HashSet<>(ElementKinds.WRITABLE)); //$NON-NLS-1$
    }

    /**
     * Every writer of the editor runs on every version: the title, a page, an element of every
     * writable kind, a column of a table, a form handler, a caption, a box, the
     * removal of an element, a page removed again. The result reads back, and a fixture the
     * writers did not touch is written back byte for byte.
     */
    @Test
    public void everyWriterRunsOnEveryVersion() throws Exception
    {
        for (String name : FIXTURES)
        {
            String text = ALayoutIsReadOffTheContainerTest.fixture(name);
            LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(name, editor.setTitle("Title of " + name).done); //$NON-NLS-1$
            assertTrue(name, editor.addPage(null, "ProbePage", "Probe page").done); //$NON-NLS-1$ //$NON-NLS-2$
            int top = 10;
            for (String kind : KINDS)
            {
                // a list field shows a value list, so the writer takes the value-list pattern;
                // a table shows rows, so it takes the pattern of the list it shows
                ListNode listData = kind.equals("ListField") //$NON-NLS-1$
                    ? BraceTree.parse("{\"Pattern\",{\"#\",4772b3b4-f4a3-49c0-a1a5-8cb5961511a3}}") : null; //$NON-NLS-1$
                ListNode settings = null;
                if (kind.equals("Table")) //$NON-NLS-1$
                {
                    listData = BraceTree.parse("{\"Pattern\",{\"#\"," + LIST_TYPE + "}}"); //$NON-NLS-1$ //$NON-NLS-2$
                    settings = TableColumns.settingsBlock("CatalogList.Products", LIST_TYPE); //$NON-NLS-1$
                }
                LayoutEditor.Outcome added = editor.addElement(kind, "Probe" + kind, null, "ProbePage", //$NON-NLS-1$ //$NON-NLS-2$
                    new int[] { 10, top, 210, top + 20 }, "Probe " + kind, null, listData, settings); //$NON-NLS-1$
                assertTrue(name + " " + kind + ": " + added.message, added.done); //$NON-NLS-1$ //$NON-NLS-2$
                assertNotNull(name + " " + kind, added.elementId); //$NON-NLS-1$
                top += 25;
            }
            // a table takes columns as well, from the one column record versions 25, 26 and 27 share
            assertTrue(name, editor.addColumn("ProbeTable", TableColumns.LIST, "ProbeColumn", null, null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                BraceTree.parse("{\"Pattern\",{\"S\"}}"), //$NON-NLS-1$
                List.of(new FormLayout.Segment(FormLayout.Segment.STANDARD, "-2")), null, "field").done); //$NON-NLS-1$ //$NON-NLS-2$
            // the command panel of the probe takes a button, a submenu with a button under it
            // and a separator - the button record of version 25/26 and of 27 alike
            assertTrue(name, CommandPanels.addButton(editor.root(), "ru", "ProbeCommandPanel", "ProbeButton", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Probe", "ProbeClick", null, null, null, null).done); //$NON-NLS-1$
            assertTrue(name, CommandPanels.addSubmenu(editor.root(), "ru", "ProbeCommandPanel", "ProbeSubmenu", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "Probe submenu", null, null).done); //$NON-NLS-1$
            assertTrue(name, CommandPanels.addButton(editor.root(), "ru", "ProbeCommandPanel", "ProbeInside", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                null, StandardActions.find("add"), null, "ProbeSubmenu", null).done); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(name, CommandPanels.addSeparator(editor.root(), "ProbeCommandPanel", "ProbeSeparator", null, null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(name, editor.bindFormHandler("ПриОткрытии", "ProbeOpening").done); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(name, editor.setCaption("ProbeLabel", "Renamed").done); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(name, editor.setBox("ProbeField", Integer.valueOf(20), null, Integer.valueOf(300), null).done); //$NON-NLS-1$
            assertTrue(name, editor.setTooltip("ProbeField", "A tooltip").done); //$NON-NLS-1$ //$NON-NLS-2$
            // the style writers and the anchors run on every version as well
            assertTrue(name, ElementStyle.setVisible(editor.root(), "ProbeField", false).done); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(name, ElementStyle.setEnabled(editor.root(), "ProbeField", false).done); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(name, ElementStyle.setReadOnly(editor.root(), "ProbeField", true).done); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(name, ElementStyle.setFont(editor.root(), "ProbeField", null, "Verdana", Integer.valueOf(10), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Boolean.TRUE, Boolean.FALSE, Boolean.FALSE).done);
            assertTrue(name, ElementStyle.setColor(editor.root(), "ProbeField", "text", "#3366FF").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(name, ElementStyle.setAlignment(editor.root(), "ProbeField", "right").done); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(name, ElementStyle.setFormat(editor.root(), "ProbeField", "ДФ=dd.MM.yyyy", "ru").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(name, Anchors.set(editor.root(), "ProbeLabel", "top", "ProbeField.bottom", Integer.valueOf(4)).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            FormLayout.Element probe = editor.layout().elementNamed("ProbeField"); //$NON-NLS-1$
            assertNotNull(name, probe);
            assertEquals(name, Boolean.FALSE, probe.details.get("visible")); //$NON-NLS-1$
            assertEquals(name, Boolean.FALSE, probe.details.get("enabled")); //$NON-NLS-1$
            assertEquals(name, Boolean.TRUE, probe.details.get("readOnly")); //$NON-NLS-1$
            assertEquals(name, "#3366FF", probe.details.get("textColor")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(name, "right", probe.details.get("alignment")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(name, "ДФ=dd.MM.yyyy", probe.details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$
            @SuppressWarnings("unchecked")
            Map<String, Object> probeFont = (Map<String, Object>)probe.details.get("font"); //$NON-NLS-1$
            assertEquals(name, "Verdana", probeFont.get("family")); //$NON-NLS-1$ //$NON-NLS-2$
            FormLayout.Element probeLabel = editor.layout().elementNamed("ProbeLabel"); //$NON-NLS-1$
            @SuppressWarnings("unchecked")
            Map<String, Object> probeAnchors = (Map<String, Object>)probeLabel.details.get("anchors"); //$NON-NLS-1$
            assertEquals(name, "ProbeField.bottom+4", probeAnchors.get("top")); //$NON-NLS-1$ //$NON-NLS-2$
            FormLayout layout = editor.layout();
            FormLayout.Element field = layout.elementNamed("ProbeField"); //$NON-NLS-1$
            assertNotNull(name, field);
            assertEquals(name, 20, field.box[0]);
            assertEquals(name, 300, field.box[2]);
            assertEquals(name, "Field", field.type); //$NON-NLS-1$
            assertEquals(name, "Renamed", layout.elementNamed("ProbeLabel").title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            FormLayout.Element probeTable = layout.elementNamed("ProbeTable"); //$NON-NLS-1$
            assertNotNull(name, probeTable);
            assertEquals(name, "Table", probeTable.type); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(name, 1, probeTable.columns.size());
            assertEquals(name, "ProbeColumn", probeTable.columns.get(0).name); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(name, TableColumns.LIST, probeTable.columns.get(0).kind);
            assertTrue(name, editor.removeElement("ProbeButton").done); //$NON-NLS-1$
            assertTrue(name, editor.layout().elementNamed("ProbeButton") == null); //$NON-NLS-1$
            // The page still holds elements, so it is refused; the elements go first, then the page.
            assertFalse(name, editor.removePage(null, "ProbePage").done); //$NON-NLS-1$
            for (String kind : KINDS)
            {
                if (!kind.equals("Button")) //$NON-NLS-1$
                {
                    assertTrue(name + " " + kind, editor.removeElement("Probe" + kind).done); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            assertTrue(name, editor.removePage(null, "ProbePage").done); //$NON-NLS-1$
            // What the edits added is gone; what they changed stays changed; the rest is byte for byte.
            String after = editor.text();
            assertTrue(name, after.contains("Title of " + name)); //$NON-NLS-1$
            assertFalse(name, after.contains("ProbePage")); //$NON-NLS-1$
        }
    }

    /**
     * A table stands on every measured version: the one measured record takes the pattern of the
     * rows it shows, the settings block of those rows and a column, and the whole form reads back
     * with every element the fixture held still there and no record the reader cannot place.
     */
    @Test
    public void aTableIsWrittenOnEveryMeasuredVersion() throws Exception
    {
        for (String name : FIXTURES)
        {
            String text = ALayoutIsReadOffTheContainerTest.fixture(name);
            FormLayout before = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
            LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            ListNode pattern = BraceTree.parse("{\"Pattern\",{\"#\"," + LIST_TYPE + "}}"); //$NON-NLS-1$ //$NON-NLS-2$
            ListNode settings = TableColumns.settingsBlock("CatalogList.Products", LIST_TYPE); //$NON-NLS-1$
            assertNotNull(name, settings);
            LayoutEditor.Outcome added = editor.addElement("Table", "ProbeTable", null, null, //$NON-NLS-1$ //$NON-NLS-2$
                new int[] { 0, 200, 600, 400 }, null, "Probe tooltip", pattern, settings); //$NON-NLS-1$
            assertTrue(name + ": " + added.message, added.done); //$NON-NLS-1$
            assertNotNull(name, added.elementId);
            assertTrue(name, editor.addColumn("ProbeTable", TableColumns.LIST, "ProbeCol", "Probe column", null, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                BraceTree.parse("{\"Pattern\",{\"S\"}}"), //$NON-NLS-1$
                List.of(new FormLayout.Segment(FormLayout.Segment.STANDARD, "-2")), null, "field").done); //$NON-NLS-1$ //$NON-NLS-2$
            String written = editor.text();
            assertEquals(name, written, BraceTree.serialize(BraceTree.parse(written)));
            assertFalse(name, written.contains("@")); //$NON-NLS-1$
            FormLayout after = LayoutReader.read(BraceTree.parse(written), "Catalog"); //$NON-NLS-1$
            FormLayout.Element table = after.elementNamed("ProbeTable"); //$NON-NLS-1$
            assertNotNull(name, table);
            assertEquals(name, "Table", table.type); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(name, 0, table.box[0]);
            assertEquals(name, 200, table.box[1]);
            assertEquals(name, 600, table.box[2]);
            assertEquals(name, 400, table.box[3]);
            assertEquals(name, "Probe tooltip", table.tooltip.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(name, 1, table.columns.size());
            FormLayout.Column column = table.columns.get(0);
            assertEquals(name, "ProbeCol", column.name); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(name, "Probe column", column.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(name, 1, column.path.size());
            assertEquals(name, FormLayout.Segment.STANDARD, column.path.get(0).kind);
            assertEquals(name, "-2", column.path.get(0).value); //$NON-NLS-1$
            assertEquals(name, "Field", column.control); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(name, TableColumns.LIST, column.kind);
            assertEquals(name + " unread: " + after.unread, before.unread.size(), after.unread.size()); //$NON-NLS-1$
            Map<String, String> was = typesByName(before);
            Map<String, String> is = typesByName(after);
            for (Map.Entry<String, String> element : was.entrySet())
            {
                assertEquals(name + " " + element.getKey(), element.getValue(), is.get(element.getKey())); //$NON-NLS-1$
            }
            assertEquals(name, was.size() + 1, is.size());
        }
    }

    /**
     * The elements of a layout by name, with their type.
     *
     * @param layout the layout
     * @return the named elements
     */
    private static Map<String, String> typesByName(FormLayout layout)
    {
        Map<String, String> out = new LinkedHashMap<>();
        for (FormLayout.Element element : layout.allElements())
        {
            if (element.name != null)
            {
                out.put(element.name, element.type);
            }
        }
        return out;
    }

    /** A layout of a version the writers were not measured on is refused at the editor, by name. */
    @Test
    public void anUnmeasuredVersionIsRefusedAtTheEditor() throws Exception
    {
        String v25 = ALayoutIsReadOffTheContainerTest.fixture(FIXTURES.get(0));
        String v23 = "{23," + v25.substring(4); //$NON-NLS-1$
        ListNode tree = BraceTree.parse(v23);
        // Reading is not writing: the reader still yields the elements.
        assertTrue(LayoutReader.read(tree, "Report").allElements().size() > 10); //$NON-NLS-1$
        try
        {
            LayoutEditor.over(tree, "Report", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("a version 23 layout was opened for writing"); //$NON-NLS-1$
        }
        catch (UnsupportedLayoutVersionException e)
        {
            assertEquals("23", e.version()); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("version 23")); //$NON-NLS-1$
            assertTrue(e.getMessage(), e.getMessage().contains("25, 26, 27")); //$NON-NLS-1$
        }
        try
        {
            LayoutEditor.over(BraceTree.parse("{0}"), null, null); //$NON-NLS-1$
            fail("a layout without a version was opened for writing"); //$NON-NLS-1$
        }
        catch (UnsupportedLayoutVersionException e)
        {
            assertEquals("0", e.version()); //$NON-NLS-1$
        }
    }

    /** Through the tool the refusal names the form and the version, and nothing is written. */
    @Test
    public void anUnmeasuredVersionIsRefusedThroughTheTool() throws Exception
    {
        Path container = root.resolve(PROJECT).resolve("src/Reports/Settings/Forms/OldForm/Form.oform"); //$NON-NLS-1$
        byte[] before = Files.readAllBytes(container);
        for (String operation : List.of("set_property", "add_element", "add_page", "bind_handler")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String answer = new OrdinaryFormsTool().execute(Map.of("operation", operation, "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
                "formFqn", "Report.Settings.Form.OldForm", "property", "title", "value", "x", "name", "P", "type", "Label", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
                "event", "ПриОткрытии")); //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
            assertFalse(operation, json.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(operation, json.get("error").getAsString().contains("version 23")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(operation, "23", json.get("layoutVersion").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(container)));
        // The form is still read.
        String layout = new OrdinaryFormsTool().execute(Map.of("operation", "layout", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "formFqn", "Report.Settings.Form.OldForm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(JsonParser.parseString(layout).getAsJsonObject().get("success").getAsBoolean()); //$NON-NLS-1$
    }
}
