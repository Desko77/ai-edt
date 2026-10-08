/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.mdreport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.function.Function;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

import ru.aiedt.mcp.server.support.BmSupportRegistryHelper;

/**
 * {@code get_metadata_details} names the support mode of the object and of each of its forms where the
 * support service holds one, names the state of a project that is on nobody's support, and writes
 * nothing about support where the service itself is out of reach.
 */
public class ASupportModeIsShownWhereTheObjectHasOneTest
{
    private static final String SUPPORT_MODE = "Support Mode"; //$NON-NLS-1$

    /**
     * A catalog with two forms.
     *
     * @return the catalog
     */
    private static Catalog catalog()
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Goods"); //$NON-NLS-1$
        CatalogForm item = MdClassFactory.eINSTANCE.createCatalogForm();
        item.setName("ItemForm"); //$NON-NLS-1$
        catalog.getForms().add(item);
        CatalogForm list = MdClassFactory.eINSTANCE.createCatalogForm();
        list.setName("ListForm"); //$NON-NLS-1$
        catalog.getForms().add(list);
        return catalog;
    }

    /**
     * @param catalog the catalog
     * @param modes the support modes, or <code>null</code> for a project not on support
     * @return the markdown
     */
    private static String details(Catalog catalog, Function<MdObject, String> modes)
    {
        return MetadataFormatter.format(catalog, false, "ru", null, false, modes); //$NON-NLS-1$
    }

    /** The object's mode is a property row, and each form's mode a column of the forms table. */
    @Test
    public void anObjectOnSupportNamesItsModeAndItsFormsModes()
    {
        String markdown = details(catalog(), object -> {
            if (object instanceof Catalog)
            {
                return "ChangesNotAllowed"; //$NON-NLS-1$
            }
            return "ItemForm".equals(object.getName()) ? "ChangesAllowed" : null; //$NON-NLS-1$ //$NON-NLS-2$
        });

        assertTrue(markdown, markdown.contains("| Support Mode | ChangesNotAllowed |")); //$NON-NLS-1$
        assertTrue(markdown, markdown.contains("| Name | Synonym | Form Type | Support Mode |")); //$NON-NLS-1$
        assertTrue(markdown, markdown.contains("| ChangesAllowed |")); //$NON-NLS-1$
        for (String line : markdown.split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith("| ListForm |")) //$NON-NLS-1$
            {
                assertTrue(line, line.endsWith("| - |")); //$NON-NLS-1$
            }
        }
    }

    /** An object the support service holds no mode for says nothing about support. */
    @Test
    public void anObjectWithoutAModeSaysNothingAboutSupport()
    {
        String markdown = details(catalog(), object -> null);

        assertFalse(markdown, markdown.contains(SUPPORT_MODE));
        assertTrue(markdown, markdown.contains("| Name | Synonym | Form Type |")); //$NON-NLS-1$
    }

    /** A project not on support says nothing about support. */
    @Test
    public void aProjectNotOnSupportSaysNothingAboutSupport()
    {
        String markdown = details(catalog(), null);

        assertFalse(markdown, markdown.contains(SUPPORT_MODE));
    }

    /** A project on nobody's support names that state instead of failing the read. */
    @Test
    public void aProjectOnNobodysSupportNamesTheState()
    {
        String markdown = details(catalog(), object -> BmSupportRegistryHelper.NOT_ON_SUPPORT);

        assertTrue(markdown, markdown.contains("| Support Mode | not on support |")); //$NON-NLS-1$
        assertFalse(markdown, markdown.contains("unreadable")); //$NON-NLS-1$
    }

    /** No project, no modes. */
    @Test
    public void noProjectHasNoModes()
    {
        assertNull(BmSupportRegistryHelper.userModes(null));
    }
}
