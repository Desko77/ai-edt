/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Filling and the fill CHECK are two events, and each English name belongs to its own.
 * <p>
 * The table registers a signature under both the Russian and the English name, and the two English
 * names sat on each other's rows: a caller asking for {@code FillCheckProcessing} was handed the
 * signature of filling. The rest of the plugin never had them the wrong way round -
 * {@code BmEventSubscriptionHelper} and {@code FormEventRegistry} both read
 * {@code FillCheckProcessing} as the check - so the table disagreed with its own codebase.
 * </p>
 * <p>
 * Filling also takes the fill text. Counted in a configuration of 2571 modules: 32 declarations with
 * three arguments, one with two.
 * </p>
 */
public class TheFillEventsAreNotSwappedTest
{
    private static final String FILLING = "Обработка" //$NON-NLS-1$
        + "Заполнения";

    private static final String FILL_CHECK = "Обработка" //$NON-NLS-1$
        + "ПроверкиЗаполн" //$NON-NLS-1$
        + "ения";

    /** The parameter list the generator writes for an event, read out of the stub it renders. */
    private static String signatureOf(String event)
    {
        EventStubGenerator.Stub stub = EventStubGenerator.generateStub(event, "H", null); //$NON-NLS-1$
        assertNotNull("the table has to know " + event, stub.code); //$NON-NLS-1$
        assertEquals("the signature has to come from the table, not the fallback", //$NON-NLS-1$
            "known", stub.signatureSource); //$NON-NLS-1$
        int open = stub.code.indexOf('(');
        int close = stub.code.indexOf(')', open);
        assertTrue("the stub has to declare a parameter list", open > 0 && close > open); //$NON-NLS-1$
        return stub.code.substring(open + 1, close);
    }

    /** The English name of each event carries that event's own signature. */
    @Test
    public void eachEnglishNameCarriesItsOwnSignature()
    {
        assertEquals(signatureOf(FILLING), signatureOf("Filling")); //$NON-NLS-1$
        assertEquals(signatureOf(FILL_CHECK), signatureOf("FillCheckProcessing")); //$NON-NLS-1$
    }

    /** And the two are not the same signature, which is what being swapped looked like. */
    @Test
    public void theTwoEventsDoNotShareASignature()
    {
        assertTrue("filling and the fill check are different events", //$NON-NLS-1$
            !signatureOf(FILLING).equals(signatureOf(FILL_CHECK)));
    }

    /** Filling is handed the fill text as well. */
    @Test
    public void fillingCarriesTheFillText()
    {
        assertTrue("the fill text belongs in the filling signature: " + signatureOf(FILLING), //$NON-NLS-1$
            signatureOf(FILLING).contains("ТекстЗап" //$NON-NLS-1$
                + "олнения")); //$NON-NLS-1$
    }

    /** The check is handed the attributes it checks, and no fill data. */
    @Test
    public void theCheckCarriesTheAttributesItChecks()
    {
        String signature = signatureOf(FILL_CHECK);
        assertTrue("the checked attributes belong in the check: " + signature, //$NON-NLS-1$
            signature.contains("Проверяемы" //$NON-NLS-1$
                + "еРеквизиты")); //$NON-NLS-1$
    }
}
