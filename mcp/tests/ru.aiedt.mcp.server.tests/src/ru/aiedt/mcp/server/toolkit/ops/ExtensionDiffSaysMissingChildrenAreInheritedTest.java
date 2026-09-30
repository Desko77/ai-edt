/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * An adopted object carries only the children the extension adopted. The comparison lists the
 * others under {@code missingFromExtension}, and says next to the lists that the extension inherits
 * them from the base unchanged.
 */
public class ExtensionDiffSaysMissingChildrenAreInheritedTest
{
    /**
     * Builds a catalog with the named attributes.
     *
     * @param attributes the attribute names
     * @return the catalog
     */
    private static Catalog catalog(String... attributes)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName("Товары"); //$NON-NLS-1$
        for (String name : attributes)
        {
            CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
            attribute.setName(name);
            catalog.getAttributes().add(attribute);
        }
        return catalog;
    }

    /** A child the extension did not adopt is listed, and the answer says it is inherited. */
    @Test
    public void aChildNotAdoptedIsListedAsInherited()
    {
        Map<String, Object> body = new LinkedHashMap<>();
        ExtensionDiffTool.compare(body, catalog("Артикул"), catalog("Артикул", "Вес")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("compared", body.get("status")); //$NON-NLS-1$ //$NON-NLS-2$
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) body.get("attributes"); //$NON-NLS-1$
        assertEquals(Arrays.asList("Вес"), (List<?>) attributes.get("missingFromExtension")); //$NON-NLS-1$ //$NON-NLS-2$
        Object note = body.get("missingFromExtensionNote"); //$NON-NLS-1$
        assertNotNull(note);
        assertTrue(String.valueOf(note).contains("inherits")); //$NON-NLS-1$
    }
}
