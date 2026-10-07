/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessorForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * Setting a default-form property to a form by its name or address.
 * <p>
 * The default-form slots hold a form, not a scalar: {@code setDefaultForm} takes the form object and
 * so does {@code setDefaultObjectForm}. A caller has text, so before this the write reached the
 * setter with a String and came back as a refusal about a composite value - an object whose form
 * already had a default form could not be pointed at another one at all. The name is resolved among
 * the forms the owner declares, and a name the owner does not declare - or an address naming another
 * owner's form - is refused with the names that owner has.
 * </p>
 */
public class AFormNamedForADefaultSlotIsResolvedTest
{
    /**
     * @return a data processor named Проверка with two forms, Первая made the default
     */
    private static DataProcessor ownerWithTwoForms()
    {
        DataProcessor owner = MdClassFactory.eINSTANCE.createDataProcessor();
        owner.setName("Проверка"); //$NON-NLS-1$
        DataProcessorForm first = form("Первая"); //$NON-NLS-1$
        DataProcessorForm second = form("Вторая"); //$NON-NLS-1$
        owner.getForms().add(first);
        owner.getForms().add(second);
        owner.setDefaultForm(first);
        return owner;
    }

    /**
     * @param name the form name
     * @return a form of the data processor kind
     */
    private static DataProcessorForm form(String name)
    {
        DataProcessorForm form = MdClassFactory.eINSTANCE.createDataProcessorForm();
        form.setName(name);
        return form;
    }

    /** A slot that already names a form is pointed at another one by its name. */
    @Test
    public void aFormAlreadySetIsReplacedByName()
    {
        DataProcessor owner = ownerWithTwoForms();
        DataProcessorForm second = (DataProcessorForm)owner.getForms().get(1);

        String refusal = BmObjectHelper.setProperty(owner, "defaultForm", "Вторая"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(String.valueOf(refusal), refusal);
        assertSame(second, owner.getDefaultForm());
    }

    /** The same form, named by its address. */
    @Test
    public void aFormIsResolvedByItsAddress()
    {
        DataProcessor owner = ownerWithTwoForms();
        DataProcessorForm second = (DataProcessorForm)owner.getForms().get(1);
        owner.setDefaultForm((DataProcessorForm)owner.getForms().get(0));

        String refusal = BmObjectHelper.setProperty(owner, "defaultForm", //$NON-NLS-1$
            "DataProcessor.Проверка.Form.Вторая"); //$NON-NLS-1$

        assertNull(String.valueOf(refusal), refusal);
        assertSame(second, owner.getDefaultForm());
    }

    /** An address written in Russian names the same owner: the type part is read in both alphabets. */
    @Test
    public void aRussianAddressNamesTheSameOwner()
    {
        DataProcessor owner = ownerWithTwoForms();
        DataProcessorForm second = (DataProcessorForm)owner.getForms().get(1);
        owner.setDefaultForm((DataProcessorForm)owner.getForms().get(0));

        String refusal = BmObjectHelper.setProperty(owner, "defaultForm", //$NON-NLS-1$
            "Обработка.Проверка.Form.Вторая"); //$NON-NLS-1$

        assertNull(String.valueOf(refusal), refusal);
        assertSame(second, owner.getDefaultForm());
    }

    /** A name no form of the owner has is refused with the names it does have. */
    @Test
    public void aNameOfNoSuchFormIsRefusedWithTheFormsThatExist()
    {
        DataProcessor owner = ownerWithTwoForms();
        DataProcessorForm first = (DataProcessorForm)owner.getForms().get(0);

        String refusal = BmObjectHelper.setProperty(owner, "defaultForm", "Третья"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("defaultForm")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Третья")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Первая")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Вторая")); //$NON-NLS-1$
        assertSame("the default form is left as it was", first, owner.getDefaultForm()); //$NON-NLS-1$
    }

    /** A form of another object is not this object's form, and is refused the same way. */
    @Test
    public void aFormOfAnotherOwnerIsRefused()
    {
        DataProcessor owner = ownerWithTwoForms();
        DataProcessorForm first = (DataProcessorForm)owner.getForms().get(0);

        String refusal = BmObjectHelper.setProperty(owner, "defaultForm", //$NON-NLS-1$
            "DataProcessor.Другая.Form.Вторая"); //$NON-NLS-1$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("Вторая")); //$NON-NLS-1$
        assertSame(first, owner.getDefaultForm());
    }

    /** The per-purpose slots of a catalog are resolved the same way. */
    @Test
    public void aPerPurposeSlotIsResolvedToo()
    {
        Catalog owner = MdClassFactory.eINSTANCE.createCatalog();
        owner.setName("Товары"); //$NON-NLS-1$
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("ФормаСписка"); //$NON-NLS-1$
        owner.getForms().add(form);

        String refusal = BmObjectHelper.setProperty(owner, "defaultListForm", "ФормаСписка"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(String.valueOf(refusal), refusal);
        assertSame(form, owner.getDefaultListForm());
    }

    /** A name is read the way the model reads names, without regard to case. */
    @Test
    public void aNameIsResolvedWithoutRegardToCase()
    {
        DataProcessor owner = ownerWithTwoForms();
        DataProcessorForm second = (DataProcessorForm)owner.getForms().get(1);

        String refusal = BmObjectHelper.setProperty(owner, "defaultForm", "вторая"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(String.valueOf(refusal), refusal);
        assertSame(second, owner.getDefaultForm());
    }

    /** An owner that declares no form has nothing to resolve against, and the write is refused. */
    @Test
    public void anOwnerWithoutFormsRefusesTheWrite()
    {
        DataProcessor owner = MdClassFactory.eINSTANCE.createDataProcessor();

        String refusal = BmObjectHelper.setProperty(owner, "defaultForm", "Первая"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("defaultForm")); //$NON-NLS-1$
        assertNull(owner.getDefaultForm());
    }

    /** A scalar property of an owner that declares forms is written as it was, untouched. */
    @Test
    public void aScalarPropertyOfAnOwnerWithFormsIsStillWritten()
    {
        Catalog owner = MdClassFactory.eINSTANCE.createCatalog();
        owner.setName("Товары"); //$NON-NLS-1$
        CatalogForm form = MdClassFactory.eINSTANCE.createCatalogForm();
        form.setName("ФормаСписка"); //$NON-NLS-1$
        owner.getForms().add(form);

        String refusal = BmObjectHelper.setProperty(owner, "name", "Другое"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(String.valueOf(refusal), refusal);
        assertEquals("Другое", owner.getName()); //$NON-NLS-1$
    }
}
