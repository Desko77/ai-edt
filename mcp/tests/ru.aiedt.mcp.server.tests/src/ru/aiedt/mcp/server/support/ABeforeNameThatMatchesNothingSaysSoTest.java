/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormGroup;

/**
 * Placement before a named sibling answers whether the sibling was there, and a move that missed
 * its sibling says so.
 * <p>
 * {@code addToContainerBefore} used to answer nothing: a name that matched no element silently
 * put the item at the end, and every caller answered a plain success. The boolean answer lets the
 * callers warn; the move route appends its warning to the description the answer carries.
 * </p>
 */
public class ABeforeNameThatMatchesNothingSaysSoTest
{
    private BmFormHelper helper;

    /**
     * Initializes the reflective model access the placement needs.
     */
    @Before
    public void setUp()
    {
        helper = new BmFormHelper();
        assertTrue("the form model classes must be available", helper.init()); //$NON-NLS-1$
    }

    /**
     * A name that matches nothing places the item at the end and answers that no sibling was
     * found.
     *
     * @throws Exception when the list cannot be read
     */
    @Test
    public void aNameThatMatchesNothingPlacesTheItemAtTheEndAndSaysSo() throws Exception
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.getItems().add(namedField("Первый")); //$NON-NLS-1$
        FormField added = namedField("Новый"); //$NON-NLS-1$

        boolean placedBeforeASibling = helper.addToContainerBefore(form, added, "НетТакого"); //$NON-NLS-1$

        assertFalse("no element carries the name, so the item cannot stand before one", //$NON-NLS-1$
            placedBeforeASibling);
        assertSame(added, form.getItems().get(1));
    }

    /**
     * A name that matches places the item in front of its sibling.
     *
     * @throws Exception when the list cannot be read
     */
    @Test
    public void aNameThatMatchesPlacesTheItemBeforeTheSibling() throws Exception
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.getItems().add(namedField("Первый")); //$NON-NLS-1$
        FormField added = namedField("Новый"); //$NON-NLS-1$

        assertTrue(helper.addToContainerBefore(form, added, "Первый")); //$NON-NLS-1$

        assertSame(added, form.getItems().get(0));
    }

    /**
     * A name that differs from the sibling's only in case still matches it: 1C element names are
     * identifiers and carry no case, so the anchor lookup reads them the same way.
     *
     * @throws Exception when the list cannot be read
     */
    @Test
    public void aNameInAnotherCaseStillPlacesTheItemBeforeTheSibling() throws Exception
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.getItems().add(namedField("Товары")); //$NON-NLS-1$
        FormField added = namedField("Новый"); //$NON-NLS-1$

        assertTrue("a name differing only in case names the same element", //$NON-NLS-1$
            helper.addToContainerBefore(form, added, "товары")); //$NON-NLS-1$

        assertSame(added, form.getItems().get(0));
    }

    /**
     * A move that names a missing sibling still moves the item, and its description says the
     * sibling was not found.
     *
     * @throws Exception when the model refuses the move
     */
    @Test
    public void aMoveBeforeAMissingSiblingSaysSo() throws Exception
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup target = FormFactory.eINSTANCE.createFormGroup();
        target.setName("ГруппаКолонок"); //$NON-NLS-1$
        form.getItems().add(target);
        form.getItems().add(namedField("Поле")); //$NON-NLS-1$

        String description = helper.moveItemToContainer(form, "Поле", "ГруппаКолонок", "НетТакого"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(description, description.contains("НетТакого")); //$NON-NLS-1$
        assertTrue(description, description.contains("Warning")); //$NON-NLS-1$
        assertEquals("the item still moved into the target", 1, target.getItems().size()); //$NON-NLS-1$
        assertEquals("only the group remains at the root", 1, form.getItems().size()); //$NON-NLS-1$
    }

    /**
     * A reorder inside the container the item already sits in owes the same warning a move between
     * containers owes: a missing sibling put the item at the end, and the answer has to say so
     * instead of reading as the asked position being taken.
     *
     * @throws Exception when the model refuses the move
     */
    @Test
    public void aReorderBeforeAMissingSiblingSaysSo() throws Exception
    {
        Form form = FormFactory.eINSTANCE.createForm();
        form.getItems().add(namedField("Второй")); //$NON-NLS-1$
        form.getItems().add(namedField("Первый")); //$NON-NLS-1$

        String description = helper.moveItemToContainer(form, "Второй", null, "НетТакого"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(description, description.contains("Warning")); //$NON-NLS-1$
        assertTrue(description, description.contains("НетТакого")); //$NON-NLS-1$
        assertEquals("the item stays in its container", 2, form.getItems().size()); //$NON-NLS-1$
        assertEquals("a missing sibling puts the item at the end", //$NON-NLS-1$
            "Второй", ((FormField)form.getItems().get(1)).getName()); //$NON-NLS-1$
    }

    /**
     * A move that names a present sibling carries no warning.
     *
     * @throws Exception when the model refuses the move
     */
    @Test
    public void aMoveBeforeAPresentSiblingCarriesNoWarning() throws Exception
    {
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup target = FormFactory.eINSTANCE.createFormGroup();
        target.setName("ГруппаКолонок"); //$NON-NLS-1$
        target.getItems().add(namedField("Первый")); //$NON-NLS-1$
        form.getItems().add(target);
        form.getItems().add(namedField("Поле")); //$NON-NLS-1$

        String description = helper.moveItemToContainer(form, "Поле", "ГруппаКолонок", "Первый"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse(description, description.contains("Warning")); //$NON-NLS-1$
        assertEquals("Поле", ((FormField)target.getItems().get(0)).getName()); //$NON-NLS-1$
    }

    /**
     * @param name the element name
     * @return a form field outside any model
     */
    private static FormField namedField(String name)
    {
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName(name);
        return field;
    }
}
