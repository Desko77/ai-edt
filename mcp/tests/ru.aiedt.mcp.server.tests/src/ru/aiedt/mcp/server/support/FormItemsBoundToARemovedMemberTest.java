/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.AbstractDataPath;
import com._1c.g5.v8.dt.form.model.DataItem;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.Table;

/**
 * Which form items a removal of an attribute, a tabular section or a column takes with it.
 * <p>
 * An item goes when its data path is the member under one of the form's data roots, or runs below
 * it. A path that only ends in the member's name - a column of the same name in another tabular
 * section, a form attribute named like the member - stays.
 * </p>
 */
public class FormItemsBoundToARemovedMemberTest
{
    /** The member the removals below take away. */
    private static final String GOODS = "Товары"; //$NON-NLS-1$

    @Test
    public void theTabularSectionTakesItsTableAndNothingThatMerelyEndsInItsName()
    {
        Form form = formWithMainAttribute("Объект"); //$NON-NLS-1$
        Table table = table("ТаблицаТовары", "Объект.Товары"); //$NON-NLS-1$ //$NON-NLS-2$
        table.getItems().add(field("ТоварыНоменклатура", "Объект.Товары.Номенклатура")); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(table);
        form.getItems().add(field("ПрочееТовары", "Объект.Прочее.Товары")); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(field("РеквизитФормыТовары", "Товары")); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(field("ТоварыИтог", "Объект.ТоварыИтог")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Collections.singletonList("ТаблицаТовары"), //$NON-NLS-1$
            BmFormCleanupHelper.itemsReferencing(form, GOODS));
        assertEquals(Collections.singletonList("ТаблицаТовары"), //$NON-NLS-1$
            BmFormCleanupHelper.removeItemsReferencing(form, GOODS));
        assertEquals(Arrays.asList("ПрочееТовары", "РеквизитФормыТовары", "ТоварыИтог"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            names(form.getItems()));
    }

    @Test
    public void aColumnTakesOnlyItsOwnField()
    {
        Form form = formWithMainAttribute("Объект"); //$NON-NLS-1$
        Table table = table("ТаблицаТовары", "Объект.Товары"); //$NON-NLS-1$ //$NON-NLS-2$
        table.getItems().add(field("ТоварыНоменклатура", "Объект.Товары.Номенклатура")); //$NON-NLS-1$ //$NON-NLS-2$
        table.getItems().add(field("ТоварыКоличество", "Объект.Товары.Количество")); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(table);

        assertEquals(Collections.singletonList("ТоварыНоменклатура"), //$NON-NLS-1$
            BmFormCleanupHelper.removeItemsReferencing(form, "Товары.Номенклатура")); //$NON-NLS-1$
        assertEquals(Collections.singletonList("ТоварыКоличество"), names(table.getItems())); //$NON-NLS-1$
        assertEquals(Collections.singletonList("ТаблицаТовары"), names(form.getItems())); //$NON-NLS-1$
    }

    /** A main attribute named otherwise is the root the paths start from. */
    @Test
    public void theMainAttributeIsTheRoot()
    {
        Form form = formWithMainAttribute("Запись"); //$NON-NLS-1$
        form.getItems().add(field("Сумма", "Запись.Сумма")); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(field("ОбъектСумма", "Объект.Сумма")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Collections.singletonList("Сумма"), //$NON-NLS-1$
            BmFormCleanupHelper.itemsReferencing(form, "Сумма")); //$NON-NLS-1$
    }

    /** A form that declares no main attribute is read with the object roots of both scripts. */
    @Test
    public void aFormWithoutAMainAttributeIsReadWithTheObjectRoots()
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.getItems().add(field("Английский", "Object.Товары")); //$NON-NLS-1$ //$NON-NLS-2$
        form.getItems().add(field("Русский", "объект.товары")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(Arrays.asList("Английский", "Русский"), //$NON-NLS-1$ //$NON-NLS-2$
            BmFormCleanupHelper.itemsReferencing(form, GOODS));
    }

    /** The refusal lists the items and says nothing was removed. */
    @Test
    public void theRefusalListsWhatReferencesTheMember()
    {
        BmFormCleanupHelper.CleanupResult preview = new BmFormCleanupHelper.CleanupResult();
        preview.removedByForm.put("Catalog.Товары.Form.ФормаЭлемента", //$NON-NLS-1$
            new ArrayList<>(Collections.singletonList("ТаблицаТовары"))); //$NON-NLS-1$

        MetadataGuards.BlockedGuardException refusal =
            BmFormCleanupHelper.requiresCascadeForms(GOODS, preview);

        assertTrue(refusal.verdict.blocked);
        assertEquals(ErrorTags.REQUIRES_CASCADE_FORMS.wire(), refusal.verdict.tag.name);
        assertTrue(refusal.verdict.hint.contains("cascadeForms=true")); //$NON-NLS-1$
        assertTrue(refusal.verdict.hint.contains("Nothing was removed")); //$NON-NLS-1$
        assertTrue(String.valueOf(refusal.verdict.tag.data).contains("ТаблицаТовары")); //$NON-NLS-1$
    }

    /** The three removals read cascadeForms, so the facade passes it to them. */
    @Test
    public void theRemovalsReadCascadeForms()
    {
        Map<String, String> supplied = new LinkedHashMap<>();
        supplied.put("projectName", "P"); //$NON-NLS-1$ //$NON-NLS-2$
        supplied.put("ownerFqn", "Catalog.Товары"); //$NON-NLS-1$ //$NON-NLS-2$
        supplied.put("name", GOODS); //$NON-NLS-1$
        supplied.put("cascadeForms", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String operation : new String[] { "remove_object_attribute", "remove_tabular_section" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            List<String> unread = UnreadArguments.of("EditMetadataTool", operation, supplied); //$NON-NLS-1$
            assertTrue(operation + " leaves unread: " + unread, unread.isEmpty()); //$NON-NLS-1$
        }
        supplied.put("tabularSectionName", GOODS); //$NON-NLS-1$
        List<String> unread =
            UnreadArguments.of("EditMetadataTool", "remove_tabular_section_attribute", supplied); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("remove_tabular_section_attribute leaves unread: " + unread, unread.isEmpty()); //$NON-NLS-1$
    }

    private static Form formWithMainAttribute(String name)
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormAttribute main = FormFactory.eINSTANCE.createFormAttribute();
        main.setName(name);
        main.setMain(true);
        form.getAttributes().add(main);
        return form;
    }

    private static FormField field(String name, String dataPath)
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName(name);
        bind(field, dataPath);
        return field;
    }

    private static Table table(String name, String dataPath)
    {
        Table table = FormFactory.eINSTANCE.createTable();
        table.setName(name);
        bind(table, dataPath);
        return table;
    }

    private static void bind(DataItem item, String dataPath)
    {
        AbstractDataPath path = FormFactory.eINSTANCE.createDataPath();
        path.getSegments().addAll(Arrays.asList(dataPath.split("\\."))); //$NON-NLS-1$
        item.setDataPath(path);
    }

    private static List<String> names(List<? extends FormItem> items)
    {
        List<String> names = new ArrayList<>();
        for (FormItem item : items)
        {
            names.add(item.getName());
        }
        return names;
    }
}
