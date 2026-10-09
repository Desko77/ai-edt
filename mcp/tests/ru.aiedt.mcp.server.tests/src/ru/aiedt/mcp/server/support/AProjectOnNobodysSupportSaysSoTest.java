/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.function.Function;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * A project on nobody's support says so about its objects, and a read that fails on a project which
 * IS on support keeps the name of what failed.
 * <p>
 * The support object is handed out for a project written from scratch as well, and reading the mode
 * of an object of such a project throws: every cell of the support column then said
 * <code>unreadable: RuntimeException</code> - a broken tool rather than a project with no support.
 * The vendor configurations the project names are what tells the two apart.
 * </p>
 */
public class AProjectOnNobodysSupportSaysSoTest
{
    /**
     * A catalog with one form, for the rendering half of the answer.
     *
     * @return the catalog
     */
    private static Catalog catalog()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        catalog.getForms().add(MdClassFactory.eINSTANCE.createCatalogForm());
        return catalog;
    }

    /** A read that throws whatever it is handed, which is what the live call met. */
    private static Function<MdObject, String> throwing()
    {
        return object -> {
            throw new RuntimeException("the object is not in the support registry"); //$NON-NLS-1$
        };
    }

    /** A project that names no vendor is on nobody's support, and says so without reading. */
    @Test
    public void aProjectWithNoVendorNamesItsSupportState()
    {
        Function<MdObject, String> modes =
            BmSupportRegistryHelper.supportModes(throwing(), Boolean.FALSE);

        assertEquals(BmSupportRegistryHelper.NOT_ON_SUPPORT, modes.apply(catalog()));
        assertEquals("not on support", BmSupportRegistryHelper.NOT_ON_SUPPORT); //$NON-NLS-1$
    }

    /** A project that names a vendor keeps the mode the registry holds. */
    @Test
    public void aProjectWithAVendorKeepsItsMode()
    {
        Function<MdObject, String> modes =
            BmSupportRegistryHelper.supportModes(object -> "ChangesNotAllowed", Boolean.TRUE); //$NON-NLS-1$

        assertEquals("ChangesNotAllowed", modes.apply(catalog())); //$NON-NLS-1$
    }

    /** A read that fails under a named vendor names the exception and its message. */
    @Test
    public void aFailingReadUnderAVendorNamesTheFailure()
    {
        Function<MdObject, String> modes =
            BmSupportRegistryHelper.supportModes(throwing(), Boolean.TRUE);

        assertEquals("unreadable: RuntimeException (the object is not in the support registry)", //$NON-NLS-1$
            modes.apply(catalog()));
    }

    /**
     * Whether the project names a vendor could not be asked: the support state was never
     * established, so a failing read is a failure and not a project without support.
     */
    @Test
    public void anUnaskedSupportStateLeavesTheReadAsAFailure()
    {
        Function<MdObject, String> modes = BmSupportRegistryHelper.supportModes(throwing(), null);

        assertTrue(modes.apply(catalog()), modes.apply(catalog()).startsWith("unreadable: ")); //$NON-NLS-1$
    }

    /** An exception with no message of its own still names what failed. */
    @Test
    public void aFailureWithoutAMessageIsStillNamed()
    {
        Function<MdObject, String> modes = BmSupportRegistryHelper.supportModes(object -> {
            throw new RuntimeException();
        }, Boolean.TRUE);

        assertEquals("unreadable: RuntimeException (RuntimeException)", modes.apply(catalog())); //$NON-NLS-1$
    }

    /** A message carrying a line break does not break the table it is written into. */
    @Test
    public void aMessageWithALineBreakStaysOnOneLine()
    {
        Function<MdObject, String> modes = BmSupportRegistryHelper.supportModes(object -> {
            throw new RuntimeException("first\nsecond"); //$NON-NLS-1$
        }, Boolean.TRUE);

        assertEquals("unreadable: RuntimeException (first second)", modes.apply(catalog())); //$NON-NLS-1$
    }
}
