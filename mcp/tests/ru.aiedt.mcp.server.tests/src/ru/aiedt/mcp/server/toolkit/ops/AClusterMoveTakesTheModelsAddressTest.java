/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.ops.ClusterAdminFacadeTool.ObjectCheck;

/**
 * The address {@code cluster_admin} stores for an object is the one the Navigator builds for it,
 * whatever spelling the call used, and an address the Navigator never builds is refused.
 * <p>
 * The check runs on a configuration built in memory, so the rule is tested without a project.
 * </p>
 */
public class AClusterMoveTakesTheModelsAddressTest
{
    /**
     * @return a configuration holding one catalog, {@code Products}
     */
    private static Configuration configurationWithProducts()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog products = MdClassFactory.eINSTANCE.createCatalog();
        products.setName("Products"); //$NON-NLS-1$
        configuration.getCatalogs().add(products);
        return configuration;
    }

    /**
     * @param check a refused check
     * @return the reason code the refusal carries
     */
    private static String reasonOf(ObjectCheck check)
    {
        return JsonParser.parseString(check.refusal.toJson()).getAsJsonObject().get("reason") //$NON-NLS-1$
            .getAsString();
    }

    /** Case, plural and the Russian type name all come back as the Navigator's address. */
    @Test
    public void everySpellingComesBackAsTheNavigatorsAddress()
    {
        Configuration configuration = configurationWithProducts();
        String[] spellings = {"Catalog.Products", "catalog.products", "Catalogs.PRODUCTS", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.Products", //$NON-NLS-1$
            " Catalog.Products "}; //$NON-NLS-1$
        for (String spelling : spellings)
        {
            ObjectCheck check = ClusterAdminFacadeTool.checkAgainst(configuration, spelling, "Catalog"); //$NON-NLS-1$
            assertNull(spelling, check.refusal);
            assertEquals(spelling, "Catalog.Products", check.fqn); //$NON-NLS-1$
        }
    }

    /** A nested address names no object a cluster can hold, even when its owner exists. */
    @Test
    public void aNestedAddressIsRefused()
    {
        ObjectCheck check = ClusterAdminFacadeTool.checkAgainst(configurationWithProducts(),
            "Catalog.Products.Attribute.Missing", "Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(check.fqn);
        assertNotNull(check.refusal);
        assertEquals("objectNotFound", reasonOf(check)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An object the configuration does not hold is refused. */
    @Test
    public void aMissingObjectIsRefused()
    {
        ObjectCheck check = ClusterAdminFacadeTool.checkAgainst(configurationWithProducts(),
            "Catalog.Goods", "Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(check.fqn);
        assertEquals("objectNotFound", reasonOf(check)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An object of another collection than the cluster's is refused, whatever its spelling. */
    @Test
    public void anObjectOfAnotherCollectionIsRefused()
    {
        ObjectCheck check = ClusterAdminFacadeTool.checkAgainst(configurationWithProducts(),
            "catalogs.products", "Document"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(check.fqn);
        assertEquals("outsideCollection", reasonOf(check)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Without a collection to match, any existing top-level object is accepted. */
    @Test
    public void withoutACollectionAnyObjectIsAccepted()
    {
        ObjectCheck check = ClusterAdminFacadeTool.checkAgainst(configurationWithProducts(),
            "catalog.PRODUCTS", null); //$NON-NLS-1$
        assertEquals("Catalog.Products", check.fqn); //$NON-NLS-1$
    }
}
