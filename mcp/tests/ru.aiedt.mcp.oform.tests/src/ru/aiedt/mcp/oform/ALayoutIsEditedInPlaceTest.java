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

import java.nio.charset.StandardCharsets;
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

import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.HandlerSignatures;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * A layout is edited in place: a caption, a tooltip, a box and the form's title are replaced
 * where the corpus keeps them; a handler is bound as the platform records it, with its event
 * code, and unbound; an element is added from the template of its type, on the page named,
 * with its own size in its anchors, and the data it shows recorded in the registry; a page is
 * added beside the existing ones; an element is removed with its data binding and every
 * anchor that named it. What is not edited is written back byte for byte, and the reader reads
 * every edit back. The writing operations of {@code ordinary_forms} answer the outcome, honour
 * {@code dryRun}, add a procedure stub with the measured parameters, and refuse what they
 * cannot do by name.
 */
public class ALayoutIsEditedInPlaceTest
{
    private static final String PROJECT = "AiEdtOformEditProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String ARTICLE_UUID = "7a1f0c2e-5b6d-4e8f-9a0b-1c2d3e4f5a6b"; //$NON-NLS-1$

    private static String layoutText;

    private static Path root;

    private static IProject project;

    private static Path container;

    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-edit"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path form = projectDir.resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        V8Container v8 = V8Container.empty();
        v8.add(V8Container.Entry.of("form", withBom(layoutText))); //$NON-NLS-1$
        v8.add(V8Container.Entry.of("module", withBom("Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        container = form.resolve("Form.oform"); //$NON-NLS-1$
        Files.write(container, v8.write());
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

    private static LayoutEditor editor()
    {
        return LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Only the records an edit names change; everything else is written back byte for byte. */
    @Test
    public void anEditChangesOnlyWhatItNames()
    {
        LayoutEditor editor = editor();
        assertTrue(editor.setCaption("НадписьНаименование", "Название:").done); //$NON-NLS-1$ //$NON-NLS-2$
        String after = editor.text();
        assertFalse(after.equals(layoutText));
        // The change is the caption record alone: the texts agree before and after it.
        int at = layoutText.indexOf("\"Наименование:\""); //$NON-NLS-1$
        assertTrue(at > 0);
        int node = layoutText.lastIndexOf("{1,2,", at); //$NON-NLS-1$
        assertEquals(layoutText.substring(0, node), after.substring(0, node));
        String tailBefore = layoutText.substring(layoutText.indexOf("\"Наименование:\"") + "\"Наименование:\"".length()); //$NON-NLS-1$ //$NON-NLS-2$
        String tailAfter = after.substring(after.indexOf("\"Название:\"") + "\"Название:\"".length()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(tailBefore, tailAfter);
        FormLayout layout = LayoutReader.read(BraceTree.parse(after), "Catalog"); //$NON-NLS-1$
        assertEquals("Название:", layout.elements.get(0).title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, layout.elements.get(0).title.size());
    }

    /** Captions, tooltips, boxes and the title are set where each type keeps them. */
    @Test
    public void propertiesAreSet()
    {
        LayoutEditor editor = editor();
        assertFalse(editor.setCaption("Наименование", "x").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.setCaption("ФлагАктивен", "Активна").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.setTooltip("Наименование", "Полное имя").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.setTooltip("НадписьНаименование", "").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.setBox("Наименование", null, null, Integer.valueOf(500), Integer.valueOf(45)).done); //$NON-NLS-1$
        assertFalse(editor.setBox("Наименование", Integer.valueOf(600), null, null, null).done); //$NON-NLS-1$
        assertTrue(editor.setTitle("Товар").done); //$NON-NLS-1$
        assertFalse(editor.setCaption("Нигде", "x").done); //$NON-NLS-1$ //$NON-NLS-2$

        FormLayout layout = LayoutReader.read(BraceTree.parse(editor.text()), "Catalog"); //$NON-NLS-1$
        assertEquals("Товар", layout.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout.Element field = layout.elementNamed("Наименование"); //$NON-NLS-1$
        assertEquals("Полное имя", field.tooltip.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of(110, 20, 500, 45), List.of(field.box[0], field.box[1], field.box[2], field.box[3]));
        assertEquals("Активна", layout.elementNamed("ФлагАктивен").title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(layout.elementNamed("НадписьНаименование").tooltip); //$NON-NLS-1$
        // The anchor naming the field's own height follows the box; the right edge, anchored to
        // another element, keeps its anchor and the answer says so.
        String text = editor.text();
        assertTrue(text, text.contains("{2,12,0,25}")); //$NON-NLS-1$
        assertTrue(text, text.contains("{2,213,3,384}")); //$NON-NLS-1$
        assertTrue(editor.setBox("Наименование", null, null, Integer.valueOf(520), null).message.contains("right stays anchored")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A handler is bound with the event's code and read back; rebinding replaces; unbinding removes. */
    @Test
    public void handlersAreBoundAndUnbound()
    {
        LayoutEditor editor = editor();
        assertTrue(editor.bindHandler("Артикул", "ПриИзменении", "АртикулПриИзменении").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(editor.bindHandler("Наименование", "Открытие", "НаименованиеОткрытие2").message.contains("rebound")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(editor.bindHandler("НадписьНаименование", "ПриИзменении", "x").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(editor.bindFormHandler("ПередОткрытием", "ПередОткрытием").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.bindFormHandler("ПослеЗаписи", "ПослеЗаписи").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.unbindHandler("Наименование", "ПриИзменении").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(editor.unbindHandler("Наименование", "ПриИзменении").done); //$NON-NLS-1$ //$NON-NLS-2$

        String text = editor.text();
        assertTrue(text, text.contains("{2147483647,e1692cc2-605b-4535-84dd-28440238746c," + CRLF + "{3,\"АртикулПриИзменении\"," + CRLF + "{1,\"АртикулПриИзменении\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(text, text.contains("{\"ru\",\"Артикул при изменении\"}")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        FormLayout.Element article = layout.elementNamed("Артикул"); //$NON-NLS-1$
        assertEquals(1, article.handlers.size());
        assertEquals("ПриИзменении", article.handlers.get(0).event); //$NON-NLS-1$
        FormLayout.Element name = layout.elementNamed("Наименование"); //$NON-NLS-1$
        assertEquals(1, name.handlers.size());
        assertEquals("НаименованиеОткрытие2", name.handlers.get(0).procedure); //$NON-NLS-1$
        assertEquals(4, layout.formHandlers.size());
        assertEquals("ПослеЗаписи", layout.formHandlers.get(1).event); //$NON-NLS-1$
        assertEquals(Integer.valueOf(80002), layout.formHandlers.get(1).code);
        assertEquals("ПередОткрытием", layout.formHandlers.get(3).event); //$NON-NLS-1$
        assertEquals("Наименование при изменении", LayoutEditor.presentation("НаименованиеПриИзменении")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Элемент, СтандартнаяОбработка", HandlerSignatures.elementParameters("Field", "НачалоВыбора")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Отказ, РежимЗаписи, РежимПроведения", HandlerSignatures.formParameters("ПередЗаписью", false, true)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", HandlerSignatures.formParameters("ПриОткрытии", false, false)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An element comes from its template, lands on the page named, and its data path is recorded. */
    @Test
    public void elementsAreAddedAndRemoved()
    {
        LayoutEditor editor = editor();
        LayoutEditor.Outcome label = editor.addElement("Label", "НадписьВес", null, "Дополнительно", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new int[] { 10, 100, 90, 119 }, "Вес:", null, null); //$NON-NLS-1$
        assertTrue(label.message, label.done);
        assertEquals("20", label.elementId); //$NON-NLS-1$
        LayoutEditor.Outcome field = editor.addElement("Field", "Вес", null, "Дополнительно", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new int[] { 100, 100, 200, 119 }, null, "Вес, кг", BraceTree.parse("{\"Pattern\",{\"N\",10,3,1}}")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(field.message, field.done);
        assertEquals("21", field.elementId); //$NON-NLS-1$
        assertTrue(editor.bindData("21", List.of(new FormLayout.Segment(FormLayout.Segment.ATTRIBUTE, "101"), //$NON-NLS-1$
            new FormLayout.Segment(FormLayout.Segment.MEMBER, ARTICLE_UUID))).done);
        LayoutEditor.Outcome inPanel = editor.addElement("CheckBox", "ФлагВажный", "Панель1", "Общие", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            new int[] { 5, 30, 120, 49 }, "Важный", null, null); //$NON-NLS-1$
        assertTrue(inPanel.message, inPanel.done);
        assertFalse(editor.addElement("Label", "НадписьВес", null, null, new int[] { 0, 0, 1, 1 }, null, null, null).done); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(editor.addElement("Table", "Т", null, null, new int[] { 0, 0, 1, 1 }, null, null, null).done); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(editor.addElement("Label", "Н2", null, "Нигде", new int[] { 0, 0, 1, 1 }, null, null, null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(editor.addElement("Label", "Н3", "Наименование", null, new int[] { 0, 0, 1, 1 }, null, null, null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String text = editor.text();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog", ALayoutIsReadOffTheContainerTest.NAMES); //$NON-NLS-1$
        assertEquals(11, layout.allElements().size());
        FormLayout.Element weightLabel = layout.elementNamed("НадписьВес"); //$NON-NLS-1$
        assertEquals("Label", weightLabel.type); //$NON-NLS-1$
        assertEquals("Вес:", weightLabel.title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Дополнительно", weightLabel.page); //$NON-NLS-1$
        assertEquals(List.of(10, 100, 90, 119), List.of(weightLabel.box[0], weightLabel.box[1], weightLabel.box[2], weightLabel.box[3]));
        FormLayout.Element weight = layout.elementNamed("Вес"); //$NON-NLS-1$
        assertEquals("Вес, кг", weight.tooltip.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Number(10,3)", weight.valueType); //$NON-NLS-1$
        assertEquals("Объект.Артикул", weight.data); //$NON-NLS-1$
        assertTrue(weight.handlers.isEmpty());
        FormLayout.Element important = layout.elementNamed("ФлагВажный"); //$NON-NLS-1$
        assertEquals("Общие", important.page); //$NON-NLS-1$
        assertEquals(2, layout.elementNamed("Панель1").children.size()); //$NON-NLS-1$
        // The new element's own size sits in its anchors, and nothing is anchored to it.
        assertTrue(text, text.contains("{2,20,0,19}")); //$NON-NLS-1$
        assertTrue(text, text.contains("{2,20,2,80}")); //$NON-NLS-1$

        LayoutEditor.Outcome removed = editor.removeElement("Панель1"); //$NON-NLS-1$
        assertTrue(removed.message, removed.done);
        assertTrue(removed.message, removed.message.contains("2 nested element(s)")); //$NON-NLS-1$
        LayoutEditor.Outcome removedField = editor.removeElement("Наименование"); //$NON-NLS-1$
        assertTrue(removedField.message, removedField.message.contains("НаименованиеПриИзменении")); //$NON-NLS-1$
        assertFalse(editor.removeElement("Наименование").done); //$NON-NLS-1$
        FormLayout after = LayoutReader.read(BraceTree.parse(editor.text()), "Catalog"); //$NON-NLS-1$
        assertEquals(7, after.allElements().size());
        assertNull(after.elementNamed("ФлагАктивен")); //$NON-NLS-1$
        assertNull(after.paths.get("16")); //$NON-NLS-1$
        assertNull(after.paths.get("12")); //$NON-NLS-1$
        assertNotNull(after.paths.get("21")); //$NON-NLS-1$
    }

    /** A panel comes with one page and takes children; the form's size is a property. */
    @Test
    public void aPanelIsAddedWithItsPage()
    {
        LayoutEditor editor = editor();
        LayoutEditor.Outcome panel = editor.addElement("Panel", "ПанельНовая", null, null, new int[] { 10, 200, 400, 300 }, null, null, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(panel.message, panel.done);
        LayoutEditor.Outcome inside = editor.addElement("Label", "НадписьВнутри", "ПанельНовая", "Страница1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            new int[] { 5, 5, 100, 24 }, "Внутри", null, null); //$NON-NLS-1$
        assertTrue(inside.message, inside.done);
        assertTrue(editor.setSize(Integer.valueOf(800), null).done);
        assertFalse(editor.setSize(Integer.valueOf(0), null).done);
        String text = editor.text();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        assertFalse(text, text.contains("@")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        FormLayout.Element added = layout.elementNamed("ПанельНовая"); //$NON-NLS-1$
        assertEquals("Panel", added.type); //$NON-NLS-1$
        assertEquals(1, added.pages.size());
        assertEquals("Страница1", added.pages.get(0).name); //$NON-NLS-1$
        assertEquals(1, added.children.size());
        assertEquals("Страница1", added.children.get(0).page); //$NON-NLS-1$
        assertTrue(text, text.contains("},800,480,")); //$NON-NLS-1$
        // the new panel carries the block behind its pages list: four rows of page 0, one entry
        assertTrue(text, text.contains("\"Страница1\",1}")); //$NON-NLS-1$
        assertTrue(text, text.contains("},1,1,0,4,")); //$NON-NLS-1$
        assertTrue(text, text.contains("{2,5,1,1,1,0,0,0,0},")); //$NON-NLS-1$
        assertTrue(text, text.contains("{2,24,0,1,4,0,0,0,0},0,4294967295,5,64,0}")); //$NON-NLS-1$
    }

    /** A panel draws no tabs with one page, draws them from the second, and takes the flag by hand. */
    @Test
    public void aPanelDrawsTabsFromItsSecondPage()
    {
        LayoutEditor editor = editor();
        assertTrue(editor.addElement("Panel", "ПанельЗакладок", null, null, new int[] { 10, 200, 400, 300 }, //$NON-NLS-1$ //$NON-NLS-2$
            null, null, null).done);
        assertEquals(Boolean.FALSE, panel(editor, "ПанельЗакладок").details.get("tabs")); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor.Outcome second = editor.addPage("ПанельЗакладок", "Вторая", "Вторая"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(second.message, second.done);
        assertTrue(second.message, second.message.contains("tab per page")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, panel(editor, "ПанельЗакладок").details.get("tabs")); //$NON-NLS-1$ //$NON-NLS-2$
        LayoutEditor.Outcome off = editor.setTabs("ПанельЗакладок", false); //$NON-NLS-1$
        assertTrue(off.message, off.done);
        assertEquals(Boolean.FALSE, panel(editor, "ПанельЗакладок").details.get("tabs")); //$NON-NLS-1$ //$NON-NLS-2$
        // a third page leaves the panel as the caller set it
        assertTrue(editor.addPage("ПанельЗакладок", "Третья", "Третья").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Boolean.FALSE, panel(editor, "ПанельЗакладок").details.get("tabs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.setTabs("ПанельЗакладок", true).done); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, panel(editor, "ПанельЗакладок").details.get("tabs")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The tabs flag is refused where there is no panel and where there is no pages list. */
    @Test
    public void tabsAreRefusedWithoutAPanel()
    {
        LayoutEditor editor = editor();
        assertFalse(editor.setTabs("НетТакой", true).done); //$NON-NLS-1$
        assertTrue(editor.addElement("Label", "НадписьБезСтраниц", null, null, new int[] { 5, 5, 100, 24 }, //$NON-NLS-1$ //$NON-NLS-2$
            "Надпись", null, null).done); //$NON-NLS-1$
        LayoutEditor.Outcome refused = editor.setTabs("НадписьБезСтраниц", true); //$NON-NLS-1$
        assertFalse(refused.message, refused.done);
    }

    /**
     * @param editor the editor to read back
     * @param name the panel
     * @return the panel as the reader sees it
     */
    private static FormLayout.Element panel(LayoutEditor editor, String name)
    {
        return LayoutReader.read(BraceTree.parse(editor.text()), "Catalog").elementNamed(name); //$NON-NLS-1$
    }

    /** A page is added beside the existing ones of the form and of a panel, and an element can land on it. */
    @Test
    public void pagesAreAdded()
    {
        LayoutEditor editor = editor();
        assertTrue(editor.addPage(null, "Прочее", "Прочее").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(editor.addPage(null, "Основная", "x").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.addPage("Панель1", "Третья", "Третья страница").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(editor.addElement("Button", "КнопкаПрочее", null, "Прочее", new int[] { 10, 10, 90, 33 }, "Прочее", null, null).done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        FormLayout layout = LayoutReader.read(BraceTree.parse(editor.text()), "Catalog"); //$NON-NLS-1$
        assertEquals(List.of("Основная", "Дополнительно", "Прочее"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List.of(layout.pages.get(0).name, layout.pages.get(1).name, layout.pages.get(2).name));
        assertEquals("Прочее", layout.pages.get(2).title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, layout.elementNamed("Панель1").pages.size()); //$NON-NLS-1$
        assertEquals("Третья страница", layout.elementNamed("Панель1").pages.get(2).title.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("Прочее", layout.elementNamed("КнопкаПрочее").page); //$NON-NLS-1$ //$NON-NLS-2$
        // both blocks grew: four rows of page 2 and a third entry, on the form and on the panel
        String text = editor.text();
        assertEquals(text, 1, count(text, "{2,0,1,1,1,2,0,0,0},")); //$NON-NLS-1$
        assertEquals(text, 2, count(text, ",1,1,0,12,")); //$NON-NLS-1$
        assertEquals(text, 1, count(text, "{2,0,0,1,4,2,0,0,0},0,4294967295,4294967295,4294967295,5,64,0}")); //$NON-NLS-1$
        // the form's third page holds the button, so its rows carry the button's box
        assertEquals(text, 1, count(text, "{2,10,1,1,1,2,0,0,0},")); //$NON-NLS-1$
        assertEquals(text, 1, count(text, "{2,33,0,1,4,2,0,0,0},0,4294967295,4294967295,4294967295,5,64,0}")); //$NON-NLS-1$
        // a page with an element on it stays, the last page stays, an empty one goes with its rows and entry
        assertFalse(editor.removePage(null, "Прочее").done); //$NON-NLS-1$
        assertFalse(editor.removePage("Панель1", "Нет").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.removePage("Панель1", "Третья").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(editor.removeElement("КнопкаПрочее").done); //$NON-NLS-1$
        assertTrue(editor.removePage(null, "Прочее").done); //$NON-NLS-1$
        FormLayout shrunk = LayoutReader.read(BraceTree.parse(editor.text()), "Catalog"); //$NON-NLS-1$
        assertEquals(List.of("Основная", "Дополнительно"), List.of(shrunk.pages.get(0).name, shrunk.pages.get(1).name)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Дополнительно", shrunk.elementNamed("Артикул").page); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, shrunk.elementNamed("Панель1").pages.size()); //$NON-NLS-1$
        String after = editor.text();
        assertEquals(after, 2, count(after, ",1,1,0,8,")); //$NON-NLS-1$
        assertEquals(after, 2, count(after, "},0,4294967295,4294967295,5,64,0}")); //$NON-NLS-1$
        // the first page holds elements, so the ordinals of the second page's elements move down when the first goes
        assertFalse(editor.removePage(null, "Основная").done); //$NON-NLS-1$
        assertTrue(editor.removePage("Панель1", "Общие").done); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout panel = LayoutReader.read(BraceTree.parse(editor.text()), "Catalog"); //$NON-NLS-1$
        assertEquals(1, panel.elementNamed("Панель1").pages.size()); //$NON-NLS-1$
        assertEquals("Прочее", panel.elementNamed("ФлагАктивен").page); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(editor.removePage("Панель1", "Прочее").done); //$NON-NLS-1$ //$NON-NLS-2$
        // the fixture's element of an unknown type is the one unread record, before and after
        assertEquals(panel.unread.toString(), 1, panel.unread.size());
        // a page added and removed leaves the layout as it was, byte for byte
        LayoutEditor again = editor();
        assertTrue(again.addPage(null, "Мимо", "Мимо").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(again.addPage("Панель1", "Мимо", "Мимо").done); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(again.removePage("Панель1", "Мимо").done); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(again.removePage(null, "Мимо").done); //$NON-NLS-1$
        assertEquals(BraceTree.serialize(BraceTree.parse(layoutText)), again.text());
    }

    private static int count(String text, String part)
    {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
        {
            n++;
        }
        return n;
    }

    /** The operations write the container, honour dryRun, add a stub, and refuse by name. */
    @Test
    public void theOperationsWriteTheContainer() throws Exception
    {
        OrdinaryFormsTool tool = new OrdinaryFormsTool();
        byte[] before = Files.readAllBytes(container);

        String dry = tool.execute(Map.of("operation", "set_property", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "formFqn", "Catalog.Products.Form.ItemForm", "property", "caption", "elementName", "НадписьНаименование", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "value", "Название:", "dryRun", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        JsonObject dryAnswer = JsonParser.parseString(dry).getAsJsonObject();
        assertTrue(dry, dryAnswer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(dryAnswer.get("written").getAsBoolean()); //$NON-NLS-1$
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(container)));

        String bound = tool.execute(Map.of("operation", "bind_handler", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "modulePath", "Catalogs/Products/Forms/ItemForm/Module.bsl", "elementName", "Артикул", "event", "ПриИзменении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        JsonObject boundAnswer = JsonParser.parseString(bound).getAsJsonObject();
        assertTrue(bound, boundAnswer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(boundAnswer.get("written").getAsBoolean()); //$NON-NLS-1$
        assertEquals("АртикулПриИзменении", boundAnswer.get("procedure").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Элемент", boundAnswer.get("parameters").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(boundAnswer.get("procedureDeclared").getAsBoolean()); //$NON-NLS-1$
        assertTrue(bound, boundAnswer.get("stub").getAsString().contains("Процедура АртикулПриИзменении(Элемент)")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(boundAnswer.get("delivery")); //$NON-NLS-1$
        OrdinaryFormFile written = OrdinaryFormFile.read(container);
        assertTrue(written.moduleText(), written.moduleText().contains("Процедура АртикулПриИзменении(Элемент)" + CRLF + "КонецПроцедуры" + CRLF)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(written.moduleText(), written.moduleText().startsWith("Процедура ПриОткрытии()")); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(written.formTree(), "Catalog"); //$NON-NLS-1$
        assertEquals("АртикулПриИзменении", layout.elementNamed("Артикул").handlers.get(0).procedure); //$NON-NLS-1$ //$NON-NLS-2$

        String again = tool.execute(Map.of("operation", "bind_handler", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "formFqn", "Catalog.Products.Form.ItemForm", "elementName", "Артикул", "event", "ПриИзменении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        JsonObject againAnswer = JsonParser.parseString(again).getAsJsonObject();
        assertTrue(againAnswer.get("procedureDeclared").getAsBoolean()); //$NON-NLS-1$
        assertFalse(againAnswer.has("stub")); //$NON-NLS-1$

        String added = tool.execute(Map.of("operation", "add_element", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "formFqn", "Catalog.Products.Form.ItemForm", "type", "Button", "name", "КнопкаПечать", "caption", "Печать", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
            "left", "200", "top", "90", "width", "100", "height", "23")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        JsonObject addedAnswer = JsonParser.parseString(added).getAsJsonObject();
        assertTrue(added, addedAnswer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("200,90,300,113", addedAnswer.get("box").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("20", addedAnswer.get("elementId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        String withData = tool.execute(Map.of("operation", "add_element", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "formFqn", "Catalog.Products.Form.ItemForm", "type", "Field", "name", "ПолеАртикул", "data", "Объект.Нигде")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        JsonObject withDataAnswer = JsonParser.parseString(withData).getAsJsonObject();
        assertFalse(withData, withDataAnswer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(withData, withDataAnswer.get("error").getAsString().contains("No member named Нигде under Объект")); //$NON-NLS-1$ //$NON-NLS-2$

        String removed = tool.execute(Map.of("operation", "remove_element", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "formFqn", "Catalog.Products.Form.ItemForm", "elementName", "КнопкаПечать")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(removed, JsonParser.parseString(removed).getAsJsonObject().get("success").getAsBoolean()); //$NON-NLS-1$
        assertNull(LayoutReader.read(OrdinaryFormFile.read(container).formTree(), "Catalog").elementNamed("КнопкаПечать")); //$NON-NLS-1$ //$NON-NLS-2$

        String unknown = tool.execute(Map.of("operation", "set_property", "projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "formFqn", "Catalog.Products.Form.ItemForm", "property", "colour", "elementName", "Артикул", "value", "red")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        assertTrue(unknown, unknown.contains("Unknown property colour")); //$NON-NLS-1$
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
}
