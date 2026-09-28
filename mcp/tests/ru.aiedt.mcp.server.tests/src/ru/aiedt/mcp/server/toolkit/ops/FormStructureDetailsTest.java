/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Button;
import com._1c.g5.v8.dt.form.model.CommandBarExtInfo;
import com._1c.g5.v8.dt.form.model.DataPath;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;
import com._1c.g5.v8.dt.form.model.FormPagesRepresentation;
import com._1c.g5.v8.dt.form.model.InputFieldExtInfo;
import com._1c.g5.v8.dt.form.model.ManagedFormFieldType;
import com._1c.g5.v8.dt.form.model.ManagedFormGroupType;
import com._1c.g5.v8.dt.form.model.PagesGroupExtInfo;
import com._1c.g5.v8.dt.form.model.Table;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What a form element carries beyond the seven properties the walk has always read: the footer a
 * column totals in, the icon, the tab row of a pages group, a submenu, a height, the multi-line mode
 * of an input field, the object a command bar takes its commands from, and an empty container named
 * as such.
 *
 * <p>The elements are real model objects built by the form factory: the properties are read through
 * reflection by method name, so a factory-made element exercises the same lookups a form opened in
 * EDT does, and the reading runs without a workspace.
 *
 * <p>The warning of an {@code add_group} that holds no children belongs to the same task: the write
 * stands, the children are absent, and the answer says so on every path the caller can read it
 * from - the front matter {@link EditFormTool} writes and the JSON field the facade emits.
 */
public class FormStructureDetailsTest
{
    private static final String FORM_FQN = "Catalog.Товары.Form.ФормаЭлемента"; //$NON-NLS-1$

