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

import java.nio.file.Files;
import java.nio.file.Path;
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
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutFactory;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.layout.MainAttributes;

/**
 * A new form's layout is the empty layout the corpus holds: every measured purpose reads back
 * with its main attribute and no element, takes elements, pages and handlers, and the events of
 * the main attribute land in the block of the kind.
 */
public class ANewFormIsTheMeasuredEmptyLayoutTest
{
    private static final String TYPE = "4634bd51-751b-45b1-a081-6373e6f1250b"; //$NON-NLS-1$

    private static final String PROJECT = "AiEdtOformCreateProbe"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    @BeforeClass
    public static void aPlainProject() throws Exception
    {
        root = Files.createTempDirectory("aiedt-oform-create"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src")); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    @AfterClass
    public static void theProjectIsGone() throws Exception
    {
        if (project != null)
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }

    private static final String[] KINDS = { "Catalog", "Document", "ChartOfCharacteristicTypes", "ExchangePlan", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "BusinessProcess", "Task", "DataProcessor", "Report", "InformationRegister", "AccumulationRegister" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

    /** Every measured purpose of every kind reads back as a form with that main attribute and nothing on it. */
    @Test
    public void everyPurposeReadsBack()
    {
        int forms = 0;
        for (String kind : KINDS)
        {
            for (MainAttributes.Purpose purpose : MainAttributes.purposesOf(kind))
            {
                MainAttributes.Spec spec = purpose == MainAttributes.Purpose.NONE ? null : MainAttributes.of(kind, purpose);
                if (purpose != MainAttributes.Purpose.NONE)
                {
                    assertNotNull(kind + " " + purpose, spec); //$NON-NLS-1$
                }
                ListNode root = LayoutFactory.newLayout("Проба " + kind, 500, 350, "ru", spec, TYPE); //$NON-NLS-1$ //$NON-NLS-2$
                String text = BraceTree.serialize(root);
                assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
                assertFalse(text, text.contains("@")); //$NON-NLS-1$
                FormLayout layout = LayoutReader.read(BraceTree.parse(text), kind);
                assertEquals(kind + " " + purpose, 0, layout.unread.size()); //$NON-NLS-1$
                assertEquals("27", layout.version); //$NON-NLS-1$
                assertEquals("Проба " + kind, layout.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(0, layout.elements.size());
                assertEquals(1, layout.pages.size());
                assertEquals("Страница1", layout.pages.get(0).name); //$NON-NLS-1$
                assertEquals(0, layout.formHandlers.size());
                if (spec == null)
                {
                    assertEquals("-1", layout.mainAttributeId); //$NON-NLS-1$
                    assertEquals(0, layout.attributes.size());
                }
                else
                {
                    assertEquals("0", layout.mainAttributeId); //$NON-NLS-1$
                    assertEquals(1, layout.attributes.size());
                    assertEquals(spec.name, layout.attributes.get(0).name);
                    assertTrue(text, text.contains("{\"#\"," + TYPE + "}")); //$NON-NLS-1$ //$NON-NLS-2$
                }
                forms++;
            }
        }
        assertTrue(Integer.toString(forms), forms >= 20);
    }

    /** The purposes are named as the create operation takes them, and the default slots follow the kind. */
    @Test
    public void purposesAreNamed()
    {
        assertEquals(MainAttributes.Purpose.OBJECT, MainAttributes.purposeOf("ItemForm", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(MainAttributes.Purpose.OBJECT, MainAttributes.purposeOf(null, "Document")); //$NON-NLS-1$
        assertEquals(MainAttributes.Purpose.LIST, MainAttributes.purposeOf(null, "InformationRegister")); //$NON-NLS-1$
        assertEquals(MainAttributes.Purpose.NONE, MainAttributes.purposeOf(null, "Constant")); //$NON-NLS-1$
        assertEquals(MainAttributes.Purpose.CHOICE, MainAttributes.purposeOf("choiceform", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(MainAttributes.Purpose.RECORD_SET, MainAttributes.purposeOf("RecordSetForm", "InformationRegister")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(MainAttributes.purposeOf("Sideways", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(MainAttributes.of("Catalog", MainAttributes.Purpose.RECORD)); //$NON-NLS-1$
        assertNull(MainAttributes.of("ChartOfAccounts", MainAttributes.Purpose.OBJECT)); //$NON-NLS-1$
        assertEquals("СправочникОбъект", MainAttributes.of("Catalog", MainAttributes.Purpose.FOLDER).name); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("defaultForm", MainAttributes.defaultSetter("DataProcessor", MainAttributes.Purpose.OBJECT)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("defaultObjectForm", MainAttributes.defaultSetter("Catalog", MainAttributes.Purpose.OBJECT)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("defaultChoiceForm", MainAttributes.defaultSetter("Catalog", MainAttributes.Purpose.CHOICE)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(MainAttributes.defaultSetter("InformationRegister", MainAttributes.Purpose.RECORD_SET)); //$NON-NLS-1$
        assertEquals("Форма элемента", OrdinaryFormCreation.synonymOf("ФормаЭлемента")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Проба2 формы", OrdinaryFormCreation.synonymOf("Проба2Формы")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Форма", OrdinaryFormCreation.synonymOf("Форма")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A new layout takes an element, a page and a handler of the form; the main attribute's events land in the block of the kind. */
    @Test
    public void aNewLayoutIsEdited()
    {
        String[][] cases = { { "Catalog", "ПередЗаписью", "{2,0,{1," }, { "Document", "ПередЗаписью", "{4,{1," }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            { "DataProcessor", "ПередСохранениемЗначений", "{0,0},{1," }, { "InformationRegister", "ПередЗаписью", "{1,{1," } }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        for (String[] one : cases)
        {
            String kind = one[0];
            MainAttributes.Purpose purpose = "InformationRegister".equals(kind) ? MainAttributes.Purpose.RECORD : MainAttributes.Purpose.OBJECT; //$NON-NLS-1$
            ListNode root = LayoutFactory.newLayout("Проба", 500, 350, "ru", MainAttributes.of(kind, purpose), TYPE); //$NON-NLS-1$ //$NON-NLS-2$
            LayoutEditor editor = LayoutEditor.over(root, kind, "ru"); //$NON-NLS-1$
            LayoutEditor.Outcome label = editor.addElement("Label", "Надпись", null, null, new int[] { 10, 10, 100, 29 }, "Проба:", null, null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(label.message, label.done);
            assertEquals("1", label.elementId); //$NON-NLS-1$
            assertTrue(editor.text(), editor.text().contains(",1,0,1,4,4,2},")); //$NON-NLS-1$
            assertTrue(editor.addPage(null, "Вторая", "Вторая").done); //$NON-NLS-1$ //$NON-NLS-2$
            LayoutEditor.Outcome opened = editor.bindFormHandler("ПриОткрытии", "ПриОткрытии"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(opened.message, opened.done);
            LayoutEditor.Outcome main = editor.bindFormHandler(one[1], one[1]);
            assertTrue(kind + ": " + main.message, main.done); //$NON-NLS-1$
            String text = editor.text();
            assertTrue(kind + ": " + text, text.replace("\r\n", "").contains(one[2])); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            FormLayout layout = LayoutReader.read(BraceTree.parse(text), kind);
            assertEquals(0, layout.unread.size());
            assertEquals(1, layout.elements.size());
            assertEquals(2, layout.pages.size());
            assertEquals(2, layout.formHandlers.size());
            assertEquals(List.of(one[1], "ПриОткрытии"), List.of(layout.formHandlers.get(0).event, layout.formHandlers.get(1).event)); //$NON-NLS-1$
            assertTrue(editor.unbindFormHandler(one[1]).done);
            assertEquals(1, LayoutReader.read(BraceTree.parse(editor.text()), kind).formHandlers.size());
        }
    }

    /** The standard actions panel lands on a new form with its three buttons and fresh identifiers. */
    @Test
    public void theStandardActionsPanelIsAdded()
    {
        ListNode root = LayoutFactory.newLayout("Проба", 500, 350, "ru", MainAttributes.of("Catalog", MainAttributes.Purpose.OBJECT), TYPE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        LayoutEditor editor = LayoutEditor.over(root, "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor.Outcome panel = editor.addStandardActions("ОсновныеДействияФормы", new int[] { 0, 325, 500, 350 }); //$NON-NLS-1$
        assertTrue(panel.message, panel.done);
        assertFalse(editor.addStandardActions("ОсновныеДействияФормы", new int[] { 0, 325, 500, 350 }).done); //$NON-NLS-1$
        String text = editor.text();
        assertFalse(text, text.contains("@")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        assertEquals(0, layout.unread.size());
        FormLayout.Element added = layout.elementNamed("ОсновныеДействияФормы"); //$NON-NLS-1$
        assertEquals("CommandPanel", added.type); //$NON-NLS-1$
        assertEquals(5, added.buttons.size());
        assertEquals(List.of("OK", "Записать", "Закрыть"), List.of(added.buttons.get(0).caption.get("ru"), added.buttons.get(2).caption.get("ru"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            added.buttons.get(4).caption.get("ru"))); //$NON-NLS-1$
        String second = LayoutEditor.over(LayoutFactory.newLayout("Проба", 500, 350, "ru", null, null), "Catalog", "ru") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .addStandardActions("Панель", new int[] { 0, 325, 500, 350 }).message; //$NON-NLS-1$
        assertTrue(second, second.contains("added")); //$NON-NLS-1$
    }

    /** The tool refuses what the corpus did not measure before it touches the model. */
    @Test
    public void theToolRefusesTheUnmeasured()
    {
        OrdinaryFormsTool tool = new OrdinaryFormsTool();
        assertTrue(tool.getInputSchema().contains("create_form")); //$NON-NLS-1$
        String answer = tool.execute(Map.of("operation", "create_form", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ownerFqn", "ChartOfAccounts.Main", "name", "ФормаПроба", "purpose", "ItemForm")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        JsonObject parsed = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(answer, parsed.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer, parsed.get("error").getAsString().contains("ChartOfAccounts")); //$NON-NLS-1$ //$NON-NLS-2$
        String sideways = tool.execute(Map.of("operation", "create_form", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ownerFqn", "Catalog.Products", "name", "ФормаПроба", "purpose", "Sideways")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(sideways, JsonParser.parseString(sideways).getAsJsonObject().get("error").getAsString().contains("Unknown purpose")); //$NON-NLS-1$ //$NON-NLS-2$
        String badName = tool.execute(Map.of("operation", "create_form", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ownerFqn", "Catalog.Products", "name", "Форма проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(badName, JsonParser.parseString(badName).getAsJsonObject().get("error").getAsString().contains("identifier")); //$NON-NLS-1$ //$NON-NLS-2$
        String help = tool.execute(Map.of("operation", "help")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(help, help.contains("create_form")); //$NON-NLS-1$
        List<MainAttributes.Purpose> purposes = MainAttributes.purposesOf("InformationRegister"); //$NON-NLS-1$
        assertEquals(List.of(MainAttributes.Purpose.LIST, MainAttributes.Purpose.CHOICE, MainAttributes.Purpose.RECORD,
            MainAttributes.Purpose.RECORD_SET, MainAttributes.Purpose.NONE), purposes);
    }
}
