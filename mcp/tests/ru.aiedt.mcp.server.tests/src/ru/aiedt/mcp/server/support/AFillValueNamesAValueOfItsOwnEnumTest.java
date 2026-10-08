/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.Enum;
import com._1c.g5.v8.dt.metadata.mdclass.EnumValue;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * A fill value naming an enum value resolves against the enum the attribute's own type names.
 * <p>
 * The attribute's type is {@code EnumRef.X}; the value that lands in the model is the EnumValue
 * object of that enum, addressed in an .mdo as {@code Enum.X.EnumValue.Y}. Both spellings a caller
 * may have read - the bare value name and that full address - are accepted, and a name the enum
 * does not declare is refused with the names it does. Built from the factories, with the enum
 * registered in a configuration the fill resolves against.
 * </p>
 */
public class AFillValueNamesAValueOfItsOwnEnumTest
{
    private static Configuration configuration()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.getEnums().add(anEnumNamed("Важность")); //$NON-NLS-1$
        return config;
    }

    private static Enum anEnumNamed(String name)
    {
        Enum importance = MdClassFactory.eINSTANCE.createEnum();
        importance.setName(name);
        importance.getEnumValues().add(valueNamed("Обычная")); //$NON-NLS-1$
        importance.getEnumValues().add(valueNamed("Повышенная")); //$NON-NLS-1$
        return importance;
    }

    private static EnumValue valueNamed(String name)
    {
        EnumValue value = MdClassFactory.eINSTANCE.createEnumValue();
        value.setName(name);
        return value;
    }

    private static CatalogAttribute attributeTyped(String typeName)
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Importance"); //$NON-NLS-1$
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(typeName);
        description.getTypes().add(type);
        attribute.setType(description);
        return attribute;
    }

    /** The bare value name lands as a ReferenceValue holding the enum's own object. */
    @Test
    public void aBareValueNameLandsAsTheEnumOwnObject()
    {
        Configuration config = configuration();
        CatalogAttribute attribute = attributeTyped("EnumRef.Важность"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "Обычная", config)); //$NON-NLS-1$

        ReferenceValue value = (ReferenceValue)attribute.getFillValue();
        assertSame(config.getEnums().get(0).getEnumValues().get(0), value.getValue());
    }

    /** The full address an .mdo carries lands as the same object. */
    @Test
    public void theFullAddressLandsAsTheSameObject()
    {
        Configuration config = configuration();
        CatalogAttribute attribute = attributeTyped("EnumRef.Важность"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute,
            "Enum.Важность.EnumValue.Повышенная", config)); //$NON-NLS-1$

        assertSame(config.getEnums().get(0).getEnumValues().get(1),
            ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** The name is matched whatever the case. */
    @Test
    public void theNameIsMatchedWhateverTheCase()
    {
        Configuration config = configuration();
        CatalogAttribute attribute = attributeTyped("EnumRef.Важность"); //$NON-NLS-1$

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "ОБЫЧНАЯ", config)); //$NON-NLS-1$

        assertSame(config.getEnums().get(0).getEnumValues().get(0),
            ((ReferenceValue)attribute.getFillValue()).getValue());
    }

    /** A name the enum does not declare is refused with the closest declared names. */
    @Test
    public void anUnknownNameIsRefusedWithTheClosestOnes()
    {
        Configuration config = configuration();
        CatalogAttribute attribute = attributeTyped("EnumRef.Важность"); //$NON-NLS-1$

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "Срочная", config); //$NON-NLS-1$

        assertTrue(refused.contains("EnumRef.Важность")); //$NON-NLS-1$
        assertTrue("the closest declared name is named: " + refused, refused.contains("Обычная")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(attribute.getFillValue());
    }

    /** A name two values share is refused rather than guessed. */
    @Test
    public void aNameTwoValuesShareIsRefused()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Enum ambiguous = anEnumNamed("Двойственно"); //$NON-NLS-1$
        ambiguous.getEnumValues().add(valueNamed("Обычная")); //$NON-NLS-1$
        config.getEnums().add(ambiguous);
        CatalogAttribute attribute = attributeTyped("EnumRef.Двойственно"); //$NON-NLS-1$

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "обычная", config); //$NON-NLS-1$

        assertTrue(refused.contains("more than once")); //$NON-NLS-1$
    }

    /** A composite type is refused as a composite, whatever the value. */
    @Test
    public void aCompositeTypeIsRefused()
    {
        Configuration config = configuration();
        CatalogAttribute attribute = attributeTyped("EnumRef.Важность"); //$NON-NLS-1$
        Type second = McoreFactory.eINSTANCE.createType();
        second.setName("CatalogRef.Склады"); //$NON-NLS-1$
        attribute.getType().getTypes().add(second);

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "Обычная", config); //$NON-NLS-1$

        assertTrue(refused.contains("compositeType")); //$NON-NLS-1$
    }

    /** Without a configuration to resolve against, the fill names what it needs. */
    @Test
    public void withoutAConfigurationTheNeedIsNamed()
    {
        CatalogAttribute attribute = attributeTyped("EnumRef.Важность"); //$NON-NLS-1$

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "Обычная", (Configuration)null); //$NON-NLS-1$

        assertTrue(refused.contains("configuration")); //$NON-NLS-1$
    }
}