    private static JsonObject parse(String json)
    {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static DataPath dataPath(String... segments)
    {
        DataPath path = FormFactory.eINSTANCE.createDataPath();
        path.getSegments().addAll(Arrays.asList(segments));
        return path;
    }

    private static JsonObject propertiesOf(Object item)
    {
        return new GetFormStructureTool().collectProperties(item);
    }

    private static FormGroup group(String name, ManagedFormGroupType type)
    {
        FormGroup group = FormFactory.eINSTANCE.createFormGroup();
        group.setName(name);
        group.setType(type);
        return group;
    }

    /**
     * The node of a walked tree that carries the given name, or <code>null</code> when the walk
     * emitted none - the tree is searched rather than indexed, because an item is not the only
     * child a container may report.
     */
    private static JsonObject nodeNamed(JsonObject node, String name)
    {
        if (node == null)
        {
            return null;
        }
        if (node.has("name") && name.equals(node.get("name").getAsString())) //$NON-NLS-1$
        {
            return node;
        }
        if (node.has("items")) //$NON-NLS-1$
        {
            for (JsonElement child : node.getAsJsonArray("items")) //$NON-NLS-1$
            {
                JsonObject found = nodeNamed(child.getAsJsonObject(), name);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    private static JsonObject walkedNode(Form form, String name, int depth)
    {
        JsonObject root = new GetFormStructureTool().walk(form, 0, depth, 500, new AtomicInteger());
        JsonObject node = nodeNamed(root, name);
        assertNotNull("the walk must emit " + name, node); //$NON-NLS-1$
        return node;
    }

    private static JsonObject marksOf(JsonObject node)
    {
        assertTrue("the node must carry properties: " + node, node.has("properties")); //$NON-NLS-1$ //$NON-NLS-2$
        return node.getAsJsonObject("properties"); //$NON-NLS-1$
    }

    // -- footer, data path --

    @Test
    public void aColumnReportsTheFooterItTotals()
    {
        FormField column = FormFactory.eINSTANCE.createFormField();
        column.setName("ТоварыСумма"); //$NON-NLS-1$
        column.setFooterDataPath(dataPath("Объект", "Товары", "Сумма")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject props = propertiesOf(column);

        assertNotNull(props);
        assertEquals("Объект.Товары.Сумма", props.get("footer").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aColumnWithoutAFooterSaysNothingAboutOne()
    {
        JsonObject props = propertiesOf(FormFactory.eINSTANCE.createFormField());

        assertTrue(props == null || !props.has("footer")); //$NON-NLS-1$
    }

    /**
     * The data path is written the way the form file writes it. The model answers {@code toString()}
     * as segments joined with slashes and a leading slash, which is a Java rendering of the path and
     * not the text a form file carries.
     */
    @Test
    public void aDataPathIsReadAsTheFormFileWritesIt()
    {
        DataPath path = dataPath("Объект", "Товары", "Сумма"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("Объект.Товары.Сумма", GetFormStructureTool.dataPathText(path)); //$NON-NLS-1$
        assertEquals("Объект.Товары.Сумма", //$NON-NLS-1$
            GetFormStructureTool.dataPathText("Объект.Товары.Сумма")); //$NON-NLS-1$
        assertEquals("/Объект/Товары/Сумма", path.toString()); //$NON-NLS-1$
        assertNull(GetFormStructureTool.dataPathText(null));
        assertNull(GetFormStructureTool.dataPathText("")); //$NON-NLS-1$
        assertNull(GetFormStructureTool.dataPathText(new Object()));
    }

    @Test
    public void anElementDataPathReachesThePropertiesDotted()
    {
        FormField column = FormFactory.eINSTANCE.createFormField();
        column.setName("ТоварыСумма"); //$NON-NLS-1$
        column.setDataPath(dataPath("Объект", "Товары", "Сумма")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        JsonObject props = propertiesOf(column);

        assertNotNull(props);
        assertEquals("Объект.Товары.Сумма", props.get("dataPath").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -- picture --

    /**
     * The picture of a button is a reference, and a picture of the platform sits in it as an
     * unresolved reference: {@code PictureRef.picture} is the one feature of the pair that may hold
     * a proxy - the picture a button carries sits in the form file and is a containment value, the
     * picture it points at does not and is not.
     */
    @Test
    public void aPictureOfThePlatformIsNamedByItsReference()
    {
        Button button = FormFactory.eINSTANCE.createButton();
        button.setName("Печать"); //$NON-NLS-1$
        PictureRef reference = McoreFactory.eINSTANCE.createPictureRef();
        Picture platform = McoreFactory.eINSTANCE.createPictureRef();
        ((InternalEObject)platform).eSetProxyURI(URI.createURI("unresolved:/StdPicture.Print")); //$NON-NLS-1$
        reference.setPicture(platform);
        button.setPicture(reference);

        JsonObject props = propertiesOf(button);

        assertNotNull(props);
        assertEquals("StdPicture.Print", props.get("picture").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Only an {@code unresolved} reference may answer a name. The last segment of any other scheme is
     * a file name or an id, and reporting it would name a picture the form never had.
     */
    @Test
    public void aReferenceOfAnotherSchemeNamesNoPicture()
    {
        PictureRef picture = McoreFactory.eINSTANCE.createPictureRef();
        ((InternalEObject)picture)
            .eSetProxyURI(URI.createURI("platform:/resource/Проект/CommonPicture/Логотип")); //$NON-NLS-1$

        assertNull(GetFormStructureTool.pictureName(picture));
    }

    @Test
    public void aNamedPictureAnswersItsName()
    {
        CommonPicture logo = MdClassFactory.eINSTANCE.createCommonPicture();
        logo.setName("Логотип"); //$NON-NLS-1$

        assertEquals("Логотип", GetFormStructureTool.pictureName(logo)); //$NON-NLS-1$
        assertNull(GetFormStructureTool.pictureName(McoreFactory.eINSTANCE.createPictureRef()));
        assertNull(GetFormStructureTool.pictureName(null));
        assertNull(GetFormStructureTool.pictureName(new Object()));
    }

    // -- kind, submenu, tabs --

    @Test
    public void aPopupGroupIsASubmenu()
    {
        JsonObject props = propertiesOf(group("ПодменюПечать", ManagedFormGroupType.POPUP)); //$NON-NLS-1$

        assertNotNull(props);
        assertEquals("Popup", props.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(props.get("submenu").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void aUsualGroupIsNoSubmenu()
    {
        JsonObject props = propertiesOf(group("ГруппаРеквизитов", ManagedFormGroupType.USUAL_GROUP)); //$NON-NLS-1$

        assertNotNull(props);
        assertEquals("UsualGroup", props.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(props.has("submenu")); //$NON-NLS-1$
        assertFalse(props.has("tabs")); //$NON-NLS-1$
    }

    @Test
    public void aPagesGroupReportsItsTabRow()
    {
        FormGroup pages = group("Страницы", ManagedFormGroupType.PAGES); //$NON-NLS-1$
        PagesGroupExtInfo extInfo = FormFactory.eINSTANCE.createPagesGroupExtInfo();
        extInfo.setPagesRepresentation(FormPagesRepresentation.TABS_ON_TOP);
        pages.setExtInfo(extInfo);

        JsonObject props = propertiesOf(pages);

        assertNotNull(props);
        assertEquals("Pages", props.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("TabsOnTop", props.get("tabs").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // -- height, multi-line, command source --

    @Test
    public void aButtonReportsItsHeightOnlyWhenItHasOne()
    {
        Button button = FormFactory.eINSTANCE.createButton();
        button.setName("Печать"); //$NON-NLS-1$

        assertFalse(propertiesOf(button).has("height")); //$NON-NLS-1$

        button.setHeight(3);

        assertEquals(3, propertiesOf(button).get("height").getAsInt()); //$NON-NLS-1$
    }

    @Test
    public void anInputFieldReportsMultiLine()
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName("Комментарий"); //$NON-NLS-1$
        field.setType(ManagedFormFieldType.INPUT_FIELD);
        InputFieldExtInfo extInfo = FormFactory.eINSTANCE.createInputFieldExtInfo();
        extInfo.setMultiLine(Boolean.TRUE);
        field.setExtInfo(extInfo);

        JsonObject props = propertiesOf(field);

        assertNotNull(props);
        assertEquals("InputField", props.get("kind").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(props.get("multiLine").getAsBoolean()); //$NON-NLS-1$
    }

    @Test
    public void aSingleLineFieldSaysNothingAboutMultiLine()
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName("Комментарий"); //$NON-NLS-1$
        field.setType(ManagedFormFieldType.INPUT_FIELD);
        InputFieldExtInfo extInfo = FormFactory.eINSTANCE.createInputFieldExtInfo();
        extInfo.setMultiLine(Boolean.FALSE);
        field.setExtInfo(extInfo);

        assertFalse(propertiesOf(field).has("multiLine")); //$NON-NLS-1$
    }

    @Test
    public void aCommandBarNamesTheObjectItTakesCommandsFrom()
    {
        Table table = FormFactory.eINSTANCE.createTable();
        table.setName("Товары"); //$NON-NLS-1$
        FormGroup bar = group("КоманднаяПанель", ManagedFormGroupType.COMMAND_BAR); //$NON-NLS-1$
        CommandBarExtInfo extInfo = FormFactory.eINSTANCE.createCommandBarExtInfo();
        extInfo.setCommandSource(table);
        bar.setExtInfo(extInfo);

        JsonObject props = propertiesOf(bar);

        assertNotNull(props);
        assertEquals("Table.Товары", props.get("commandSource").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aCommandSourceWithoutANameIsNamedByItsKind()
    {
        assertEquals("Form", GetFormStructureTool.commandSourceText(FormFactory.eINSTANCE.createForm())); //$NON-NLS-1$
        assertNull(GetFormStructureTool.commandSourceText(null));
        assertNull(GetFormStructureTool.commandSourceText(new Object()));
    }

    @Test
    public void anElementWithoutACommandSourceSaysNothingAboutOne()
    {
        JsonObject props = propertiesOf(group("Группа", ManagedFormGroupType.USUAL_GROUP)); //$NON-NLS-1$

        assertTrue(props == null || !props.has("commandSource")); //$NON-NLS-1$
    }

    // -- empty containers --

    @Test
    public void anEmptyGroupIsMarkedAndNamed()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.getItems().add(group("ПустаяГруппа", ManagedFormGroupType.USUAL_GROUP)); //$NON-NLS-1$

        JsonObject groupNode = walkedNode(form, "ПустаяГруппа", 0); //$NON-NLS-1$
        JsonArray groups = new JsonArray();
        JsonArray tabs = new JsonArray();
        GetFormStructureTool.collectEmptyGroups(
            new GetFormStructureTool().walk(form, 0, 0, 500, new AtomicInteger()), groups, tabs);

        assertTrue(marksOf(groupNode).get("empty").getAsBoolean()); //$NON-NLS-1$
        assertEquals(1, groups.size());
        assertEquals("ПустаяГруппа", groups.get(0).getAsString()); //$NON-NLS-1$
        assertEquals(0, tabs.size());
    }

    @Test
    public void aGroupWithItemsIsNotCalledEmpty()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup group = group("ГруппаРеквизитов", ManagedFormGroupType.USUAL_GROUP); //$NON-NLS-1$
        group.getItems().add(FormFactory.eINSTANCE.createButton());
        form.getItems().add(group);

        assertFalse(marksOf(walkedNode(form, "ГруппаРеквизитов", 0)).has("empty")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aLeafIsNotCalledEmpty()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        Button button = FormFactory.eINSTANCE.createButton();
        button.setName("Печать"); //$NON-NLS-1$
        form.getItems().add(button);

        assertFalse(marksOf(walkedNode(form, "Печать", 0)).has("empty")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An empty page is marked as an empty container: the page is in the form file, the platform
     * renders it with nothing in it, and a reader that only sees the tree cannot tell it from a page
     * that is not there. The tab row itself carries a page, so it is not empty.
     */
    @Test
    public void anEmptyPageIsMarkedWhileTheTabRowHoldingItIsNot()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup pages = group("Страницы", ManagedFormGroupType.PAGES); //$NON-NLS-1$
        pages.getItems().add(group("Страница1", ManagedFormGroupType.PAGE)); //$NON-NLS-1$
        form.getItems().add(pages);

        JsonObject root = new GetFormStructureTool().walk(form, 0, 0, 500, new AtomicInteger());
        JsonArray groups = new JsonArray();
        JsonArray tabs = new JsonArray();
        GetFormStructureTool.collectEmptyGroups(root, groups, tabs);

        assertFalse(marksOf(nodeNamed(root, "Страницы")).has("empty")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(marksOf(nodeNamed(root, "Страница1")).get("empty").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, groups.size());
        assertEquals("Страница1", groups.get(0).getAsString()); //$NON-NLS-1$
        assertEquals(0, tabs.size());
    }

    @Test
    public void anEmptyTabRowIsNamedSeparatelyFromTheGroups()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup pages = group("Страницы", ManagedFormGroupType.PAGES); //$NON-NLS-1$
        pages.setExtInfo(FormFactory.eINSTANCE.createPagesGroupExtInfo());
        form.getItems().add(pages);
        form.getItems().add(group("ПустаяГруппа", ManagedFormGroupType.USUAL_GROUP)); //$NON-NLS-1$

        JsonArray groups = new JsonArray();
        JsonArray tabs = new JsonArray();
        GetFormStructureTool.collectEmptyGroups(
            new GetFormStructureTool().walk(form, 0, 0, 500, new AtomicInteger()), groups, tabs);

        assertEquals(1, tabs.size());
        assertEquals("Страницы", tabs.get(0).getAsString()); //$NON-NLS-1$
        assertEquals(1, groups.size());
        assertEquals("ПустаяГруппа", groups.get(0).getAsString()); //$NON-NLS-1$
    }

    @Test
    public void theMarkDoesNotDependOnTheDepthBudget()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.getItems().add(group("ПустаяГруппа", ManagedFormGroupType.USUAL_GROUP)); //$NON-NLS-1$

        assertTrue(marksOf(walkedNode(form, "ПустаяГруппа", 1)).get("empty").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void noMarkIsReadFromATreeThatIsNotThere()
    {
        JsonArray groups = new JsonArray();
        JsonArray tabs = new JsonArray();
        GetFormStructureTool.collectEmptyGroups(null, groups, tabs);

        assertEquals(0, groups.size());
        assertEquals(0, tabs.size());
    }

    // -- the add_group warning --

    @Test
    public void anAddedGroupWithoutChildrenWarnsInTheJsonAnswer()
    {
        String markdown = new EditFormTool().buildSuccess("edit_form", "ГруппаПремии", "add_group", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Group 'ГруппаПремии' added to form successfully.", //$NON-NLS-1$
            EditFormTool.ADD_GROUP_HAS_NO_CHILDREN_WARNING);

        JsonObject answer = parse(
            FormItemsOps.convertEditFormMarkdownToJson(markdown, "add_group", FORM_FQN)); //$NON-NLS-1$

        assertFalse(answer.has("error")); //$NON-NLS-1$
        assertTrue(answer.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("add_group", answer.get("operation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(EditFormTool.ADD_GROUP_HAS_NO_CHILDREN_WARNING,
            answer.get("warning").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void anAnswerWithoutAWarningLeavesTheKeyOut()
    {
        String markdown = new EditFormTool().buildSuccess("edit_form", "ПолеВвода", "add_field", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Field 'ПолеВвода' added to form successfully.", null); //$NON-NLS-1$

        JsonObject answer = parse(
            FormItemsOps.convertEditFormMarkdownToJson(markdown, "add_field", FORM_FQN)); //$NON-NLS-1$

        assertFalse(answer.has("warning")); //$NON-NLS-1$
    }
}
