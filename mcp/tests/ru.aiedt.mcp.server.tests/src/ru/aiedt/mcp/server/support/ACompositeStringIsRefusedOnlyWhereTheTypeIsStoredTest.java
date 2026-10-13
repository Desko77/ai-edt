/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.form.model.FormFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogCommand;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * The refusal of an unbounded String inside a type composition follows the carrier, not the write.
 * <p>
 * The platform refuses such a description at the database update, where the type becomes a table
 * column - "Ошибка SDBL: Строка без спецификации длины недопустима". Every type write passes
 * through one builder, and the refusal placed there turned down carriers whose type is never
 * stored: a form attribute, a DCS field or parameter, a command parameter type. Those take the
 * composition and work. These tests pin both sides of the distinction, and the write path with it.
 * </p>
 */
public class ACompositeStringIsRefusedOnlyWhereTheTypeIsStoredTest
{
    /** The composition this test writes: an unbounded String beside a reference type. */
    private static final List<String> COMPOSITION = Arrays.asList("String,CatalogRef.Kinds"); //$NON-NLS-1$

    /**
     * A configuration holding the catalog the composition references - without it the reference
     * type would not resolve and the write would be refused for the wrong reason.
     *
     * @param name the catalog's name
     * @return the configuration
     */
    private static Configuration configurationWithCatalog(String name)
    {
        Configuration configuration = MdClassFactory.eINSTANCE.createConfiguration();
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        configuration.getCatalogs().add(catalog);
        return configuration;
    }

