/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.Report;

/**
 * Which property {@code create_form setAsDefault=true} points at the form it just made.
 * <p>
 * A data processor and a report declare one default form for all of their forms, reached through
 * {@code getDefaultForm}/{@code setDefaultForm}; every other owner keeps a slot per purpose
 * ({@code defaultObjectForm}, {@code defaultListForm}, ...). Writing the purpose-derived name on the
 * first kind named a property the owner does not have, so the call reported a failed write over a
 * slot it had filled itself. Which name to write is decided here, on the model types the platform
 * actually declares.
 * </p>
 */
public class AnOwnerWithOneDefaultFormTakesThatSlotTest
{
    /** A data processor has one slot, and the purpose of the form does not change that. */
    @Test
    public void aDataProcessorTakesItsOnlySlotWhateverThePurposeIs()
    {
        DataProcessor owner = MdClassFactory.eINSTANCE.createDataProcessor();

        assertEquals("defaultForm", FormCreateOps.defaultFormPropertyFor("OBJECT", owner)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("defaultForm", FormCreateOps.defaultFormPropertyFor("LIST", owner)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("defaultForm", FormCreateOps.defaultFormPropertyFor("GENERIC", owner)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A report declares the same single slot. */
    @Test
    public void aReportTakesItsOnlySlot()
    {
        Report owner = MdClassFactory.eINSTANCE.createReport();

        assertEquals("defaultForm", FormCreateOps.defaultFormPropertyFor("OBJECT", owner)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A catalog keeps a slot per purpose and is named by it. */
    @Test
    public void aCatalogTakesTheSlotItsPurposeNames()
    {
        Catalog owner = MdClassFactory.eINSTANCE.createCatalog();

        assertEquals("defaultObjectForm", FormCreateOps.defaultFormPropertyFor("OBJECT", owner)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("defaultListForm", FormCreateOps.defaultFormPropertyFor("LIST", owner)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("defaultChoiceForm", FormCreateOps.defaultFormPropertyFor("CHOICE", owner)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A purpose no owner keeps a slot for still answers with nothing to write. */
    @Test
    public void aPurposeWithoutASlotAnswersNothing()
    {
        assertNull(FormCreateOps.defaultFormPropertyFor("GENERIC", MdClassFactory.eINSTANCE.createCatalog())); //$NON-NLS-1$
        assertNull(FormCreateOps.defaultFormPropertyFor(null, MdClassFactory.eINSTANCE.createCatalog()));
        assertNull(FormCreateOps.defaultFormPropertyFor(null, null));
    }

    /** The slot is one pair: an owner with the getter alone is not an owner with the slot. */
    @Test
    public void onlyOwnersThatDeclareTheSlotAreNamedByIt()
    {
        assertTrue(FormCreateOps.hasSingleDefaultFormSlot(MdClassFactory.eINSTANCE.createDataProcessor()));
        assertTrue(FormCreateOps.hasSingleDefaultFormSlot(MdClassFactory.eINSTANCE.createReport()));
        assertFalse(FormCreateOps.hasSingleDefaultFormSlot(MdClassFactory.eINSTANCE.createCatalog()));
        assertFalse(FormCreateOps.hasSingleDefaultFormSlot(MdClassFactory.eINSTANCE.createDocument()));
        assertFalse(FormCreateOps.hasSingleDefaultFormSlot(null));
    }
}
