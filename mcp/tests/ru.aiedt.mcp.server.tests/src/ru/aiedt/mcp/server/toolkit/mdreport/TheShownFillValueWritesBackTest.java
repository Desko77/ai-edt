/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.mdreport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefined;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Enum;
import com._1c.g5.v8.dt.metadata.mdclass.EnumValue;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdtype.CatalogTypes;
import com._1c.g5.v8.dt.metadata.mdtype.MdRefType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory;

import ru.aiedt.mcp.server.support.BmDefinedTypeHelper;

/**
 * The fill value the report shows for a reference writes back as the same value.
 * <p>
 * The model here is attached: every referenced object hangs off a Configuration, which is itself an
 * MdObject. The address the formatter builds has to stop below the configuration - a
 * {@code Configuration.<name>.} segment in front is not a spelling the write path accepts, so reading
 * and writing would diverge. Checked for all three reference kinds: an enum value, a predefined item
 * and the empty reference.
 * </p>
 */
public class TheShownFillValueWritesBackTest
{
    private static Configuration attachedModel()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Демо"); //$NON-NLS-1$

        Enum importance = MdClassFactory.eINSTANCE.createEnum();
        importance.setName("Важность"); //$NON-NLS-1$
        for (String valueName : new String[] { "Обычная", "Повышенная" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            EnumValue value = MdClassFactory.eINSTANCE.createEnumValue();
            value.setName(valueName);
            importance.getEnumValues().add(value);
        }
        config.getEnums().add(importance);

        Catalog warehouses = MdClassFactory.eINSTANCE.createCatalog();
        warehouses.setName("Склады"); //$NON-NLS-1$
        CatalogPredefined predefined = MdClassFactory.eINSTANCE.createCatalogPredefined();
        CatalogPredefinedItem main = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        main.setName("Основной"); //$NON-NLS-1$
        predefined.getItems().add(main);
        CatalogPredefinedItem folders = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        folders.setName("Группы"); //$NON-NLS-1$
        folders.setIsFolder(true);
        CatalogPredefinedItem retail = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        retail.setName("Розничные"); //$NON-NLS-1$
        folders.getContent().add(retail);
        predefined.getItems().add(folders);
        warehouses.setPredefined(predefined);

        MdTypeFactory types = MdTypeFactory.eINSTANCE;
        MdRefType refType = types.createMdRefType();
        refType.setEmptyRef(types.createEmptyRef());
        CatalogTypes catalogTypes = types.createCatalogTypes();
        catalogTypes.setRefType(refType);
        warehouses.setProducedTypes(catalogTypes);
        config.getCatalogs().add(warehouses);
        return config;
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

    /** What the report shows for a stored reference, and what writing that back has to land on. */
    private static void shownWritesBack(CatalogAttribute attribute, Object referenced, Configuration config)
    {
        ReferenceValue stored = McoreFactory.eINSTANCE.createReferenceValue();
        stored.setValue((org.eclipse.emf.ecore.EObject)referenced);
        attribute.setFillValue(stored);

        String shown = MetadataFormatter.formatFillValue(stored);
        assertNull("the shown spelling writes back: " + shown, //$NON-NLS-1$
            BmDefinedTypeHelper.applyFillValue(attribute, shown, config));
        assertSame(referenced, ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** An enum value hanging off the configuration reads and writes as one spelling. */
    @Test
    public void anEnumValueUnderAConfigurationWritesBack()
    {
        Configuration config = attachedModel();
        EnumValue usual = config.getEnums().get(0).getEnumValues().get(0);

        assertEquals("Enum.Важность.EnumValue.Обычная", MetadataFormatter.formatFillValue( //$NON-NLS-1$
            referenceTo(usual)));
        shownWritesBack(attributeTyped("EnumRef.Важность"), usual, config); //$NON-NLS-1$
    }

    /** A predefined item hanging off the configuration reads and writes as one spelling. */
    @Test
    public void aPredefinedItemUnderAConfigurationWritesBack()
    {
        Configuration config = attachedModel();
        CatalogPredefinedItem main = config.getCatalogs().get(0).getPredefined().getItems().get(0);

        assertEquals("Catalog.Склады.Основной", MetadataFormatter.formatFillValue( //$NON-NLS-1$
            referenceTo(main)));
        shownWritesBack(attributeTyped("CatalogRef.Склады"), main, config); //$NON-NLS-1$
    }

    /** The empty reference the produced types carry reads and writes as one spelling. */
    @Test
    public void theEmptyRefUnderAConfigurationWritesBack()
    {
        Configuration config = attachedModel();
        Object emptyRef = ((CatalogTypes)config.getCatalogs().get(0).getProducedTypes())
            .getRefType().getEmptyRef();

        assertEquals("Catalog.Склады.EmptyRef", MetadataFormatter.formatFillValue( //$NON-NLS-1$
            referenceTo(emptyRef)));
        shownWritesBack(attributeTyped("CatalogRef.Склады"), emptyRef, config); //$NON-NLS-1$
    }

    /** A predefined item inside a folder reads and writes as one spelling: folder, then item. */
    @Test
    public void aNestedItemUnderAConfigurationWritesBack()
    {
        Configuration config = attachedModel();
        Object nested = config.getCatalogs().get(0).getPredefined().getItems().get(1)
            .getContent().get(0);

        assertEquals("Catalog.Склады.Группы.Розничные", MetadataFormatter.formatFillValue( //$NON-NLS-1$
            referenceTo(nested)));
        shownWritesBack(attributeTyped("CatalogRef.Склады"), nested, config); //$NON-NLS-1$
    }

    private static ReferenceValue referenceTo(Object referenced)
    {
        ReferenceValue reference = McoreFactory.eINSTANCE.createReferenceValue();
        reference.setValue((org.eclipse.emf.ecore.EObject)referenced);
        return reference;
    }
}