    /**
     * The carriers whose type description becomes a table column.
     */
    @Test
    public void everyFeatureOfADatabaseObjectIsStored()
    {
        // BasicFeature is the base of every attribute of a database object and of a tabular
        // section, of a register dimension, resource and attribute, of an accounting flag and of
        // an addressing attribute.
        assertTrue(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createCatalogAttribute()));
        assertTrue(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createTabularSectionAttribute()));
        assertTrue(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createInformationRegisterDimension()));
        assertTrue(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createInformationRegisterResource()));
        assertTrue(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createCommonAttribute()));
        assertTrue(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createAccountingFlag()));
        assertTrue(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createAddressingAttribute()));

        // These name their one type description on the object itself.
        assertTrue(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createConstant()));
        assertTrue(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createSequenceDimension()));
        // A defined type is stored through the attributes that borrow its composition.
        assertTrue(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createDefinedType()));
    }

    /**
     * The carriers whose type description stays where it was written.
     */
    @Test
    public void aCarrierWhoseTypeNeverReachesATableIsNotStored()
    {
        assertFalse(BmDefinedTypeHelper.typeIsStoredInDatabase(
            FormFactory.eINSTANCE.createFormAttribute()));
        assertFalse(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createCatalogCommand()));
        assertFalse(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createSessionParameter()));
        // An external data source field and function are the table's own, not a configuration
        // column of the object that holds them.
        assertFalse(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createField()));
        assertFalse(BmDefinedTypeHelper.typeIsStoredInDatabase(
            MdClassFactory.eINSTANCE.createFunction()));
        assertFalse(BmDefinedTypeHelper.typeIsStoredInDatabase(
            DcsFactory.eINSTANCE.createDataCompositionSchemaParameter()));
        assertFalse(BmDefinedTypeHelper.typeIsStoredInDatabase(
            DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetField()));
        // No owner at all: nothing to build a column out of.
        assertFalse(BmDefinedTypeHelper.typeIsStoredInDatabase(null));
    }

    /**
     * A stored carrier refuses the composition and writes nothing.
     */
    @Test
    public void aStoredCarrierRefusesTheComposition()
    {
        Configuration configuration = configurationWithCatalog("Kinds"); //$NON-NLS-1$
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Kind"); //$NON-NLS-1$

        BmDefinedTypeHelper.TypesResult refused = BmDefinedTypeHelper.setTypes(attribute,
            (IProject)null, configuration, COMPOSITION, new BmDefinedTypeHelper.QualifierOptions());

        assertFalse(String.valueOf(refused.error), refused.ok);
        assertTrue(String.valueOf(refused.error), refused.error.contains("'length'")); //$NON-NLS-1$
        assertTrue("nothing is written on a refusal", //$NON-NLS-1$
            attribute.getType() == null || attribute.getType().getTypes().isEmpty());

        // A defined type carries the same composition into the attributes that borrow it.
        DefinedType definedType = MdClassFactory.eINSTANCE.createDefinedType();
        definedType.setName("KindOrText"); //$NON-NLS-1$
        BmDefinedTypeHelper.TypesResult refusedOnDefinedType = BmDefinedTypeHelper.setTypes(definedType,
            (IProject)null, configuration, COMPOSITION, new BmDefinedTypeHelper.QualifierOptions());
        assertFalse(String.valueOf(refusedOnDefinedType.error), refusedOnDefinedType.ok);
        assertTrue("nothing is written on a refusal", //$NON-NLS-1$
            definedType.getType() == null || definedType.getType().getTypes().isEmpty());
    }

    /**
     * The carriers that keep the composition, each through its own path.
     */
    @Test
    public void aFormAttributeACommandAndASchemaParameterKeepTheComposition()
    {
        Configuration configuration = configurationWithCatalog("Kinds"); //$NON-NLS-1$

        FormAttribute formAttribute = FormFactory.eINSTANCE.createFormAttribute();
        formAttribute.setName("Kind"); //$NON-NLS-1$
        BmDefinedTypeHelper.TypesResult onForm = BmDefinedTypeHelper.setFormAttributeTypes(formAttribute,
            null, configuration, COMPOSITION, null);
        assertTrue(String.valueOf(onForm.error), onForm.ok);

        CatalogCommand command = MdClassFactory.eINSTANCE.createCatalogCommand();
        command.setName("FillKind"); //$NON-NLS-1$
        BmDefinedTypeHelper.TypesResult onCommand = BmDefinedTypeHelper.setTypes(command, (IProject)null,
            configuration, COMPOSITION, null);
        assertTrue(String.valueOf(onCommand.error), onCommand.ok);

        DataCompositionSchemaParameter parameter =
            DcsFactory.eINSTANCE.createDataCompositionSchemaParameter();
        TypeDescription parameterType = McoreFactory.eINSTANCE.createTypeDescription();
        parameter.setValueType(parameterType);
        BmDefinedTypeHelper.TypesResult onParameter = BmDefinedTypeHelper.setTypesOnDescription(
            parameterType, null, configuration, COMPOSITION, null, parameter);
        assertTrue(String.valueOf(onParameter.error), onParameter.ok);

        DataCompositionSchemaDataSetField field =
            DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetField();
        TypeDescription fieldType = McoreFactory.eINSTANCE.createTypeDescription();
        field.setValueType(fieldType);
        BmDefinedTypeHelper.TypesResult onField = BmDefinedTypeHelper.setTypesOnDescription(
            fieldType, null, configuration, COMPOSITION, null, field);
        assertTrue(String.valueOf(onField.error), onField.ok);
    }

    /**
     * A bounded composition is accepted on a stored carrier as well - the refusal is about the
     * missing length, not about the composition.
     */
    @Test
    public void aStoredCarrierTakesABoundedComposition()
    {
        Configuration configuration = configurationWithCatalog("Kinds"); //$NON-NLS-1$
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Kind"); //$NON-NLS-1$
        BmDefinedTypeHelper.QualifierOptions bounded = new BmDefinedTypeHelper.QualifierOptions();
        bounded.length = Integer.valueOf(20);

        BmDefinedTypeHelper.TypesResult applied = BmDefinedTypeHelper.setTypes(attribute, (IProject)null,
            configuration, COMPOSITION, bounded);

        assertTrue(String.valueOf(applied.error), applied.ok);
        assertFalse(attribute.getType().getTypes().isEmpty());
    }
}
