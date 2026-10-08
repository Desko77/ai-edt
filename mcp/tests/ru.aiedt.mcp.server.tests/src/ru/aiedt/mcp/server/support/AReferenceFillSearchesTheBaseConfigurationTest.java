/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefined;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Enum;
import com._1c.g5.v8.dt.metadata.mdclass.EnumValue;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdtype.CatalogTypes;
import com._1c.g5.v8.dt.metadata.mdtype.MdRefType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory;

/**
 * A reference fill resolves the search order it is handed, extension configuration first, base second.
 * <p>
 * An extension attribute may carry a type of the base configuration the extension did not adopt; the
 * enum or the catalog then lives only in the parent configuration. The fill takes the configurations
 * in order and answers with the first one that holds the type - and when both hold the same name,
 * the extension's own object wins.
 * </p>
 */
public class AReferenceFillSearchesTheBaseConfigurationTest
{
    private static Enum anEnumNamed(String name)
    {
        Enum importance = MdClassFactory.eINSTANCE.createEnum();
        importance.setName(name);
        EnumValue usual = MdClassFactory.eINSTANCE.createEnumValue();
        usual.setName("Обычная"); //$NON-NLS-1$
        importance.getEnumValues().add(usual);
        return importance;
    }

    private static CatalogAttribute attributeTyped(String typeName)
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Заполнение"); //$NON-NLS-1$
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(typeName);
        description.getTypes().add(type);
        attribute.setType(description);
        return attribute;
    }

    /** A type only the base configuration holds resolves through the search order. */
    @Test
    public void aBaseOnlyEnumResolvesThroughTheOrder()
    {
        Configuration extension = MdClassFactory.eINSTANCE.createConfiguration();
        Configuration base = MdClassFactory.eINSTANCE.createConfiguration();
        base.getEnums().add(anEnumNamed("Важность")); //$NON-NLS-1$
        CatalogAttribute attribute = attributeTyped("EnumRef.Важность"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "Обычная", //$NON-NLS-1$
            Arrays.asList(extension, base)));

        assertSame(base.getEnums().get(0).getEnumValues().get(0),
            ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** The extension's own object wins when both configurations hold the name. */
    @Test
    public void theExtensionConfigurationWinsTheOrder()
    {
        Configuration extension = MdClassFactory.eINSTANCE.createConfiguration();
        extension.getEnums().add(anEnumNamed("Важность")); //$NON-NLS-1$
        Configuration base = MdClassFactory.eINSTANCE.createConfiguration();
        base.getEnums().add(anEnumNamed("Важность")); //$NON-NLS-1$
        CatalogAttribute attribute = attributeTyped("EnumRef.Важность"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "Обычная", //$NON-NLS-1$
            Arrays.asList(extension, base)));

        assertSame(extension.getEnums().get(0).getEnumValues().get(0),
            ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** The empty reference of a base-only type resolves through the order too. */
    @Test
    public void theEmptyRefOfABaseOnlyTypeResolves()
    {
        Catalog warehouses = MdClassFactory.eINSTANCE.createCatalog();
        warehouses.setName("Склады"); //$NON-NLS-1$
        CatalogPredefined predefined = MdClassFactory.eINSTANCE.createCatalogPredefined();
        warehouses.setPredefined(predefined);
        MdTypeFactory types = MdTypeFactory.eINSTANCE;
        MdRefType refType = types.createMdRefType();
        refType.setEmptyRef(types.createEmptyRef());
        CatalogTypes catalogTypes = types.createCatalogTypes();
        catalogTypes.setRefType(refType);
        warehouses.setProducedTypes(catalogTypes);
        Configuration extension = MdClassFactory.eINSTANCE.createConfiguration();
        Configuration base = MdClassFactory.eINSTANCE.createConfiguration();
        base.getCatalogs().add(warehouses);
        CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "EmptyRef", //$NON-NLS-1$
            Arrays.asList(extension, base)));

        assertSame(refType.getEmptyRef(), ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** A type no configuration of the order holds is refused as not found. */
    @Test
    public void aTypeNowhereInTheOrderIsRefused()
    {
        Configuration extension = MdClassFactory.eINSTANCE.createConfiguration();
        Configuration base = MdClassFactory.eINSTANCE.createConfiguration();
        CatalogAttribute attribute = attributeTyped("EnumRef.Важность"); //$NON-NLS-1$

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "Обычная", //$NON-NLS-1$
            Arrays.asList(extension, base));

        assertTrue(refused.contains("not found")); //$NON-NLS-1$
    }
}
