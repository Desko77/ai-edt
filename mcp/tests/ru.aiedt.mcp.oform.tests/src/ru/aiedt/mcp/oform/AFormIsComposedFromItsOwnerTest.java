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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree.ListNode;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.layout.MainAttributes;
import ru.aiedt.mcp.oform.ops.ComposerOperations;

/**
 * A form laid out from an owner's description, with no project: the header, the tables, the nine
 * argument variants, the three seams of the write, and the same geometry after the form grows.
 */
public class AFormIsComposedFromItsOwnerTest
{
    private static final String OBJ = "11111111-1111-1111-1111-111111111111"; //$NON-NLS-1$

    private static final String LIST = "22222222-2222-2222-2222-222222222222"; //$NON-NLS-1$

    private static final String REF = "33333333-3333-3333-3333-333333333333"; //$NON-NLS-1$

    private static final String ROW = "44444444-4444-4444-4444-444444444444"; //$NON-NLS-1$

    private static final String SET = "55555555-5555-5555-5555-555555555555"; //$NON-NLS-1$

    private static final String REC = "66666666-6666-6666-6666-666666666666"; //$NON-NLS-1$

    private static final Pattern OFFSET = Pattern.compile("^(.*?)([+-]\\d+)$"); //$NON-NLS-1$

    /** A catalog with a header and one tabular section lays out inside itself, and reads back. */
    @Test
    public void catalogWithATabularSection()
    {
        FormLayout layout = placed(compose(catalog(), args("ItemForm"))); //$NON-NLS-1$
        assertNotNull(layout.elementNamed("Артикул")); //$NON-NLS-1$
        assertNotNull(layout.elementNamed("Состав")); //$NON-NLS-1$
        assertNotNull(layout.elementNamed("ОсновныеДействияФормы")); //$NON-NLS-1$
        assertEquals("Состав", layout.elementNamed("КоманднаяПанельСостав").details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A document with two tabular sections puts them on pages under the header. */
    @Test
    public void documentWithTwoTabularSections()
    {
        FormComposer.Result result = compose(document(2), args("DocumentForm")); //$NON-NLS-1$
        FormLayout layout = placed(result);
        assertNotNull(layout.elementNamed("ТабличныеЧасти")); //$NON-NLS-1$
        assertEquals(2, layout.elementNamed("ТабличныеЧасти").pages.size()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, layout.elementNamed("ТабличныеЧасти").details.get("tabs")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Товары", layout.elementNamed("ТабличныеЧасти").pages.get(0).name); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Услуги", layout.elementNamed("ТабличныеЧасти").pages.get(1).name); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Товары", layout.elementNamed("Товары").page); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Услуги", layout.elementNamed("Услуги").page); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.tables.contains("Товары")); //$NON-NLS-1$
        assertTrue(result.tables.contains("Услуги")); //$NON-NLS-1$
        assertEquals(26, strip(layout.elementNamed("ТабличныеЧасти"))); //$NON-NLS-1$
    }

    /**
     * @param panel a panel
     * @return the room it keeps between the deepest element of its pages and its own bottom
     */
    private static int strip(FormLayout.Element panel)
    {
        int deepest = 0;
        for (FormLayout.Element child : panel.children)
        {
            deepest = Math.max(deepest, child.box[3]);
        }
        return panel.box[3] - panel.box[1] - deepest;
    }

    /** A record form is the register's dimensions, resources and attributes, and no table. */
    @Test
    public void registerRecordForm()
    {
        FormComposer.Result result = compose(register(), args("RecordForm")); //$NON-NLS-1$
        FormLayout layout = placed(result);
        assertTrue(result.fields.contains("Номенклатура")); //$NON-NLS-1$
        assertTrue(result.fields.contains("Цена")); //$NON-NLS-1$
        assertTrue(result.fields.contains("Комментарий")); //$NON-NLS-1$
        assertFalse(result.fields.contains("Период")); //$NON-NLS-1$
        assertTrue(result.tables.isEmpty());
        assertNotNull(layout.elementNamed("ОсновныеДействияФормы")); //$NON-NLS-1$
    }

