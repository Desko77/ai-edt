/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
import com._1c.g5.v8.dt.metadata.mdclass.Document;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdtype.CatalogTypes;
import com._1c.g5.v8.dt.metadata.mdtype.MdRefType;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory;

/**
 * A fill value on a reference type resolves the type's own predefined items, and its empty reference.
 * <p>
 * The attribute's type is {@code CatalogRef.X}; the value that lands in the model is the predefined
 * item object of that catalog (addressed in an .mdo as {@code Catalog.X.Y}), or - for the EmptyRef
 * literal - the EmptyRef object the produced ref type carries ({@code Catalog.X.EmptyRef}). A type
 * with no predefined items says so, and a name two items share is refused rather than guessed.
 * </p>
 */
public class AFillValueNamesAPredefinedItemOrTheEmptyRefTest
{
    private static CatalogPredefinedItem itemNamed(String name)
    {
        CatalogPredefinedItem item = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        item.setName(name);
        return item;
    }

    private static Catalog catalogWithPredefined()
    {
        Catalog warehouses = MdClassFactory.eINSTANCE.createCatalog();
        warehouses.setName("Склады"); //$NON-NLS-1$
        CatalogPredefined predefined = MdClassFactory.eINSTANCE.createCatalogPredefined();
        predefined.getItems().add(itemNamed("Основной")); //$NON-NLS-1$
        predefined.getItems().add(itemNamed("Запасной")); //$NON-NLS-1$
        warehouses.setPredefined(predefined);
        return warehouses;
    }

