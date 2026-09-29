/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Which owner property {@code setAsDefault} points at when {@code create_form} runs.
 * <p>
 * The property follows from the form's purpose, and the purpose is one question with one answer:
 * the caller's {@code purpose}, or a legacy {@code formType} that names one, or - when neither is
 * given - the owner type and the form name. Choosing the property from the raw {@code formType}
 * instead meant a caller who gave {@code purpose=ListForm} and no {@code formType} got no property
 * set at all and an answer of success (audit W05 F3).
 * </p>
 */
public class TheDefaultFormSetterFollowsThePurposeTest
{
    private static final String CATALOG = "Catalog.Товары"; //$NON-NLS-1$

    /** The regression: a purpose with no formType has to name its default-form property too. */
    @Test
    public void aPurposeIsEnoughToNameTheProperty()
    {
        assertEquals("defaultListForm", setterFor("ListForm", null, "ФормаЭлемента")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("defaultObjectForm", setterFor("ItemForm", null, "СписокТоваров")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("defaultChoiceForm", setterFor("ChoiceForm", null, "Любая")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("defaultFolderForm", setterFor("FolderForm", null, "Любая")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("defaultFolderChoiceForm", setterFor("FolderChoiceForm", null, "Любая")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("defaultRecordForm", setterFor("RecordForm", null, "Любая")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** The purpose states the form's role, and it outranks a legacy formType saying something else. */
    @Test
    public void anExplicitPurposeOutranksTheLegacyFormType()
    {
        assertEquals("defaultListForm", setterFor("ListForm", "ItemForm", "ФормаЭлемента")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** The older spelling still works: a caller who passes only formType=ItemForm means the object form. */
    @Test
    public void aLegacyFormTypeThatNamesAPurposeIsStillAPurpose()
    {
        assertEquals("defaultObjectForm", setterFor(null, "ItemForm", "ФормаЭлемента")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("defaultListForm", setterFor(null, "ListForm", "ФормаЭлемента")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * {@code MANAGED} and {@code ORDINARY} name the form model, not a purpose. Reading them as one
     * left the property unset even though the owner and the name said what the form was for.
     */
    @Test
    public void aFormModelIsNotAPurpose()
    {
        assertNull(FormCreateOps.purposeToken(null, "MANAGED")); //$NON-NLS-1$
        assertNull(FormCreateOps.purposeToken(null, "ORDINARY")); //$NON-NLS-1$
        assertNull(FormCreateOps.purposeToken(null, null));
        assertNull(FormCreateOps.purposeToken(null, "   ")); //$NON-NLS-1$
        // ... and the question is passed on, so the owner and the form name still answer it.
        assertEquals("defaultObjectForm", setterFor(null, "MANAGED", "ФормаЭлемента")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("defaultListForm", setterFor(null, "ORDINARY", "СписокТоваров")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** With neither a purpose nor a formType, the owner type and the form name decide. */
    @Test
    public void theOwnerAndTheNameDecideWhenNothingIsStated()
    {
        assertEquals("defaultObjectForm", setterFor(null, null, "ФормаЭлемента")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("defaultListForm", setterFor(null, null, "СписокТоваров")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A purpose the owner has no property for has nothing to set, and the caller says so rather than
     * answering success. The two are named here so the answer does not silently turn into a property.
     */
    @Test
    public void purposesWithNoDefaultFormProperty()
    {
        assertNull(setterFor("Generic", null, "Любая")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(setterFor("RecordSetForm", null, "Любая")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(FormCreateOps.defaultFormSetterFor(null));
    }

    private static String setterFor(String purpose, String legacyFormType, String formName)
    {
        return FormCreateOps.defaultFormSetterFor(
            FormCreateOps.formPurposeFor(purpose, legacyFormType, CATALOG, formName));
    }
}