    /** ItemForm and ObjectForm are the same object form. */
    @Test
    public void purposeItemForm()
    {
        assertEquals(compose(catalog(), args("ItemForm")).fields, compose(catalog(), args("ObjectForm")).fields); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** ObjectForm keeps the item form's header and its tabular section. */
    @Test
    public void purposeObjectForm()
    {
        FormComposer.Result result = compose(catalog(), args("ObjectForm")); //$NON-NLS-1$
        placed(result);
        assertTrue(result.fields.contains("Код")); //$NON-NLS-1$
        assertTrue(result.tables.contains("Состав")); //$NON-NLS-1$
    }

    /** DocumentForm puts Номер and Дата ahead of the document's own attributes. */
    @Test
    public void purposeDocumentForm()
    {
        FormComposer.Result result = compose(document(2), args("DocumentForm")); //$NON-NLS-1$
        placed(result);
        assertEquals("Номер", result.fields.get(0)); //$NON-NLS-1$
        assertEquals("Дата", result.fields.get(1)); //$NON-NLS-1$
    }

    /** A folder form is Код, Наименование and Родитель, and it drops tabular sections. */
    @Test
    public void purposeFolderForm()
    {
        FormComposer.Result result = compose(catalog(), args("FolderForm")); //$NON-NLS-1$
        placed(result);
        assertEquals(List.of("Код", "Наименование", "Родитель"), result.fields); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(result.tables.isEmpty());
    }

    /** A list form is one table over the list, above the form's panel. */
    @Test
    public void purposeListForm()
    {
        FormLayout layout = placed(compose(catalog(), args("ListForm"))); //$NON-NLS-1$
        String table = MainAttributes.of("Catalog", MainAttributes.Purpose.LIST).name; //$NON-NLS-1$
        FormLayout.Element list = layout.elementNamed(table);
        assertNotNull(list);
        assertEquals(0, list.box[0]);
        assertEquals(0, list.box[1]);
        assertEquals(table, layout.elementNamed("ОсновныеДействияФормы").details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A choice form is a list form. */
    @Test
    public void purposeChoiceForm()
    {
        FormComposer.Result result = compose(catalog(), args("ChoiceForm")); //$NON-NLS-1$
        placed(result);
        assertEquals(List.of(MainAttributes.of("Catalog", MainAttributes.Purpose.CHOICE).name), result.tables); //$NON-NLS-1$
    }

    /** RecordForm is named so the purpose table has a row of its own. */
    @Test
    public void purposeRecordForm()
    {
        assertTrue(compose(register(), args("RecordForm")).ok); //$NON-NLS-1$
    }

    /** A record set fills the form with one table and a panel that takes its actions from it. */
    @Test
    public void purposeRecordSetForm()
    {
        FormLayout layout = placed(compose(register(), args("RecordSetForm"))); //$NON-NLS-1$
        String table = MainAttributes.of("InformationRegister", MainAttributes.Purpose.RECORD_SET).name; //$NON-NLS-1$
        assertNotNull(layout.elementNamed(table));
        assertEquals(table, layout.elementNamed("ОсновныеДействияФормы").details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** None lays nothing out, and says that the header flags do nothing. */
    @Test
    public void purposeNone()
    {
        Map<String, String> params = args("None"); //$NON-NLS-1$
        params.put("withFields", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("withTables", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        FormComposer.Result result = compose(catalog(), params);
        FormLayout layout = placed(result);
        assertTrue(layout.elements.isEmpty());
        assertTrue(result.note, result.note.contains("withFields")); //$NON-NLS-1$
        assertTrue(result.note, result.note.contains("withTables")); //$NON-NLS-1$
    }

    /** withFields=false leaves the header out and keeps the table. */
    @Test
    public void withFieldsFalse()
    {
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("withFields", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        FormComposer.Result result = compose(catalog(), params);
        FormLayout layout = placed(result);
        assertTrue(result.fields.isEmpty());
        assertTrue(result.tables.contains("Состав")); //$NON-NLS-1$
        assertNull(layout.elementNamed("Артикул")); //$NON-NLS-1$
    }

    /** withTables=false leaves the tabular sections out and keeps the header. */
    @Test
    public void withTablesFalse()
    {
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("withTables", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        FormComposer.Result result = compose(catalog(), params);
        placed(result);
        assertTrue(result.tables.isEmpty());
        assertFalse(result.fields.isEmpty());
    }

    /** withCommands=false leaves both the form panel and the table panels out. */
    @Test
    public void withCommandsFalse()
    {
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("withCommands", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout layout = placed(compose(catalog(), params));
        for (FormLayout.Element element : layout.allElements())
        {
            assertFalse(element.name, "CommandPanel".equals(element.type)); //$NON-NLS-1$
        }
    }

    /** With no header the tables start at the top margin. */
    @Test
    public void tablesStartAtTheTopMargin()
    {
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("withFields", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("withTables", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout layout = placed(compose(catalog(), params));
        assertEquals(8, layout.elementNamed("КоманднаяПанельСостав").box[1]); //$NON-NLS-1$
    }

    /** pages with one tabular section is a panel of one page. */
    @Test
    public void pagesWithOneTabularSection()
    {
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("tabularSections", "pages"); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout layout = placed(compose(catalog(), params));
        FormLayout.Element panel = layout.elementNamed("ТабличныеЧасти"); //$NON-NLS-1$
        assertNotNull(panel);
        assertEquals(1, panel.pages.size());
        assertNotNull(layout.elementNamed("Состав")); //$NON-NLS-1$
    }

    /** Two tables stacked under a twelve-row header fit in 700. */
    @Test
    public void belowWithTwoTabularSectionsFits()
    {
        Map<String, String> params = args("DocumentForm"); //$NON-NLS-1$
        params.put("tabularSections", "below"); //$NON-NLS-1$ //$NON-NLS-2$
        FormComposer.Result result = compose(document(2), params);
        FormLayout layout = placed(result);
        assertNull(layout.elementNamed("ТабличныеЧасти")); //$NON-NLS-1$
        assertEquals(2, result.tables.size());
        assertTrue(result.height <= 700);
    }

    /** Three tables stacked under the same header do not fit, and the refusal names pages. */
    @Test
    public void belowWithThreeTabularSectionsRefuses()
    {
        Map<String, String> params = args("DocumentForm"); //$NON-NLS-1$
        params.put("tabularSections", "below"); //$NON-NLS-1$ //$NON-NLS-2$
        FormComposer.Result result = compose(document(3), params);
        assertFalse(result.ok);
        assertTrue(result.error, result.error.contains("tabularSections=pages")); //$NON-NLS-1$
    }

    /** Two columns and an explicit width below 820 are refused by that minimum. */
    @Test
    public void twoColumnsRefuseANarrowWidth()
    {
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("columns", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("width", "600"); //$NON-NLS-1$ //$NON-NLS-2$
        FormComposer.Result result = compose(catalog(), params);
        assertFalse(result.ok);
        assertTrue(result.error, result.error.contains("820")); //$NON-NLS-1$
    }

    /** Two columns and no width raise the form to 820, and only the second column stretches. */
    @Test
    public void twoColumnsWithoutAWidthAre820()
    {
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("columns", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        FormComposer.Result result = compose(catalog(), params);
        FormLayout layout = placed(result);
        assertEquals(820, result.width);
        assertEquals(2, result.columns);
        boolean first = false;
        boolean second = false;
        for (FormLayout.Element element : layout.elements)
        {
            if (!"Field".equals(element.type) && !"SelectField".equals(element.type)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            boolean stretches = String.valueOf(anchors(element).get("right")).startsWith("form"); //$NON-NLS-1$ //$NON-NLS-2$
            if (element.box[0] < 400)
            {
                assertFalse(element.name, stretches);
                first = true;
            }
            else
            {
                assertTrue(element.name, stretches);
                second = true;
            }
        }
        assertTrue(first && second);
    }

    /** A file that will not be written leaves the model alone and creates no directory. */
    @Test
    public void fileFailureLeavesTheModel()
    {
        Files files = new Files();
        files.failWrite = true;
        Model model = new Model();
        Map<String, Object> answer = OrdinaryFormCreation.create(catalog(), args("ItemForm"), files, model, new Marker()); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), String.valueOf(answer.get("error")).contains("model was not changed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(model.commitCalled);
        assertFalse(files.exists);
    }

    /** A transaction that fails removes the container, and the next call passes the checks. */
    @Test
    public void transactionFailureRemovesTheContainer()
    {
        Files files = new Files();
        Model model = new Model();
        model.failCommit = true;
        Map<String, Object> first = OrdinaryFormCreation.create(catalog(), args("ItemForm"), files, model, new Marker()); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, first.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(first.get("error")), String.valueOf(first.get("error")).contains("container was removed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(files.exists);
        model.failCommit = false;
        Map<String, Object> second = OrdinaryFormCreation.create(catalog(), args("ItemForm"), files, model, new Marker()); //$NON-NLS-1$
        assertEquals(second.toString(), Boolean.TRUE, second.get("success")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, second.get("written")); //$NON-NLS-1$
    }

    /** When the container cannot be removed, the answer names its path. */
    @Test
    public void removalFailureNamesThePath()
    {
        Files files = new Files();
        files.failRemove = true;
        Model model = new Model();
        model.failCommit = true;
        Map<String, Object> answer = OrdinaryFormCreation.create(catalog(), args("ItemForm"), files, model, new Marker()); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), String.valueOf(answer.get("error")).contains("orphaned")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(answer.get("error")), String.valueOf(answer.get("error")).contains(files.orphan)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A mark that fails keeps the form and carries the reason with the update hint. */
    @Test
    public void markerFailureKeepsTheForm()
    {
        Files files = new Files();
        Model model = new Model();
        Marker marker = new Marker();
        marker.fail = true;
        Map<String, Object> answer = OrdinaryFormCreation.create(catalog(), args("ItemForm"), files, model, marker); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, answer.get("success")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, answer.get("written")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("delivery")), String.valueOf(answer.get("delivery")).contains("baseline locked")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(String.valueOf(answer.get("notes")), String.valueOf(answer.get("notes")).contains("update_database fullUpdate=true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(files.written);
        assertTrue(model.commitCalled);
    }

    /** A dry run checks both parts and writes nothing. */
    @Test
    public void dryRunWritesNothing()
    {
        Files files = new Files();
        Model model = new Model();
        Marker marker = new Marker();
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("dryRun", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> answer = OrdinaryFormCreation.create(catalog(), params, files, model, marker);
        assertEquals(answer.toString(), Boolean.TRUE, answer.get("success")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, answer.get("written")); //$NON-NLS-1$
        assertNotNull(answer.get("model")); //$NON-NLS-1$
        assertNotNull(answer.get("layoutPlan")); //$NON-NLS-1$
        assertFalse(files.writeCalled);
        assertFalse(model.commitCalled);
        assertFalse(marker.called);
        assertFalse(String.valueOf(answer), String.valueOf(answer).matches("(?s).*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-.*")); //$NON-NLS-1$
    }

    /** The second call after a rolled-back transaction is the one that writes. */
    @Test
    public void aSecondCallAfterRollbackPassesPreflight()
    {
        transactionFailureRemovesTheContainer();
    }

    /** A catalog item form still fits its neighbours after the form grows, in one column. */
    @Test
    public void catalogGrowsByOneColumn()
    {
        grows(catalog(), "ItemForm", "1"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A catalog item form still fits its neighbours after the form grows, in two columns. */
    @Test
    public void catalogGrowsByTwoColumns()
    {
        grows(catalog(), "ItemForm", "2"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A document form, pages and all, still fits after the form grows, in one column. */
    @Test
    public void documentGrowsByOneColumn()
    {
        grows(document(2), "DocumentForm", "1"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A document form, pages and all, still fits after the form grows, in two columns. */
    @Test
    public void documentGrowsByTwoColumns()
    {
        grows(document(2), "DocumentForm", "2"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A record form still fits after the form grows, in one column. */
    @Test
    public void registerGrowsByOneColumn()
    {
        grows(register(), "RecordForm", "1"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A record form still fits after the form grows, in two columns. */
    @Test
    public void registerGrowsByTwoColumns()
    {
        grows(register(), "RecordForm", "2"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** describe_owner names a catalog's attributes, section and standard attributes. */
    @Test
    public void describeCatalog()
    {
        Map<String, Object> described = ComposerOperations.describeOwner("Catalog.Товары", catalog()); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, described.get("success")); //$NON-NLS-1$
        assertEquals("Артикул", nameAt(described, "attributes", 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Состав", nameAt(described, "tabularSections", 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Код", nameAt(described, "standardAttributes", 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Код", nameAt(described, "listColumns", 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Наименование", nameAt(described, "listColumns", 1)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Проведен is described and is not a field: the corpus measured no code for it. */
    @Test
    public void describeDocumentNamesPosted()
    {
        Map<String, Object> described = document(1).describe();
        assertEquals("Проведен", nameAt(described, "standardAttributes", 2)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Boolean", typeAt(described, "standardAttributes", 2)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(compose(document(1), args("DocumentForm")).fields.contains("Проведен")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A register's description lists dimensions, resources and list columns in that order. */
    @Test
    public void describeRegister()
    {
        Map<String, Object> described = register().describe();
        assertEquals("Номенклатура", nameAt(described, "dimensions", 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Цена", nameAt(described, "resources", 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Номенклатура", nameAt(described, "listColumns", 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ВидЦен", nameAt(described, "listColumns", 1)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Цена", nameAt(described, "listColumns", 2)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(3, ((List<?>)described.get("listColumns")).size()); //$NON-NLS-1$
    }

    /** The description and the create answer carry no uuid. */
    @Test
    public void describePrintsNoUuid()
    {
        String text = String.valueOf(catalog().describe());
        assertFalse(text, text.matches("(?s).*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-.*")); //$NON-NLS-1$
    }

    /** Twelve header attributes stay in one column. */
    @Test
    public void autoColumnsStayOneUntilTwelve()
    {
        FormComposer.Result result = compose(document(1), args("DocumentForm")); //$NON-NLS-1$
        assertTrue(result.error, result.ok);
        assertEquals(1, result.columns);
        assertEquals(500, result.width);
    }

    /** The thirteenth header attribute opens the second column and widens the form to 820. */
    @Test
    public void autoColumnsBecomeTwoPastTwelve()
    {
        OwnerModel owner = document(1);
        OwnerModel.Builder builder = OwnerModel.builder("Document", "Заказ") //$NON-NLS-1$
            .objectType(OBJ, "DocumentObject.Заказ").listType(LIST, "DocumentList.Заказ"); //$NON-NLS-1$ //$NON-NLS-2$
        for (OwnerModel.Attribute standard : owner.standards)
        {
            builder.standard(standard);
        }
        for (OwnerModel.Attribute attribute : owner.attributes)
        {
            builder.attribute(attribute);
        }
        builder.attribute(member("Еще", "Еще", 90, "{\"Pattern\",{\"S\"}}", "String")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        FormComposer.Result result = compose(builder.build(), args("DocumentForm")); //$NON-NLS-1$
        assertTrue(result.error, result.ok);
        assertEquals(2, result.columns);
        assertEquals(820, result.width);
    }

    /** More than sixteen rows of one column move the header into a panel of pages. */
    @Test
    public void aLongHeaderGoesIntoPages()
    {
        OwnerModel.Builder builder = OwnerModel.builder("Catalog", "Длинный") //$NON-NLS-1$
            .objectType(OBJ, "CatalogObject.Длинный"); //$NON-NLS-1$
        for (int i = 0; i < 17; i++)
        {
            builder.attribute(member("Реквизит" + i, "Реквизит " + i, 100 + i, "{\"Pattern\",{\"S\"}}", "String")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("columns", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout layout = placed(compose(builder.build(), params));
        FormLayout.Element header = layout.elementNamed("Шапка"); //$NON-NLS-1$
        assertNotNull(header);
        // the header's own 400 and the room of the tab strip above its two pages
        assertEquals(426, header.box[3] - header.box[1]);
        assertEquals(2, header.pages.size());
        // the last page holds fewer rows than the first, so the room below its content is the
        // strip's and the rest of the page
        assertTrue(Integer.toString(strip(header)), strip(header) >= 26);
    }

    /** A boolean is a check box and has no label beside it. */
    @Test
    public void aBooleanIsACheckBox()
    {
        FormLayout layout = placed(compose(catalog(), args("ItemForm"))); //$NON-NLS-1$
        assertEquals("CheckBox", layout.elementNamed("Флаг").type); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(layout.elementNamed("НадписьФлаг")); //$NON-NLS-1$
    }

    /** A reference is a choice field. */
    @Test
    public void aReferenceIsASelectField()
    {
        FormLayout layout = placed(compose(catalog(), args("ItemForm"))); //$NON-NLS-1$
        assertEquals("SelectField", layout.elementNamed("Поставщик").type); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A date and a number carry the format of their type. */
    @Test
    public void aDateAndANumberCarryAFormat()
    {
        FormLayout layout = placed(compose(catalog(), args("ItemForm"))); //$NON-NLS-1$
        assertEquals("ДФ=dd.MM.yyyy", layout.elementNamed("Дата").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("ЧЦ=15; ЧДЦ=2", layout.elementNamed("Цена").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A number keeps the precision and the fraction the pattern records, in the pattern and in the format. */
    @Test
    public void aNumberKeepsItsPrecisionAndFraction()
    {
        FormLayout layout = placed(compose(field("Сумма", OwnerModelReader.numberPattern(Integer.valueOf(15), Integer.valueOf(2), Boolean.FALSE)), //$NON-NLS-1$
            args("ItemForm"))); //$NON-NLS-1$
        assertEquals("{\"Pattern\",{\"N\",15,2,0}}", flat(layout.elementNamed("Сумма"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ЧЦ=15; ЧДЦ=2", layout.elementNamed("Сумма").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A date with time keeps the fractions token and the format that shows the time. */
    @Test
    public void aDateTimeKeepsItsFractions()
    {
        FormLayout layout = placed(compose(field("Момент", OwnerModelReader.datePattern("DateTime")), args("ItemForm"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("{\"Pattern\",{\"D\",\"DT\"}}", flat(layout.elementNamed("Момент"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ДФ=dd.MM.yyyy HH:mm:ss", layout.elementNamed("Момент").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A time keeps its fractions token and the time format. */
    @Test
    public void aTimeKeepsItsFractions()
    {
        FormLayout layout = placed(compose(field("Начало", OwnerModelReader.datePattern("Time")), args("ItemForm"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("{\"Pattern\",{\"D\",\"T\"}}", flat(layout.elementNamed("Начало"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ДФ=HH:mm:ss", layout.elementNamed("Начало").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A date without the time keeps the date token and the date format. */
    @Test
    public void aDateKeepsItsFractions()
    {
        FormLayout layout = placed(compose(field("День", OwnerModelReader.datePattern("Date")), args("ItemForm"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("{\"Pattern\",{\"D\",\"D\"}}", flat(layout.elementNamed("День"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ДФ=dd.MM.yyyy", layout.elementNamed("День").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A catalog code takes the number type and the length, not the name Код. */
    @Test
    public void aNumericCatalogCodeTakesTheOwnersType()
    {
        ListNode pattern = OwnerModelReader.codePattern("Number", 9, null); //$NON-NLS-1$
        FormLayout layout = placed(compose(coded("Catalog", "Код", -2, pattern), args("ItemForm"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("{\"Pattern\",{\"N\",9,0,0}}", flat(layout.elementNamed("Код"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ЧЦ=9", layout.elementNamed("Код").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A document number takes the number type and the length, not the name Номер. */
    @Test
    public void aNumericDocumentNumberTakesTheOwnersType()
    {
        ListNode pattern = OwnerModelReader.codePattern("Number", 11, null); //$NON-NLS-1$
        FormLayout layout = placed(compose(coded("Document", "Номер", -2, pattern), args("DocumentForm"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("{\"Pattern\",{\"N\",11,0,0}}", flat(layout.elementNamed("Номер"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ЧЦ=11", layout.elementNamed("Номер").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A string code keeps the length and the fixed-length flag, and takes no number format. */
    @Test
    public void aStringCatalogCodeTakesTheOwnersLength()
    {
        ListNode pattern = OwnerModelReader.codePattern("String", 9, null); //$NON-NLS-1$
        FormLayout layout = placed(compose(coded("Catalog", "Код", -2, pattern), args("ItemForm"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("{\"Pattern\",{\"S\",9,0}}", flat(layout.elementNamed("Код"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(layout.elementNamed("Код").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A pattern without qualifiers leaves the field without a format. */
    @Test
    public void aPatternWithoutQualifiersSetsNoFormat()
    {
        OwnerModel owner = OwnerModel.builder("Catalog", "Товары").objectType(OBJ, "CatalogObject.Товары") //$NON-NLS-1$ //$NON-NLS-2$
            .attribute(member("Число", "Число", 80, "{\"Pattern\",{\"N\"}}", "Number")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .attribute(member("Когда", "Когда", 81, "{\"Pattern\",{\"D\"}}", "Date")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .build();
        FormLayout layout = placed(compose(owner, args("ItemForm"))); //$NON-NLS-1$
        assertEquals("{\"Pattern\",{\"N\"}}", flat(layout.elementNamed("Число"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("{\"Pattern\",{\"D\"}}", flat(layout.elementNamed("Когда"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(layout.elementNamed("Число").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(layout.elementNamed("Когда").details.get("format")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A column of several types is written with no control, and the answer names it. */
    @Test
    public void aCompositeColumnHasNoControl()
    {
        FormComposer.Result result = compose(catalog(), args("ItemForm")); //$NON-NLS-1$
        FormLayout layout = placed(result);
        assertTrue(result.plainColumns.toString(), result.plainColumns.contains("Состав.Смешанный")); //$NON-NLS-1$
        FormLayout.Column column = column(layout.elementNamed("Состав"), "Смешанный"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(column.control);
    }

    /** An explicit width outside 500..1000 is refused by that range. */
    @Test
    public void widthOutsideTheRangeIsRefused()
    {
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("width", "400"); //$NON-NLS-1$ //$NON-NLS-2$
        FormComposer.Result result = compose(catalog(), params);
        assertFalse(result.ok);
        assertTrue(result.error, result.error.contains("500") && result.error.contains("1000")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An explicit height outside 350..700 is refused by that range. */
    @Test
    public void heightOutsideTheRangeIsRefused()
    {
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("height", "200"); //$NON-NLS-1$ //$NON-NLS-2$
        FormComposer.Result result = compose(catalog(), params);
        assertFalse(result.ok);
        assertTrue(result.error, result.error.contains("350") && result.error.contains("700")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A synonym longer than 33 characters is repeated in the label's tooltip. */
    @Test
    public void aLongSynonymGoesIntoTheTooltip()
    {
        String synonym = "А".repeat(34); //$NON-NLS-1$
        FormLayout layout = placed(compose(oneField(synonym), args("ItemForm"))); //$NON-NLS-1$
        assertEquals(synonym, layout.elementNamed("НадписьРеквизит").tooltip.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A synonym of 33 characters fits in its label at seven pixels a character. */
    @Test
    public void aLabelOfThirtyThreeCharactersFits()
    {
        FormLayout layout = placed(compose(oneField("А".repeat(33)), args("ItemForm"))); //$NON-NLS-1$ //$NON-NLS-2$
        FormLayout.Element label = layout.elementNamed("НадписьРеквизит"); //$NON-NLS-1$
        assertTrue(label.box[2] - label.box[0] >= 7 * 33);
    }

    /** Every field of a catalog form is at least 150 wide. */
    @Test
    public void everyFieldIsAtLeast150()
    {
        placed(compose(catalog(), args("ItemForm"))); //$NON-NLS-1$
    }

    /** The composed layout serializes twice to the same bytes and the reader keeps nothing back. */
    @Test
    public void theLayoutRoundTrips()
    {
        placed(compose(catalog(), args("ItemForm"))); //$NON-NLS-1$
    }

    /** Номер and Дата are the first two fields of a document. */
    @Test
    public void documentNumberAndDateComeFirst()
    {
        purposeDocumentForm();
    }

    /** A catalog list shows Код and then Наименование. */
    @Test
    public void listColumnsOfACatalog()
    {
        FormLayout layout = placed(compose(catalog(), args("ListForm"))); //$NON-NLS-1$
        FormLayout.Element table = layout.elementNamed(MainAttributes.of("Catalog", MainAttributes.Purpose.LIST).name); //$NON-NLS-1$
        assertEquals("Код", table.columns.get(0).name); //$NON-NLS-1$
        assertEquals("Наименование", table.columns.get(1).name); //$NON-NLS-1$
    }

    /** A document list shows Дата and then Номер. */
    @Test
    public void listColumnsOfADocument()
    {
        FormLayout layout = placed(compose(document(1), args("ListForm"))); //$NON-NLS-1$
        FormLayout.Element table = layout.elementNamed(MainAttributes.of("Document", MainAttributes.Purpose.LIST).name); //$NON-NLS-1$
        assertEquals("Дата", table.columns.get(0).name); //$NON-NLS-1$
        assertEquals("Номер", table.columns.get(1).name); //$NON-NLS-1$
    }

    /** A register list shows dimensions and then resources, not the record's own attribute. */
    @Test
    public void listColumnsOfARegister()
    {
        FormLayout layout = placed(compose(register(), args("ListForm"))); //$NON-NLS-1$
        FormLayout.Element table = layout.elementNamed(MainAttributes.of("InformationRegister", MainAttributes.Purpose.LIST).name); //$NON-NLS-1$
        assertEquals(List.of("Номенклатура", "ВидЦен", "Цена"), names(table)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** A record set adds the record's own attributes after the resources. */
    @Test
    public void aRecordSetListsAttributesToo()
    {
        FormLayout layout = placed(compose(register(), args("RecordSetForm"))); //$NON-NLS-1$
        FormLayout.Element table = layout.elementNamed(MainAttributes.of("InformationRegister", MainAttributes.Purpose.RECORD_SET).name); //$NON-NLS-1$
        assertEquals(List.of("Номенклатура", "ВидЦен", "Цена", "Комментарий"), names(table)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** Two attributes of one name are an error of the operation, and nothing is written. */
    @Test
    public void anEditorRefusalIsAnError()
    {
        OwnerModel.Builder builder = OwnerModel.builder("Catalog", "Товары").objectType(OBJ, "CatalogObject.Товары"); //$NON-NLS-1$ //$NON-NLS-2$
        builder.attribute(member("Артикул", "Артикул", 1, "{\"Pattern\",{\"S\"}}", "String")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        builder.attribute(member("Артикул", "Артикул", 2, "{\"Pattern\",{\"S\"}}", "String")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Files files = new Files();
        Model model = new Model();
        Map<String, Object> answer = OrdinaryFormCreation.create(builder.build(), args("ItemForm"), files, model, new Marker()); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertNotNull(answer.get("error")); //$NON-NLS-1$
        assertFalse(files.writeCalled);
        assertFalse(model.commitCalled);
    }

    /** Neighbours of one parent and one page do not overlap, and every box stays inside its parent. */
    @Test
    public void neighborsDoNotOverlap()
    {
        placed(compose(document(2), args("DocumentForm"))); //$NON-NLS-1$
    }

    /** A kind with no ordinary form is refused before any owner is read. */
    @Test
    public void describeOwnerRefusesAKindWithoutForms()
    {
        Map<String, Object> answer = ComposerOperations.describeOwner("ChartOfAccounts.Main", null); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), String.valueOf(answer.get("error")).contains("ChartOfAccounts")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A type that owns no forms is refused by name. */
    @Test
    public void describeOwnerRefusesAnUnknownType()
    {
        Map<String, Object> answer = ComposerOperations.describeOwner("Nope.Something", null); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), String.valueOf(answer.get("error")).contains("not a metadata type")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A missing owner of a kind that does have forms is named as not found. */
    @Test
    public void describeOwnerNamesAMissingOwner()
    {
        Map<String, Object> answer = ComposerOperations.describeOwner("Catalog.Missing", null); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, answer.get("success")); //$NON-NLS-1$
        assertTrue(String.valueOf(answer.get("error")), String.valueOf(answer.get("error")).contains("not found")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** columns is 1, 2 or auto. */
    @Test
    public void columnsMustBeNamed()
    {
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("columns", "3"); //$NON-NLS-1$ //$NON-NLS-2$
        FormComposer.Result result = compose(catalog(), params);
        assertFalse(result.ok);
        assertTrue(result.error, result.error.contains("1, 2 or auto")); //$NON-NLS-1$
    }

    /** tabularSections is pages or below. */
    @Test
    public void tabularSectionsMustBeNamed()
    {
        Map<String, String> params = args("ItemForm"); //$NON-NLS-1$
        params.put("tabularSections", "side"); //$NON-NLS-1$ //$NON-NLS-2$
        FormComposer.Result result = compose(catalog(), params);
        assertFalse(result.ok);
        assertTrue(result.error, result.error.contains("pages or below")); //$NON-NLS-1$
    }

    private static void grows(OwnerModel owner, String purpose, String columns)
    {
        Map<String, String> params = args(purpose);
        params.put("columns", columns); //$NON-NLS-1$
        FormComposer.Result result = compose(owner, params);
        FormLayout layout = placed(result);
        grow(layout, result.width + 300, result.height + 200);
    }

    private static FormLayout placed(FormComposer.Result result)
    {
        assertTrue(result.error, result.ok);
        String text = BraceTree.serialize(result.layout);
        assertFalse(text, text.contains("@")); //$NON-NLS-1$
        ListNode parsed = BraceTree.parse(text);
        assertEquals(text, BraceTree.serialize(parsed));
        FormLayout layout = LayoutReader.read(parsed, "Catalog"); //$NON-NLS-1$
        assertEquals(layout.unread.toString(), 0, layout.unread.size());
        check(layout.elements, result.width, result.height);
        return layout;
    }

    private static void check(List<FormLayout.Element> siblings, int parentWidth, int parentHeight)
    {
        Map<String, List<FormLayout.Element>> pages = new LinkedHashMap<>();
        for (FormLayout.Element element : siblings)
        {
            assertNotNull(element.name, element.box);
            assertTrue(element.name, element.box[0] >= 0 && element.box[1] >= 0);
            assertTrue(element.name + " " + element.box[2] + " of " + parentWidth, element.box[2] <= parentWidth); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(element.name + " " + element.box[3] + " of " + parentHeight, element.box[3] <= parentHeight); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(element.name, element.box[0] < element.box[2] && element.box[1] < element.box[3]);
            if ("Field".equals(element.type) || "CheckBox".equals(element.type) || "SelectField".equals(element.type)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                assertTrue(element.name + " is " + (element.box[2] - element.box[0]), element.box[2] - element.box[0] >= 150); //$NON-NLS-1$
            }
            if ("Label".equals(element.type) && element.title != null) //$NON-NLS-1$
            {
                String caption = element.title.get("ru"); //$NON-NLS-1$
                if (caption != null && caption.endsWith(":") && caption.length() - 1 <= 33) //$NON-NLS-1$
                {
                    assertTrue(element.name, element.box[2] - element.box[0] >= 7 * (caption.length() - 1));
                }
            }
            pages.computeIfAbsent(element.page == null ? "" : element.page, key -> new ArrayList<>()).add(element); //$NON-NLS-1$
            check(element.children, element.box[2] - element.box[0], element.box[3] - element.box[1]);
        }
        for (List<FormLayout.Element> page : pages.values())
        {
            for (int i = 0; i < page.size(); i++)
            {
                for (int j = i + 1; j < page.size(); j++)
                {
                    assertFalse(page.get(i).name + " overlaps " + page.get(j).name, overlaps(page.get(i).box, page.get(j).box)); //$NON-NLS-1$
                }
            }
        }
    }

    private static boolean overlaps(int[] a, int[] b)
    {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    private static void grow(FormLayout layout, int width, int height)
    {
        Map<String, int[]> boxes = new LinkedHashMap<>();
        for (FormLayout.Element element : layout.allElements())
        {
            boxes.put(element.name, element.box.clone());
        }
        for (int pass = 0; pass < 8; pass++)
        {
            for (FormLayout.Element element : layout.allElements())
            {
                int[] box = boxes.get(element.name);
                for (Map.Entry<String, Object> anchor : anchors(element).entrySet())
                {
                    String value = String.valueOf(anchor.getValue());
                    if ("none".equals(value) || "null".equals(value)) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        continue;
                    }
                    int offset = 0;
                    Matcher matcher = OFFSET.matcher(value);
                    String target = value;
                    if (matcher.matches())
                    {
                        target = matcher.group(1);
                        offset = Integer.parseInt(matcher.group(2));
                    }
                    int edge = edge(anchor.getKey());
                    int base;
                    if ("form".equals(target)) //$NON-NLS-1$
                    {
                        base = edge == 0 || edge == 1 ? 0 : edge == 2 ? width : height;
                    }
                    else
                    {
                        int dot = target.lastIndexOf('.');
                        assertTrue(value, dot > 0);
                        int[] other = boxes.get(target.substring(0, dot));
                        assertNotNull(value, other);
                        base = other[edge(target.substring(dot + 1))];
                    }
                    box[edge] = base + offset;
                }
            }
        }
        Map<String, FormLayout.Element> byName = new LinkedHashMap<>();
        for (FormLayout.Element element : layout.allElements())
        {
            element.box = boxes.get(element.name);
            byName.put(element.name, element);
        }
        check(layout.elements, width, height);
        absolute(layout.elements, 0, 0, width, height);
        assertFalse(byName.isEmpty());
    }

    private static void absolute(List<FormLayout.Element> elements, int originX, int originY, int formWidth, int formHeight)
    {
        for (FormLayout.Element element : elements)
        {
            int left = originX + element.box[0];
            int top = originY + element.box[1];
            int right = originX + element.box[2];
            int bottom = originY + element.box[3];
            assertTrue(element.name + " at " + right + "," + bottom, left >= 0 && top >= 0 && right <= formWidth && bottom <= formHeight); //$NON-NLS-1$ //$NON-NLS-2$
            absolute(element.children, left, top, formWidth, formHeight);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> anchors(FormLayout.Element element)
    {
        Object value = element.details.get("anchors"); //$NON-NLS-1$
        return value instanceof Map ? (Map<String, Object>)value : Map.of();
    }

    private static int edge(String name)
    {
        switch (name)
        {
        case "left": //$NON-NLS-1$
            return 0;
        case "top": //$NON-NLS-1$
            return 1;
        case "right": //$NON-NLS-1$
            return 2;
        case "bottom": //$NON-NLS-1$
            return 3;
        default:
            throw new AssertionError(name);
        }
    }

    private static FormComposer.Result compose(OwnerModel owner, Map<String, String> params)
    {
        return FormComposer.compose(owner, params);
    }

    private static Map<String, String> args(String purpose)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("name", "ФормаЭлемента"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("purpose", purpose); //$NON-NLS-1$
        params.put("language", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        return params;
    }

    private static OwnerModel catalog()
    {
        return OwnerModel.builder("Catalog", "Товары") //$NON-NLS-1$
            .objectType(OBJ, "CatalogObject.Товары") //$NON-NLS-1$
            .listType(LIST, "CatalogList.Товары") //$NON-NLS-1$
            .refType(REF, "CatalogRef.Товары") //$NON-NLS-1$
            .standard(standard("Код", -2, "{\"Pattern\",{\"S\"}}", "String", false)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .standard(standard("Наименование", -3, "{\"Pattern\",{\"S\"}}", "String", false)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .standard(standard("Родитель", -4, "{\"Pattern\",{\"#\"," + REF + "}}", "CatalogRef.Товары", true)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .attribute(member("Артикул", "Артикул", 1, "{\"Pattern\",{\"S\",10,1}}", "String(10)")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .attribute(member("Цена", "Цена", 2, "{\"Pattern\",{\"N\",15,2,0}}", "Number(15,2)")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .attribute(member("Дата", "Дата", 3, "{\"Pattern\",{\"D\",\"D\"}}", "Date")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .attribute(member("Флаг", "Флаг", 4, "{\"Pattern\",{\"B\"}}", "Boolean")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .attribute(member("Поставщик", "Поставщик", 5, "{\"Pattern\",{\"#\"," + REF + "}}", "CatalogRef.Контрагенты")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .section(section("Состав", "Состав", 6, ROW, "CatalogTabularSectionObject.Товары.Состав", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(member("Номенклатура", "Номенклатура", 7, "{\"Pattern\",{\"#\"," + REF + "}}", "CatalogRef.Номенклатура"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    member("Количество", "Количество", 8, "{\"Pattern\",{\"N\",10,0,0}}", "Number"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    member("Смешанный", "Смешанный", 9, "{\"Pattern\",{\"S\",10,1},{\"N\",10,0,0}}", "String, Number")))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .build();
    }

    private static OwnerModel document(int sections)
    {
        OwnerModel.Builder builder = OwnerModel.builder("Document", "Заказ") //$NON-NLS-1$
            .objectType(OBJ, "DocumentObject.Заказ") //$NON-NLS-1$
            .listType(LIST, "DocumentList.Заказ"); //$NON-NLS-1$
        builder.standard(standard("Номер", -2, "{\"Pattern\",{\"S\"}}", "String", false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        builder.standard(standard("Дата", -3, "{\"Pattern\",{\"D\",\"DT\"}}", "DateTime", false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        builder.standard(new OwnerModel.Attribute("Проведен", "Проведен", null, null, false, null, "Boolean")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (int i = 0; i < 10; i++)
        {
            builder.attribute(member("Реквизит" + i, "Реквизит " + i, 20 + i, "{\"Pattern\",{\"S\"}}", "String")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        String[] names = { "Товары", "Услуги", "Оплата" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (int i = 0; i < sections; i++)
        {
            builder.section(section(names[i], names[i], 30 + i, ROW, "DocumentTabularSectionObject.Заказ." + names[i], //$NON-NLS-1$
                List.of(member("Сумма", "Сумма", 40 + i, "{\"Pattern\",{\"N\",15,2,0}}", "Number")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        return builder.build();
    }

    private static OwnerModel register()
    {
        return OwnerModel.builder("InformationRegister", "Цены") //$NON-NLS-1$
            .listType(LIST, "InformationRegisterList.Цены") //$NON-NLS-1$
            .recordType(REC, "InformationRegisterRecordManager.Цены") //$NON-NLS-1$
            .recordSetType(SET, "InformationRegisterRecordSet.Цены") //$NON-NLS-1$
            .standard(standard("Период", -2, "{\"Pattern\",{\"D\"}}", "Date", false)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .dimension(member("Номенклатура", "Номенклатура", 50, "{\"Pattern\",{\"#\"," + REF + "}}", "CatalogRef.Номенклатура")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .dimension(member("ВидЦен", "Вид цен", 51, "{\"Pattern\",{\"S\"}}", "String")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .resource(member("Цена", "Цена", 52, "{\"Pattern\",{\"N\",15,2,0}}", "Number(15,2)")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .attribute(member("Комментарий", "Комментарий", 53, "{\"Pattern\",{\"S\"}}", "String")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .build();
    }

    private static OwnerModel field(String name, ListNode pattern)
    {
        return OwnerModel.builder("Catalog", "Товары").objectType(OBJ, "CatalogObject.Товары") //$NON-NLS-1$ //$NON-NLS-2$
            .attribute(new OwnerModel.Attribute(name, name, "aaaaaaaa-bbbb-cccc-dddd-000000000099", null, false, pattern, name)) //$NON-NLS-1$
            .build();
    }

    private static OwnerModel coded(String kind, String name, int code, ListNode pattern)
    {
        String produced = "Document".equals(kind) ? "DocumentObject.Владелец" : "CatalogObject.Владелец"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return OwnerModel.builder(kind, "Владелец").objectType(OBJ, produced) //$NON-NLS-1$
            .standard(new OwnerModel.Attribute(name, name, null, Integer.valueOf(code), false, pattern, name)).build();
    }

    private static String flat(FormLayout.Element element)
    {
        assertNotNull(element);
        assertNotNull(element.pattern);
        return BraceTree.serialize(element.pattern.node()).replace("\r", "").replace("\n", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static OwnerModel oneField(String synonym)
    {
        return OwnerModel.builder("Catalog", "Товары").objectType(OBJ, "CatalogObject.Товары") //$NON-NLS-1$ //$NON-NLS-2$
            .attribute(member("Реквизит", synonym, 70, "{\"Pattern\",{\"S\"}}", "String")).build(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static OwnerModel.Attribute member(String name, String synonym, int index, String pattern, String typeText)
    {
        return new OwnerModel.Attribute(name, synonym, String.format(Locale.ROOT, "aaaaaaaa-bbbb-cccc-dddd-%012d", Integer.valueOf(index)), //$NON-NLS-1$
            null, false, BraceTree.parse(pattern), typeText);
    }

    private static OwnerModel.Attribute standard(String name, int code, String pattern, String typeText, boolean folderOnly)
    {
        return new OwnerModel.Attribute(name, name, null, Integer.valueOf(code), folderOnly, BraceTree.parse(pattern), typeText);
    }

    private static OwnerModel.TabularSection section(String name, String synonym, int index, String rowUuid, String rowName,
        List<OwnerModel.Attribute> attributes)
    {
        return new OwnerModel.TabularSection(name, synonym, String.format(Locale.ROOT, "aaaaaaaa-bbbb-cccc-dddd-%012d", //$NON-NLS-1$
            Integer.valueOf(index)), rowUuid, rowName, attributes);
    }

    private static FormLayout.Column column(FormLayout.Element table, String name)
    {
        for (FormLayout.Column column : table.columns)
        {
            if (name.equals(column.name))
            {
                return column;
            }
        }
        return null;
    }

    private static List<String> names(FormLayout.Element table)
    {
        List<String> names = new ArrayList<>();
        for (FormLayout.Column column : table.columns)
        {
            names.add(column.name);
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static String nameAt(Map<String, Object> described, String key, int index)
    {
        List<Map<String, Object>> list = (List<Map<String, Object>>)described.get(key);
        return String.valueOf(list.get(index).get("name")); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static String typeAt(Map<String, Object> described, String key, int index)
    {
        List<Map<String, Object>> list = (List<Map<String, Object>>)described.get(key);
        return String.valueOf(list.get(index).get("type")); //$NON-NLS-1$
    }

    /** A container that records what the write asked of it. */
    private static final class Files implements OrdinaryFormCreation.ContainerWriter
    {
        boolean exists;

        boolean writeCalled;

        boolean failWrite;

        boolean failRemove;

        byte[] written;

        final String orphan = "src/Catalogs/Товары/Forms/ФормаЭлемента"; //$NON-NLS-1$

        @Override
        public boolean directoryExists(String relativeDirectory)
        {
            return exists;
        }

        @Override
        public void write(String relativeFile, byte[] bytes) throws IOException
        {
            writeCalled = true;
            if (failWrite)
            {
                throw new IOException("disk full"); //$NON-NLS-1$
            }
            written = bytes;
            exists = true;
        }

        @Override
        public String removeCreated()
        {
            if (failRemove)
            {
                return orphan;
            }
            exists = false;
            written = null;
            return null;
        }
    }

    /** A model entry that can refuse the transaction. */
    private static final class Model implements OrdinaryFormCreation.ModelWriter
    {
        boolean commitCalled;

        boolean failCommit;

        @Override
        public String nameTaken(String formName)
        {
            return null;
        }

        @Override
        public String commit(String formName, String language, String synonym, boolean setAsDefault, String defaultSetter)
        {
            commitCalled = true;
            return failCommit ? "transaction refused" : null; //$NON-NLS-1$
        }
    }

    /** A baseline mark that can fail. */
    private static final class Marker implements OrdinaryFormCreation.DeliveryMarker
    {
        boolean called;

        boolean fail;

        @Override
        public String mark() throws Exception
        {
            called = true;
            if (fail)
            {
                throw new IOException("baseline locked"); //$NON-NLS-1$
            }
            return "marked"; //$NON-NLS-1$
        }
    }
}
