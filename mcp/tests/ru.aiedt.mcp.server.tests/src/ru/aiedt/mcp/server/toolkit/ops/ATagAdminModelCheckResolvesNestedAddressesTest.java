/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The model check of {@code assign_tag}: an address the configuration does not hold is refused,
 * and a nested address - an attribute, a form - resolves the way the marker file spells it.
 * <p>
 * Unlike a cluster, a tag also sits on nested objects, so the walk goes past the second segment
 * instead of stopping there. The rule is proven against a configuration built in memory; the
 * cluster's own check stops at the top level because a cluster holds nothing smaller.
 * </p>
 */
public class ATagAdminModelCheckResolvesNestedAddressesTest
{
    /**
     * @return a configuration holding one catalog with one attribute and one form
     */
    private static Configuration configurationWithProducts()
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog products = MdClassFactory.eINSTANCE.createCatalog();
        products.setName("Products"); //$NON-NLS-1$
        CatalogAttribute code = MdClassFactory.eINSTANCE.createCatalogAttribute();
        code.setName("Code"); //$NON-NLS-1$
        products.getAttributes().add(code);
        CatalogForm itemForm = MdClassFactory.eINSTANCE.createCatalogForm();
        itemForm.setName("ItemForm"); //$NON-NLS-1$
        products.getForms().add(itemForm);
        configuration.getCatalogs().add(products);
        return configuration;
    }

    /**
     * @param check a refused check
     * @return the reason code the refusal carries
     */
    private static String reasonOf(TagAdminFacadeTool.ObjectCheck check)
    {
        return JsonParser.parseString(check.refusal.toJson()).getAsJsonObject().get("reason") //$NON-NLS-1$
            .getAsString();
    }

    /**
     * @param check a refused check
     * @return the refusal's sentence for a person
     */
    private static String messageOf(TagAdminFacadeTool.ObjectCheck check)
    {
        return JsonParser.parseString(check.refusal.toJson()).getAsJsonObject().get("error") //$NON-NLS-1$
            .getAsString();
    }

    /** Case, plural and the Russian type name all come back as the model's own address. */
    @Test
    public void everySpellingComesBackAsTheModelsAddress()
    {
        Configuration configuration = configurationWithProducts();
        String[] spellings = {"Catalog.Products", "catalog.products", "Catalogs.PRODUCTS", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Справочник.Products", //$NON-NLS-1$
            " Catalog.Products "}; //$NON-NLS-1$
        for (String spelling : spellings)
        {
            TagAdminFacadeTool.ObjectCheck check = TagAdminFacadeTool.checkAgainst(configuration,
                spelling);
            assertNull(spelling, check.refusal);
            assertEquals(spelling, "Catalog.Products", check.fqn); //$NON-NLS-1$
        }
    }

    /** A nested attribute address resolves, in the case the model carries. */
    @Test
    public void aNestedAttributeAddressResolves()
    {
        TagAdminFacadeTool.ObjectCheck check = TagAdminFacadeTool
            .checkAgainst(configurationWithProducts(), "catalog.products.attribute.code"); //$NON-NLS-1$
        assertNull(check.refusal);
        assertEquals("Catalog.Products.Attribute.Code", check.fqn); //$NON-NLS-1$
    }

    /** A nested form address resolves the same way. */
    @Test
    public void aNestedFormAddressResolves()
    {
        TagAdminFacadeTool.ObjectCheck check = TagAdminFacadeTool
            .checkAgainst(configurationWithProducts(), "Catalog.Products.Form.ItemForm"); //$NON-NLS-1$
        assertNull(check.refusal);
        assertEquals("Catalog.Products.Form.ItemForm", check.fqn); //$NON-NLS-1$
    }

    /** A nested pair the object does not hold is refused, naming the missing pair. */
    @Test
    public void aMissingNestedPairIsRefused()
    {
        TagAdminFacadeTool.ObjectCheck check = TagAdminFacadeTool.checkAgainst(
            configurationWithProducts(), "Catalog.Products.Attribute.Ghost"); //$NON-NLS-1$
        assertNull(check.fqn);
        assertNotNull(check.refusal);
        assertEquals("objectNotFound", reasonOf(check)); //$NON-NLS-1$
        assertTrue(messageOf(check).contains("Attribute")); //$NON-NLS-1$
        assertTrue(messageOf(check).contains("Ghost")); //$NON-NLS-1$
    }

    /** A child tail that is not kind-name pairs is refused rather than read as the object itself. */
    @Test
    public void anOddChildTailIsRefused()
    {
        TagAdminFacadeTool.ObjectCheck check = TagAdminFacadeTool.checkAgainst(
            configurationWithProducts(), "Catalog.Products.Attribute"); //$NON-NLS-1$
        assertNull(check.fqn);
        assertEquals("objectNotFound", reasonOf(check)); //$NON-NLS-1$
    }

    /** An object the configuration does not hold is refused with the nearest names. */
    @Test
    public void aMissingObjectIsRefusedWithNearestNames()
    {
        TagAdminFacadeTool.ObjectCheck check = TagAdminFacadeTool.checkAgainst(
            configurationWithProducts(), "Catalog.Product"); //$NON-NLS-1$
        assertNull(check.fqn);
        assertEquals("objectNotFound", reasonOf(check)); //$NON-NLS-1$
        assertTrue(messageOf(check), messageOf(check).contains("Products")); //$NON-NLS-1$
    }

    /** An empty or single-segment address is refused as not an address. */
    @Test
    public void anEmptyAddressIsRefused()
    {
        Configuration configuration = configurationWithProducts();
        for (String spelling : new String[] {"", "   ", "Catalog", "Catalog."}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            TagAdminFacadeTool.ObjectCheck check = TagAdminFacadeTool.checkAgainst(configuration,
                spelling);
            assertNull(spelling, check.fqn);
            assertEquals(spelling, "objectNotFound", reasonOf(check)); //$NON-NLS-1$
        }
    }
}
