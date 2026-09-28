/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.form.model.FormField;
import com._1c.g5.v8.dt.form.model.FormGroup;

import ru.aiedt.mcp.server.support.BmFormHelper;

/**
 * What {@code set_form_item_property} answers to a property the item does not have.
 * <p>
 * The refusal used to name the implementation class the EMF runtime happens to use
 * ("Property 'noSuchProp' is absent on FormFieldImpl") and stop there - no hint at
 * which properties the item does take. The object path answered the same question
 * with the model class and the settable names. The form path now gives that same
 * answer, and a property the item has as a containment list (a group's items) is
 * pointed at the item-structure operations instead of being called absent.
 * </p>
 */
public class AnUnknownItemPropertyListsWhatCanBeSetTest
{
    /**
     * An absent property is refused with the model class, the list of settable
     * properties, and no trace of the implementation class name.
     */
    @Test
    public void anAbsentPropertyNamesTheModelClassAndListsTheSettableOnes() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = FormFactory.eINSTANCE.createForm();
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName("InputField1"); //$NON-NLS-1$
        form.getItems().add(field);

        String refusal = helper.setItemProperty(form, "InputField1", "noSuchProp", "x"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull("an absent property is refused", refusal); //$NON-NLS-1$
        assertTrue("the refusal names the model class: " + refusal, //$NON-NLS-1$
            refusal.contains("FormField")); //$NON-NLS-1$
        assertFalse("the implementation class is not the answer: " + refusal, //$NON-NLS-1$
            refusal.contains("Impl")); //$NON-NLS-1$
        assertTrue("the refusal lists the settable properties: " + refusal, //$NON-NLS-1$
            refusal.contains("Settable:")); //$NON-NLS-1$
        assertTrue("a property the field has is in the list: " + refusal, //$NON-NLS-1$
            refusal.contains("readOnly")); //$NON-NLS-1$
    }

    /**
     * A containment list (a group's items) is a property the item has but not a
     * scalar one: the refusal names the operations that manage list contents.
     */
    @Test
    public void aListPropertyNamesTheOperationsThatManageIt() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = FormFactory.eINSTANCE.createForm();
        FormGroup group = FormFactory.eINSTANCE.createFormGroup();
        group.setName("Group1"); //$NON-NLS-1$
        form.getItems().add(group);

        String refusal = helper.setItemProperty(form, "Group1", "items", "x"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull("a list property is refused by the scalar write", refusal); //$NON-NLS-1$
        assertTrue("the refusal points at the item-structure operations: " + refusal, //$NON-NLS-1$
            refusal.contains("remove_form_item")); //$NON-NLS-1$
        assertTrue("the refusal says nothing was changed: " + refusal, //$NON-NLS-1$
            refusal.contains("Nothing was changed")); //$NON-NLS-1$
        assertFalse("the implementation class is not the answer: " + refusal, //$NON-NLS-1$
            refusal.contains("Impl")); //$NON-NLS-1$
    }

    /**
     * The correction text is the only change: a real scalar property still writes.
     */
    @Test
    public void aRealPropertyStillWrites() throws Exception
    {
        BmFormHelper helper = new BmFormHelper();
        assertTrue(helper.init());
        Form form = FormFactory.eINSTANCE.createForm();
        FormField field = FormFactory.eINSTANCE.createFormField();
        field.setName("InputField2"); //$NON-NLS-1$
        form.getItems().add(field);

        String error = helper.setItemProperty(form, "InputField2", "readOnly", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNull("a scalar property writes: " + error, error); //$NON-NLS-1$
        assertTrue("the write reached the model", field.isReadOnly()); //$NON-NLS-1$
    }
}
