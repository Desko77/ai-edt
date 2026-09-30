/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.eclipse.emf.ecore.impl.EObjectImpl;
import org.junit.Test;

/**
 * A boolean written as text.
 * <p>
 * The spellings a caller actually types, in either language and either case, with spaces around
 * them, have to become that boolean. Text that is none of them has to be refused with the list of
 * spellings, and the property has to stay as it was: storing the unknown text as {@code false}
 * reports a successful write of a value nobody asked for.
 * </p>
 */
public class BooleanLiteralTest
{
    private static final String[] TRUE_SPELLINGS = {
        "true", "TRUE", "True", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "истина", "ИСТИНА", "Истина", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "да", "ДА", "Да", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "yes", "YES", "Yes", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "1" }; //$NON-NLS-1$

    private static final String[] FALSE_SPELLINGS = {
        "false", "FALSE", "False", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "ложь", "ЛОЖЬ", "Ложь", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "нет", "НЕТ", "Нет", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "no", "NO", "No", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "0" }; //$NON-NLS-1$

    /** An object whose one setter takes a primitive boolean. */
    public static final class FlagHolder extends EObjectImpl
    {
        private boolean flag;

        public void setFlag(boolean value)
        {
            this.flag = value;
        }

        public boolean isFlag()
        {
            return flag;
        }
    }

    @Test
    public void everyAcceptedSpellingIsReadInEitherCaseAndWithSpaces()
    {
        for (String spelling : TRUE_SPELLINGS)
        {
            assertTrue(spelling, BooleanLiteral.parse(spelling));
            assertTrue(spelling, BooleanLiteral.parse("  " + spelling + "  ")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String spelling : FALSE_SPELLINGS)
        {
            assertFalse(spelling, BooleanLiteral.parse(spelling));
            assertFalse(spelling, BooleanLiteral.parse("  " + spelling + "  ")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void anUnknownSpellingIsRefusedAndNamesTheAcceptedOnes()
    {
        assertRejected("Истинно"); //$NON-NLS-1$
        assertRejected("2"); //$NON-NLS-1$
        assertRejected(""); //$NON-NLS-1$
        assertRejected("   "); //$NON-NLS-1$
        assertRejected(null);
    }

    @Test
    public void aBooleanPropertyStoresARussianTrueLiteral()
    {
        FlagHolder target = new FlagHolder();

        assertNull(BmObjectHelper.setProperty(target, "flag", "  Истина  ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(target.isFlag());
    }

    @Test
    public void aBooleanPropertyRefusesAnUnknownLiteralAndKeepsTheOldValue()
    {
        FlagHolder target = new FlagHolder();
        target.setFlag(true);

        String refusal = BmObjectHelper.setProperty(target, "flag", "Истинно"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("истина")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("ложь")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("true")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("false")); //$NON-NLS-1$
        assertTrue("the previous value stays", target.isFlag()); //$NON-NLS-1$
    }

    private static void assertRejected(String text)
    {
        try
        {
            BooleanLiteral.parse(text);
            fail("accepted: [" + text + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (IllegalArgumentException rejected)
        {
            String message = rejected.getMessage();
            assertNotNull(message);
            for (String spelling : TRUE_SPELLINGS)
            {
                assertTrue(message, message.toLowerCase().contains(spelling.toLowerCase()));
            }
            for (String spelling : FALSE_SPELLINGS)
            {
                assertTrue(message, message.toLowerCase().contains(spelling.toLowerCase()));
            }
        }
    }
}