    private static Configuration configurationWith(Catalog catalog)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.getCatalogs().add(catalog);
        return config;
    }

    private static CatalogAttribute attributeTyped(String typeName)
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Склад"); //$NON-NLS-1$
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(typeName);
        description.getTypes().add(type);
        attribute.setType(description);
        return attribute;
    }

    /** The item name lands as a ReferenceValue holding the catalog's own object. */
    @Test
    public void anItemNameLandsAsTheCatalogOwnObject()
    {
        Catalog warehouses = catalogWithPredefined();
        Configuration config = configurationWith(warehouses);
        CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "Основной", config)); //$NON-NLS-1$

        assertSame(warehouses.getPredefined().getItems().get(0),
            ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** The full address an .mdo carries - the owner kind without Ref - lands as the same object. */
    @Test
    public void theFullAddressLandsAsTheSameObject()
    {
        Catalog warehouses = catalogWithPredefined();
        Configuration config = configurationWith(warehouses);
        CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "Catalog.Склады.Запасной", config)); //$NON-NLS-1$

        assertSame(warehouses.getPredefined().getItems().get(1),
            ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** A name no item carries is refused with the ones the catalog does. */
    @Test
    public void anUnknownItemIsRefused()
    {
        Configuration config = configurationWith(catalogWithPredefined());
        CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "Транзитный", config); //$NON-NLS-1$

        assertTrue(refused.contains("CatalogRef.Склады")); //$NON-NLS-1$
        assertNull(attribute.getFillValue());
    }

    /** A name two items share is refused rather than guessed. */
    @Test
    public void aNameTwoItemsShareIsRefused()
    {
        Catalog warehouses = MdClassFactory.eINSTANCE.createCatalog();
        warehouses.setName("Двойной"); //$NON-NLS-1$
        CatalogPredefined predefined = MdClassFactory.eINSTANCE.createCatalogPredefined();
        predefined.getItems().add(itemNamed("ТотСамый")); //$NON-NLS-1$
        predefined.getItems().add(itemNamed("тотсамый")); //$NON-NLS-1$
        warehouses.setPredefined(predefined);
        Configuration config = configurationWith(warehouses);
        CatalogAttribute attribute = attributeTyped("CatalogRef.Двойной"); //$NON-NLS-1$

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "ТотСамый", config); //$NON-NLS-1$

        assertTrue(refused.contains("more than once")); //$NON-NLS-1$
    }

    /** A type with no predefined items - a document reference - says so. */
    @Test
    public void aTypeWithoutPredefinedItemsSaysSo()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Document invoice = MdClassFactory.eINSTANCE.createDocument();
        invoice.setName("Накладная"); //$NON-NLS-1$
        config.getDocuments().add(invoice);
        CatalogAttribute attribute = attributeTyped("DocumentRef.Накладная"); //$NON-NLS-1$

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "Основной", config); //$NON-NLS-1$

        assertTrue(refused.contains("no predefined items")); //$NON-NLS-1$
    }

    /** The EmptyRef literal lands as the EmptyRef object of the produced ref type. */
    @Test
    public void theEmptyRefLiteralLandsAsTheProducedOne()
    {
        Catalog warehouses = catalogWithPredefined();
        MdTypeFactory types = MdTypeFactory.eINSTANCE;
        MdRefType refType = types.createMdRefType();
        refType.setEmptyRef(types.createEmptyRef());
        CatalogTypes catalogTypes = types.createCatalogTypes();
        catalogTypes.setRefType(refType);
        warehouses.setProducedTypes(catalogTypes);
        Configuration config = configurationWith(warehouses);
        CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "EmptyRef", config)); //$NON-NLS-1$

        assertSame(refType.getEmptyRef(), ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** The Russian spelling of the empty reference lands the same way. */
    @Test
    public void theRussianEmptyRefLiteralLandsToo()
    {
        Catalog warehouses = catalogWithPredefined();
        MdTypeFactory types = MdTypeFactory.eINSTANCE;
        MdRefType refType = types.createMdRefType();
        refType.setEmptyRef(types.createEmptyRef());
        CatalogTypes catalogTypes = types.createCatalogTypes();
        catalogTypes.setRefType(refType);
        warehouses.setProducedTypes(catalogTypes);
        Configuration config = configurationWith(warehouses);
        CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "ПустаяСсылка", config)); //$NON-NLS-1$

        assertSame(refType.getEmptyRef(), ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** A type whose produced ref type carries no EmptyRef on this runtime is refused with the reason. */
    @Test
    public void aMissingProducedEmptyRefIsRefusedWithTheReason()
    {
        Configuration config = configurationWith(catalogWithPredefined());
        CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "EmptyRef", config); //$NON-NLS-1$

        assertTrue(refused.contains("produced types")); //$NON-NLS-1$
    }

    private static CatalogPredefinedItem folderNamed(String name)
    {
        CatalogPredefinedItem folder = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        folder.setName(name);
        folder.setIsFolder(true);
        return folder;
    }

    /** A catalog whose predefined items sit inside folders, one item per folder. */
    private static Catalog catalogWithNestedPredefined()
    {
        Catalog warehouses = MdClassFactory.eINSTANCE.createCatalog();
        warehouses.setName("Склады"); //$NON-NLS-1$
        CatalogPredefined predefined = MdClassFactory.eINSTANCE.createCatalogPredefined();
        predefined.getItems().add(itemNamed("Основной")); //$NON-NLS-1$
        CatalogPredefinedItem retail = folderNamed("Группы"); //$NON-NLS-1$
        retail.getContent().add(itemNamed("Розничные")); //$NON-NLS-1$
        predefined.getItems().add(retail);
        warehouses.setPredefined(predefined);
        return warehouses;
    }

    /** An item inside a folder resolves by its bare name when that name is unique. */
    @Test
    public void aNestedItemNameLandsAsTheCatalogOwnObject()
    {
        Catalog warehouses = catalogWithNestedPredefined();
        Configuration config = configurationWith(warehouses);
        CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "Розничные", config)); //$NON-NLS-1$

        assertSame(warehouses.getPredefined().getItems().get(1).getContent().get(0),
            ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** The full path of a nested item - folder name, then item name - lands as the same object. */
    @Test
    public void theNestedFullPathLandsAsTheSameObject()
    {
        Catalog warehouses = catalogWithNestedPredefined();
        Configuration config = configurationWith(warehouses);
        CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute,
            "Catalog.Склады.Группы.Розничные", config)); //$NON-NLS-1$

        assertSame(warehouses.getPredefined().getItems().get(1).getContent().get(0),
            ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** A name two nested items share is refused with the full paths that disambiguate. */
    @Test
    public void aNameTwoNestedItemsShareIsRefusedWithThePaths()
    {
        Catalog warehouses = MdClassFactory.eINSTANCE.createCatalog();
        warehouses.setName("Склады"); //$NON-NLS-1$
        CatalogPredefined predefined = MdClassFactory.eINSTANCE.createCatalogPredefined();
        CatalogPredefinedItem retail = folderNamed("Розничные"); //$NON-NLS-1$
        retail.getContent().add(itemNamed("Оптовые")); //$NON-NLS-1$
        CatalogPredefinedItem spare = folderNamed("Запасные"); //$NON-NLS-1$
        spare.getContent().add(itemNamed("Оптовые")); //$NON-NLS-1$
        predefined.getItems().add(retail);
        predefined.getItems().add(spare);
        warehouses.setPredefined(predefined);
        Configuration config = configurationWith(warehouses);
        CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "Оптовые", config); //$NON-NLS-1$

        assertTrue(refused.contains("more than once")); //$NON-NLS-1$
        assertTrue("the refusal offers both full paths: " + refused, //$NON-NLS-1$
            refused.contains("Catalog.Склады.Розничные.Оптовые") //$NON-NLS-1$
                && refused.contains("Catalog.Склады.Запасные.Оптовые")); //$NON-NLS-1$
    }

    /** The folder itself is an item too, and resolves like any other. */
    @Test
    public void aFolderNameLandsAsTheFolderObject()
    {
        Catalog warehouses = catalogWithNestedPredefined();
        Configuration config = configurationWith(warehouses);
        CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "Группы", config)); //$NON-NLS-1$

        assertSame(warehouses.getPredefined().getItems().get(1),
            ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** The EmptyRef addressed by its whole owner lands; a beginning of the owner address is refused. */
    @Test
    public void aQualifiedEmptyRefNeedsTheWholeOwnerAddress()
    {
        Catalog warehouses = catalogWithPredefined();
        MdTypeFactory types = MdTypeFactory.eINSTANCE;
        MdRefType refType = types.createMdRefType();
        refType.setEmptyRef(types.createEmptyRef());
        CatalogTypes catalogTypes = types.createCatalogTypes();
        catalogTypes.setRefType(refType);
        warehouses.setProducedTypes(catalogTypes);
        Configuration config = configurationWith(warehouses);

        CatalogAttribute whole = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$
        assertNull(BmDefinedTypeHelper.applyFillValue(whole, "Catalog.Склады.EmptyRef", config)); //$NON-NLS-1$
        assertSame(refType.getEmptyRef(), ((ReferenceValue)whole.getFillValue()).getValue());

        for (String partial : new String[] {"Catalog.EmptyRef", "Catalog.С.EmptyRef", "Catalog.Склад.EmptyRef"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            CatalogAttribute attribute = attributeTyped("CatalogRef.Склады"); //$NON-NLS-1$
            org.junit.Assert.assertNotNull(partial, BmDefinedTypeHelper.applyFillValue(attribute, partial, config));
            assertNull(partial, attribute.getFillValue());
        }
    }
}
