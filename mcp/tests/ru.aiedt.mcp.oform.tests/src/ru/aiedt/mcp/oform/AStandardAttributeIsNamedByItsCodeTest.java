/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

import ru.aiedt.mcp.oform.layout.StandardAttributes;

/**
 * A standard attribute is named by its kind and its negative code: every measured pair answers
 * its name from the code and its code from the name, the kind in any letter case, the name in
 * any letter case. What the corpus did not measure answers null - another kind's code, a code
 * outside the table, a positive code, a missing kind or a missing name - rather than a guess.
 */
public class AStandardAttributeIsNamedByItsCodeTest
{
    /** Every measured pair: kind, code, name. */
    private static final List<String[]> MEASURED = List.of(
        new String[] { "Catalog", "-2", "Код" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "Catalog", "-3", "Наименование" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "Catalog", "-4", "Родитель" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "Catalog", "-5", "Владелец" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "Document", "-2", "Номер" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "Document", "-3", "Дата" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "BusinessProcess", "-2", "Номер" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "BusinessProcess", "-3", "Дата" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "Task", "-3", "Дата" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "Task", "-9", "Наименование" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "Task", "-10", "Выполнена" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "ExchangePlan", "-2", "Код" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "ExchangePlan", "-3", "Наименование" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "ExchangePlan", "-9", "НомерОтправленного" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "ExchangePlan", "-10", "НомерПринятого" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "ChartOfCharacteristicTypes", "-6", "Родитель" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "ChartOfCharacteristicTypes", "-8", "Код" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "ChartOfCharacteristicTypes", "-9", "Наименование" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "ChartOfCharacteristicTypes", "-11", "ТипЗначения" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new String[] { "InformationRegister", "-2", "Период" }); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** Every measured pair names its attribute from its code, the kind in any letter case. */
    @Test
    public void everyMeasuredCodeNamesItsAttribute()
    {
        for (String[] pair : MEASURED)
        {
            assertEquals(pair[0] + " " + pair[1], pair[2], StandardAttributes.nameOf(pair[0], Integer.parseInt(pair[1])));
            assertEquals(pair[0] + " " + pair[1] + " in another case", pair[2], //$NON-NLS-1$
                StandardAttributes.nameOf(pair[0].toLowerCase(java.util.Locale.ROOT), Integer.parseInt(pair[1])));
        }
    }

    /** Every measured name answers its code, the kind and the name in any letter case. */
    @Test
    public void everyMeasuredNameAnswersItsCode()
    {
        for (String[] pair : MEASURED)
        {
            Integer code = StandardAttributes.codeOf(pair[0], pair[2]);
            assertEquals(pair[0] + " " + pair[2], Integer.parseInt(pair[1]), code.intValue());
            assertEquals(pair[0] + " " + pair[2] + " in another case", Integer.parseInt(pair[1]), //$NON-NLS-1$
                StandardAttributes.codeOf(pair[0].toUpperCase(java.util.Locale.ROOT),
                    pair[2].toUpperCase(java.util.Locale.ROOT)).intValue());
        }
    }

    /** The name and the code of every measured pair name each other back exactly. */
    @Test
    public void thePairsRoundTrip()
    {
        for (String[] pair : MEASURED)
        {
            Integer code = StandardAttributes.codeOf(pair[0], pair[2]);
            assertEquals(pair[0] + " " + pair[2], pair[2], StandardAttributes.nameOf(pair[0], code.intValue()));
        }
    }

    /** What the corpus did not measure answers null: another kind's code, an outside code, a positive code. */
    @Test
    public void anUnmeasuredPairAnswersNull()
    {
        // Task's code under a Catalog, a Catalog's code under a Document, a Document's name under
        // a Catalog, and a positive code, are all pairs the corpus never measured
        assertNull(StandardAttributes.nameOf("Catalog", -10)); //$NON-NLS-1$
        assertNull(StandardAttributes.nameOf("Catalog", -11)); //$NON-NLS-1$
        assertNull(StandardAttributes.nameOf("Document", -4)); //$NON-NLS-1$
        assertNull(StandardAttributes.nameOf("Catalog", 2)); //$NON-NLS-1$
        assertNull(StandardAttributes.nameOf("Catalog", 0)); //$NON-NLS-1$
        assertNull(StandardAttributes.codeOf("Catalog", "Номер")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(StandardAttributes.codeOf("Catalog", "Нигде")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(StandardAttributes.codeOf("Nobody", "Код")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A missing kind or a missing name answers null rather than failing. */
    @Test
    public void aMissingArgumentAnswersNull()
    {
        assertNull(StandardAttributes.nameOf(null, -2));
        assertNull(StandardAttributes.codeOf(null, "Код")); //$NON-NLS-1$
        assertNull(StandardAttributes.codeOf("Catalog", null)); //$NON-NLS-1$
    }
}
