/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StringQualifiers;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * A fill value longer than the attribute's declared String length is refused before anything is written.
 * <p>
 * The length is part of the attribute's own type, and a value that does not fit it would not survive
 * the database: the refusal names the declared length so the caller can shorten the value or widen
 * the attribute. Unlimited (length 0) is the model default and takes any length.
 * </p>
 */
public class AStringLongerThanTheAttributeIsRefusedTest
{
    private static CatalogAttribute stringAttributeOfLength(int length)
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("КодВнешнейСистемы"); //$NON-NLS-1$
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName("String"); //$NON-NLS-1$
        description.getTypes().add(type);
        if (length > 0)
        {
            StringQualifiers qualifiers = McoreFactory.eINSTANCE.createStringQualifiers();
            qualifiers.setLength(length);
            description.setStringQualifiers(qualifiers);
        }
        attribute.setType(description);
        return attribute;
    }

    /** Thirteen characters into String(5) is refused, and the refusal names the 5. */
    @Test
    public void thirteenCharactersIntoFiveIsRefused()
    {
        CatalogAttribute attribute = stringAttributeOfLength(5);

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "ABCDEFGHIJKLM"); //$NON-NLS-1$

        assertTrue(refused.contains("13")); //$NON-NLS-1$
        assertTrue(refused.contains("5")); //$NON-NLS-1$
        assertNull("nothing is written on a refusal", attribute.getFillValue()); //$NON-NLS-1$
    }

    /** Five characters into String(5) land. */
    @Test
    public void fiveCharactersIntoFiveLand()
    {
        CatalogAttribute attribute = stringAttributeOfLength(5);

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "ABCDE")); //$NON-NLS-1$

        assertEquals("ABCDE", ((StringValue)attribute.getFillValue()).getValue()); //$NON-NLS-1$
    }

    /** An attribute that declared no length is unlimited and takes thirteen characters. */
    @Test
    public void noDeclaredLengthIsUnlimited()
    {
        CatalogAttribute attribute = stringAttributeOfLength(0);

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "ABCDEFGHIJKLM")); //$NON-NLS-1$

        assertEquals("ABCDEFGHIJKLM", ((StringValue)attribute.getFillValue()).getValue()); //$NON-NLS-1$
    }

    /** A value longer than a fixed-length attribute is refused the same way. */
    @Test
    public void aFixedLengthAttributeRefusesTheSameWay()
    {
        CatalogAttribute attribute = stringAttributeOfLength(5);
        StringQualifiers qualifiers = attribute.getType().getStringQualifiers();
        qualifiers.setFixed(true);

        String refused = BmDefinedTypeHelper.applyFillValue(attribute, "Шестьсимволов"); //$NON-NLS-1$

        assertTrue(refused.contains("longer than")); //$NON-NLS-1$
    }
}
