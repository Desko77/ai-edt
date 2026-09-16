/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * A dependent is named by an address the same tool takes back.
 * <p>
 * The listing used to name a dependent by its class and its own name, so CatalogAttribute.Автор
 * stood for the attribute of any of dozens of catalogs and named none of them - and it was not an
 * address {@code object_mode} could be asked about. On a demonstration configuration that shape
 * covered 6159 of the registry's 9088 names.
 * </p>
 */
public class WhatTheRegistryPrintsItTakesBackTest
{
    private static String nameOf(Object object) throws Exception
    {
        Method method = BmSupportRegistryHelper.class.getDeclaredMethod("nameOf", //$NON-NLS-1$
            com._1c.g5.v8.dt.metadata.mdclass.MdObject.class);
        method.setAccessible(true);
        return (String)method.invoke(null, object);
    }

    /** A child is named through the object that holds it. */
    @Test
    public void aChildIsNamedThroughItsOwner() throws Exception
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Автор"); //$NON-NLS-1$
        catalog.getAttributes().add(attribute);

        assertEquals("Catalog.Товары.Attribute.Автор", nameOf(attribute)); //$NON-NLS-1$
    }

    /** A top-level object keeps the name it had. */
    @Test
    public void anObjectOfItsOwnIsNamedAsBefore() throws Exception
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        assertEquals("Catalog.Товары", nameOf(catalog)); //$NON-NLS-1$
    }

    /** The kind in that address is a kind the resolver knows, or the name cannot be asked about. */
    @Test
    public void theKindOfThatAddressIsOneTheResolverTakes() throws Exception
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Автор"); //$NON-NLS-1$
        catalog.getAttributes().add(attribute);

        String[] parts = nameOf(attribute).split("\\."); //$NON-NLS-1$
        assertEquals(4, parts.length);
        assertTrue("the kind has to be one the child walk knows: " + parts[2], //$NON-NLS-1$
            BmObjectHelper.isChildKind(parts[2]));
    }
}
