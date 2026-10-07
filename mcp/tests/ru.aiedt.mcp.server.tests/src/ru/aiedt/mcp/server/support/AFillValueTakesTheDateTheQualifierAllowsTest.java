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

import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.DateQualifiers;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;

/**
 * A fill value spelled as a date reaches the attribute only in the shape its date qualifier allows.
 * <p>
 * The qualifier is part of the attribute's type, and a date that does not fit it is not a date with a
 * different precision - it is the wrong literal. What EDT writes for a beginning-of-date default is a
 * DateValue carrying {@code 0001-01-01T00:00:00}, so that is the object both the literal and the
 * refusal have to answer to. Built from the factories: the model answers about its own features with
 * no workspace behind it.
 * </p>
 */
public class AFillValueTakesTheDateTheQualifierAllowsTest
{
    private static CatalogAttribute dateAttribute(DateFractions fractions)
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Issued"); //$NON-NLS-1$
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName("Date"); //$NON-NLS-1$
        description.getTypes().add(type);
        if (fractions != null)
        {
            DateQualifiers qualifiers = McoreFactory.eINSTANCE.createDateQualifiers();
            qualifiers.setDateFractions(fractions);
            description.setDateQualifiers(qualifiers);
        }
        attribute.setType(description);
        return attribute;
    }

    /** A date literal lands as a DateValue with its own parts. */
    @Test
    public void aDateLiteralLandsWithItsOwnParts()
    {
        CatalogAttribute attribute = dateAttribute(DateFractions.DATE);

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "2024-03-01")); //$NON-NLS-1$

        DateValue value = (DateValue)attribute.getFillValue();
        assertEquals(2024, value.getValue().getYear());
        assertEquals(3, value.getValue().getMonth());
        assertEquals(1, value.getValue().getDay());
    }

    /** A time literal on a Date-qualified attribute is refused, naming the qualifier. */
    @Test
    public void aTimeLiteralIsRefusedOnADateQualifier()
    {
        String refused = BmDefinedTypeHelper.applyFillValue(dateAttribute(DateFractions.DATE), "12:30:00"); //$NON-NLS-1$

        assertTrue(refused.contains("Date")); //$NON-NLS-1$
        assertTrue(refused.contains("YYYY-MM-DD")); //$NON-NLS-1$
    }

    /** A time literal lands on a Time-qualified attribute. */
    @Test
    public void aTimeLiteralLandsOnATimeQualifier()
    {
        CatalogAttribute attribute = dateAttribute(DateFractions.TIME);

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "12:30:45")); //$NON-NLS-1$

        assertEquals(12, ((DateValue)attribute.getFillValue()).getValue().getHour());
        assertEquals(30, ((DateValue)attribute.getFillValue()).getValue().getMinute());
        assertEquals(45, ((DateValue)attribute.getFillValue()).getValue().getSecond());
    }

    /** A date literal on a Time-qualified attribute is refused. */
    @Test
    public void aDateLiteralIsRefusedOnATimeQualifier()
    {
        String refused = BmDefinedTypeHelper.applyFillValue(dateAttribute(DateFractions.TIME), "2024-03-01"); //$NON-NLS-1$

        assertTrue(refused.contains("Time")); //$NON-NLS-1$
        assertTrue(refused.contains("HH:MM:SS")); //$NON-NLS-1$
    }

    /** A date-with-time literal lands on a DateTime-qualified attribute. */
    @Test
    public void aDateTimeLiteralLandsOnADateTimeQualifier()
    {
        CatalogAttribute attribute = dateAttribute(DateFractions.DATE_TIME);

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "2024-03-01T12:30:45")); //$NON-NLS-1$

        assertEquals(2024, ((DateValue)attribute.getFillValue()).getValue().getYear());
        assertEquals(12, ((DateValue)attribute.getFillValue()).getValue().getHour());
    }

    /** A bare date on a DateTime-qualified attribute is refused: the qualifier wants the time too. */
    @Test
    public void aBareDateIsRefusedOnADateTimeQualifier()
    {
        String refused = BmDefinedTypeHelper.applyFillValue(dateAttribute(DateFractions.DATE_TIME),
            "2024-03-01"); //$NON-NLS-1$

        assertTrue(refused.contains("DateTime")); //$NON-NLS-1$
        assertTrue(refused.contains("YYYY-MM-DDTHH:MM:SS")); //$NON-NLS-1$
    }

    /** An attribute that declared no date qualifier behaves as the platform default, DateTime. */
    @Test
    public void noQualifierBehavesAsDateTime()
    {
        CatalogAttribute attribute = dateAttribute(null);

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "2024-03-01T00:00:00")); //$NON-NLS-1$
        assertTrue(BmDefinedTypeHelper.applyFillValue(dateAttribute(null), "2024-03-01") //$NON-NLS-1$
            .contains("DateTime")); //$NON-NLS-1$
    }

    /** The beginning-of-date literal is the DateValue EDT writes: 0001-01-01T00:00:00. */
    @Test
    public void theBeginningOfDateLiteralLandsAsTheFirstMoment()
    {
        CatalogAttribute attribute = dateAttribute(DateFractions.DATE);

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "StandardBeginningDate")); //$NON-NLS-1$

        DateValue value = (DateValue)attribute.getFillValue();
        assertEquals(1, value.getValue().getYear());
        assertEquals(1, value.getValue().getMonth());
        assertEquals(1, value.getValue().getDay());
        assertEquals(0, value.getValue().getHour());
    }

    /** The Russian spelling of the beginning-of-date literal lands the same way. */
    @Test
    public void theRussianBeginningOfDateLiteralLandsToo()
    {
        CatalogAttribute attribute = dateAttribute(DateFractions.DATE_TIME);

        assertNull(BmDefinedTypeHelper.applyFillValue(attribute, "НачалоДаты")); //$NON-NLS-1$

        assertEquals(1, ((DateValue)attribute.getFillValue()).getValue().getYear());
    }

    /** The beginning of date is refused where it does not belong. */
    @Test
    public void theBeginningOfDateIsRefusedOnAStringAttribute()
    {
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.setName("Note"); //$NON-NLS-1$
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName("String"); //$NON-NLS-1$
        description.getTypes().add(type);
        attribute.setType(description);

        assertTrue(BmDefinedTypeHelper.applyFillValue(attribute, "StandardBeginningDate") //$NON-NLS-1$
            .contains("Date")); //$NON-NLS-1$
    }
}
