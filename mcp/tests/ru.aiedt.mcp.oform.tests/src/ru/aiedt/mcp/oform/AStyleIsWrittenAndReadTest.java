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

import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.layout.ElementStyle;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * The style of an element is written at the position the corpus measured and read back by
 * {@code layout}: visibility, enablement, the read-only flag of a field, the font, the three
 * colours, the alignment and the format. A kind without a measured row and a value outside the
 * measured encodings are refused by name; the edit format is not measured and always refused.
 * What the writes change is the position alone - a value set and set back leaves the text as it
 * was, byte for byte.
 */
public class AStyleIsWrittenAndReadTest
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

    private static FormLayout.Element styled(LayoutEditor editor, String name)
    {
        FormLayout.Element element = editor.layout().elementNamed(name);
        assertNotNull(name, element);
        return element;
    }

    /** Visibility and enablement are one number each; the layout names an element off the default alone. */
    @Test
    public void visibilityAndEnablementAreWrittenAndRead()
    {
        LayoutEditor editor = editor();
        assertTrue(editor.text(), ElementStyle.setVisible(editor.root(), "Наименование", false).done); //$NON-NLS-1$
        assertTrue(ElementStyle.setEnabled(editor.root(), "НадписьНаименование", false).done); //$NON-NLS-1$
        String text = editor.text();
        assertEquals(text, text, BraceTree.serialize(BraceTree.parse(text)));
        FormLayout.Element field = styled(editor, "Наименование"); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, field.details.get("visible")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, styled(editor, "НадписьНаименование").details.get("enabled")); //$NON-NLS-1$
        assertNull(field.details.get("enabled")); //$NON-NLS-1$
        assertNull(styled(editor, "НадписьНаименование").details.get("visible")); //$NON-NLS-1$
        // the flag itself sits at position 5 of the head of the common list
        assertTrue(text, text.contains("8,110,20,400,39,0,")); //$NON-NLS-1$
        // a value set and set back leaves the text byte for byte
        assertTrue(ElementStyle.setVisible(editor.root(), "Наименование", true).done); //$NON-NLS-1$
        assertTrue(ElementStyle.setEnabled(editor.root(), "НадписьНаименование", true).done); //$NON-NLS-1$
        assertEquals(layoutText, editor.text());
        assertFalse(ElementStyle.setVisible(editor.root(), "Нигде", false).done); //$NON-NLS-1$
    }

    /** The read-only flag is measured on a field alone; every other kind is refused by name. */
    @Test
    public void readOnlyIsAFieldAlone()
    {
        LayoutEditor editor = editor();
        assertTrue(ElementStyle.setReadOnly(editor.root(), "Наименование", true).done); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, styled(editor, "Наименование").details.get("readOnly")); //$NON-NLS-1$
        LayoutEditor.Outcome refused = ElementStyle.setReadOnly(editor.root(), "НадписьНаименование", true); //$NON-NLS-1$
        assertFalse(refused.done);
        assertTrue(refused.message, refused.message.contains("Label")); //$NON-NLS-1$
        assertTrue(refused.message, refused.message.contains("Field")); //$NON-NLS-1$
        assertTrue(ElementStyle.setReadOnly(editor.root(), "Наименование", false).done); //$NON-NLS-1$
        assertNull(styled(editor, "Наименование").details.get("readOnly")); //$NON-NLS-1$
        assertEquals(layoutText, editor.text());
    }

    /** Two font shapes are written: the plain auto font, and a family with a size and the flags. */
    @Test
    public void aFontIsAutoOrAFamilyWithASize()
    {
        LayoutEditor editor = editor();
        // the fixture's label carries a style-element font; auto resets it to the measured shape
        assertTrue(layoutText, layoutText.contains("{7,2,0,\r\n{-20},0,100}")); //$NON-NLS-1$
        assertTrue(ElementStyle.setFont(editor.root(), "НадписьНаименование", "auto", null, null, null, null, null).done); //$NON-NLS-1$
        String text = editor.text();
        assertTrue(text, text.contains("{6,3,0,1}")); //$NON-NLS-1$
        assertNull(styled(editor, "НадписьНаименование").details.get("font")); //$NON-NLS-1$
        LayoutEditor.Outcome font = ElementStyle.setFont(editor.root(), "Наименование", null, "Verdana", Integer.valueOf(10), //$NON-NLS-1$ //$NON-NLS-2$
            Boolean.TRUE, Boolean.FALSE, Boolean.TRUE);
        assertTrue(font.message, font.done);
        text = editor.text();
        // the absolute font of the corpus: the size in tenths, the weight, the flags, the charset
        assertTrue(text, text.contains("{6,0,0,100,0,0,0,700,0,1,0,204,3,2,1,34,\"Verdana\",0}")); //$NON-NLS-1$
        FormLayout.Element field = styled(editor, "Наименование"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> read = (Map<String, Object>)field.details.get("font"); //$NON-NLS-1$
        assertEquals("Verdana", read.get("family")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(10), read.get("size")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, read.get("bold")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, read.get("underline")); //$NON-NLS-1$
        assertTrue(ElementStyle.setFont(editor.root(), "Наименование", "auto", null, null, null, null, null).done); //$NON-NLS-1$
        assertNull(styled(editor, "Наименование").details.get("font")); //$NON-NLS-1$
    }

    /** A flag without a family and a size, and value=auto beside them, are refused. */
    @Test
    public void aFontOutsideTheMeasuredShapesIsRefused()
    {
        LayoutEditor editor = editor();
        LayoutEditor.Outcome bold = ElementStyle.setFont(editor.root(), "Наименование", null, null, null, //$NON-NLS-1$
            Boolean.TRUE, null, null);
        assertFalse(bold.done);
        assertTrue(bold.message, bold.message.contains("family")); //$NON-NLS-1$
        LayoutEditor.Outcome size = ElementStyle.setFont(editor.root(), "Наименование", null, null, Integer.valueOf(10), //$NON-NLS-1$
            null, null, null);
        assertFalse(size.done);
        LayoutEditor.Outcome mixed = ElementStyle.setFont(editor.root(), "Наименование", "auto", "Verdana", //$NON-NLS-1$ //$NON-NLS-2$
            Integer.valueOf(10), null, null, null);
        assertFalse(mixed.done);
        assertTrue(mixed.message, mixed.message.contains("stands alone")); //$NON-NLS-1$
    }

    /**
     * The font's value names the auto font and nothing else: a family put there, or a typo, is a
     * refusal rather than a silent reset to the form's font.
     */
    @Test
    public void theFontValueTakesAutoAndNothingElse()
    {
        LayoutEditor editor = editor();
        LayoutEditor.Outcome family = ElementStyle.setFont(editor.root(), "Наименование", "Arial", null, null, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, null);
        assertFalse(family.done);
        assertTrue(family.message, family.message.contains("auto and nothing else")); //$NON-NLS-1$
        LayoutEditor.Outcome typo = ElementStyle.setFont(editor.root(), "Наименование", "atuo", null, null, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, null);
        assertFalse(typo.done);
        LayoutEditor.Outcome upper = ElementStyle.setFont(editor.root(), "Наименование", "AUTO", null, null, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, null);
        assertTrue(upper.message, upper.done);
    }

    /** A colour is auto, an absolute #RRGGBB, or one of the measured style element indexes. */
    @Test
    public void coloursAreAutoAbsoluteOrAStyleElement()
    {
        LayoutEditor editor = editor();
        // #3366FF is the integer 16737843 the corpus carries: blue high, red low
        assertTrue(ElementStyle.setColor(editor.root(), "Наименование", "text", "#3366FF").done); //$NON-NLS-1$ //$NON-NLS-2$
        String text = editor.text();
        assertTrue(text, text.contains("{3,0,\r\n{16737843}\r\n}")); //$NON-NLS-1$
        assertEquals("#3366FF", styled(editor, "Наименование").details.get("textColor")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(ElementStyle.setColor(editor.root(), "Наименование", "back", "-7").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(text, text.contains("{3,3,\r\n{-7}\r\n}")); //$NON-NLS-1$
        assertEquals("-7", styled(editor, "Наименование").details.get("backColor")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(ElementStyle.setColor(editor.root(), "НадписьНаименование", "border", "-22").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(ElementStyle.setColor(editor.root(), "Наименование", "text", "-3").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ElementStyle.setColor(editor.root(), "Наименование", "back", "auto").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(layoutText, editor.text());
        // the fixture's field carries the style element -3, which is not the auto colour
        assertEquals("-3", styled(editor, "Наименование").details.get("textColor")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        LayoutEditor.Outcome badHex = ElementStyle.setColor(editor.root(), "Наименование", "text", "#FF66"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(badHex.done);
        LayoutEditor.Outcome badIndex = ElementStyle.setColor(editor.root(), "Наименование", "text", "-99"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(badIndex.done);
        assertTrue(badIndex.message, badIndex.message.contains("-22")); //$NON-NLS-1$
        LayoutEditor.Outcome badSlot = ElementStyle.setColor(editor.root(), "Наименование", "middle", "auto"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(badSlot.done);
    }

    /** A label and a button align left, centre, right and auto; a field has no centre. */
    @Test
    public void alignmentIsMeasuredOnLabelButtonAndField()
    {
        LayoutEditor editor = editor();
        assertTrue(ElementStyle.setAlignment(editor.root(), "НадписьНаименование", "center").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("center", styled(editor, "НадписьНаименование").details.get("alignment")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(ElementStyle.setAlignment(editor.root(), "Наименование", "right").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("right", styled(editor, "Наименование").details.get("alignment")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(ElementStyle.setAlignment(editor.root(), "НадписьНаименование", "auto").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(styled(editor, "НадписьНаименование").details.get("alignment")); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor.Outcome centreField = ElementStyle.setAlignment(editor.root(), "Наименование", "center"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(centreField.done);
        assertTrue(centreField.message, centreField.message.contains("centre")); //$NON-NLS-1$
        LayoutEditor.Outcome checkBox = ElementStyle.setAlignment(editor.root(), "ФлагАктивен", "left"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(checkBox.done);
        assertTrue(checkBox.message, checkBox.message.contains("CheckBox")); //$NON-NLS-1$
        LayoutEditor.Outcome bad = ElementStyle.setAlignment(editor.root(), "НадписьНаименование", "up"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(bad.done);
        // a value set and set back leaves the text byte for byte: both originals are left
        assertTrue(ElementStyle.setAlignment(editor.root(), "Наименование", "left").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(ElementStyle.setAlignment(editor.root(), "НадписьНаименование", "left").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(layoutText, editor.text());
    }

    /** The format of a field is a localized string; an empty value clears it; a label has none. */
    @Test
    public void aFieldCarriesAFormat()
    {
        LayoutEditor editor = editor();
        assertTrue(ElementStyle.setFormat(editor.root(), "Наименование", "ДФ=dd.MM.yyyy", "ru").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ДФ=dd.MM.yyyy", styled(editor, "Наименование").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String text = editor.text();
        assertTrue(text, text.contains("{\"ru\",\"ДФ=dd.MM.yyyy\"}")); //$NON-NLS-1$
        assertTrue(ElementStyle.setFormat(editor.root(), "Наименование", "", "ru").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(styled(editor, "Наименование").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(layoutText, editor.text());
        LayoutEditor.Outcome label = ElementStyle.setFormat(editor.root(), "НадписьНаименование", "ДФ=dd", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(label.done);
        assertTrue(label.message, label.message.contains("Label")); //$NON-NLS-1$
    }

    /** What the layout read before the writes it reads after them: the fixture's own style. */
    @Test
    public void theFixtureStyleIsRead()
    {
        FormLayout layout = LayoutReader.read(BraceTree.parse(layoutText), "Catalog"); //$NON-NLS-1$
        FormLayout.Element label = layout.elementNamed("НадписьНаименование"); //$NON-NLS-1$
        // the fixture's label carries a style-element text colour the platform reads as -3
        assertEquals("-3", label.details.get("textColor")); //$NON-NLS-1$ //$NON-NLS-2$
        @SuppressWarnings("unchecked")
        Map<String, Object> font = (Map<String, Object>)label.details.get("font"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(-20), font.get("styleElement")); //$NON-NLS-1$
        assertNull(label.details.get("visible")); //$NON-NLS-1$
        assertNull(label.details.get("enabled")); //$NON-NLS-1$
        // the fixture's label sits at the left, which is not the default the reader hides
        assertEquals("left", label.details.get("alignment")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Through the tool the properties are set and refused by name, and dryRun writes nothing. */
    @Test
    public void theToolSetsAndRefusesStyle() throws Exception
    {
        String project = "AiEdtOformStyleProbe"; //$NON-NLS-1$
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("aiedt-oform-style"); //$NON-NLS-1$
        java.nio.file.Path form = root.resolve(project).resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        java.nio.file.Files.createDirectories(form);
        V8Container v8 = V8Container.empty();
        v8.add(V8Container.Entry.of("form", withBom(layoutText))); //$NON-NLS-1$
        v8.add(V8Container.Entry.of("module", withBom(""))); //$NON-NLS-1$ //$NON-NLS-2$
        java.nio.file.Path container = form.resolve("Form.oform"); //$NON-NLS-1$
        java.nio.file.Files.write(container, v8.write());
        org.eclipse.core.resources.IWorkspace workspace = org.eclipse.core.resources.ResourcesPlugin.getWorkspace();
        org.eclipse.core.resources.IProject probe = workspace.getRoot().getProject(project);
        org.eclipse.core.resources.IProjectDescription description = workspace.newProjectDescription(project);
        description.setLocation(new org.eclipse.core.runtime.Path(root.resolve(project).toString()));
        probe.create(description, new org.eclipse.core.runtime.NullProgressMonitor());
        probe.open(new org.eclipse.core.runtime.NullProgressMonitor());
        try
        {
            OrdinaryFormsTool tool = new OrdinaryFormsTool();
            byte[] before = java.nio.file.Files.readAllBytes(container);
            String dry = tool.execute(Map.of("operation", "set_property", "projectName", project, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "formFqn", "Catalog.Products.Form.ItemForm", "property", "visible", "elementName", "Наименование", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "value", "false", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            com.google.gson.JsonObject dryAnswer = com.google.gson.JsonParser.parseString(dry).getAsJsonObject();
            assertTrue(dry, dryAnswer.get("success").getAsBoolean()); //$NON-NLS-1$
            assertFalse(dryAnswer.get("written").getAsBoolean()); //$NON-NLS-1$
            assertTrue(java.util.Arrays.equals(before, java.nio.file.Files.readAllBytes(container)));
            String set = tool.execute(Map.of("operation", "set_property", "projectName", project, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "formFqn", "Catalog.Products.Form.ItemForm", "property", "visible", "elementName", "Наименование", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "value", "false")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(set, com.google.gson.JsonParser.parseString(set).getAsJsonObject().get("success").getAsBoolean()); //$NON-NLS-1$
            String layout = tool.execute(Map.of("operation", "layout", "projectName", project, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "formFqn", "Catalog.Products.Form.ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(layout, layout.contains("\"visible\":false")); //$NON-NLS-1$
            String refused = tool.execute(Map.of("operation", "set_property", "projectName", project, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "formFqn", "Catalog.Products.Form.ItemForm", "property", "readOnly", "elementName", "НадписьНаименование", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "value", "true")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(refused, refused.contains("Label")); //$NON-NLS-1$
            // the edit format is not measured and always refused
            String editFormat = tool.execute(Map.of("operation", "set_property", "projectName", project, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "formFqn", "Catalog.Products.Form.ItemForm", "property", "editFormat", "elementName", "Наименование", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "value", "ДФ=dd.MM.yyyy")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(editFormat, editFormat.contains("not measured")); //$NON-NLS-1$
            String unknown = tool.execute(Map.of("operation", "set_property", "projectName", project, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "formFqn", "Catalog.Products.Form.ItemForm", "property", "glow", "elementName", "Наименование", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "value", "true")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(unknown, unknown.contains("Unknown property")); //$NON-NLS-1$
            assertTrue(unknown, unknown.contains("glow")); //$NON-NLS-1$
        }
        finally
        {
            probe.delete(false, true, new org.eclipse.core.runtime.NullProgressMonitor());
            try (var walk = java.nio.file.Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(java.nio.file.Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    private static byte[] withBom(String text)
    {
        byte[] body = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte)0xEF;
        out[1] = (byte)0xBB;
        out[2] = (byte)0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }
}
