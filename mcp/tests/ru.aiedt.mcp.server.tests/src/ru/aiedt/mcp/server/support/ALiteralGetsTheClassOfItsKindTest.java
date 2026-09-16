/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * A value written into a composition schema gets the class of value its kind has.
 * <p>
 * A settings parameter value, and every value inside an appearance, is typed as {@code mcore.Value},
 * and the model has one class per kind: a boolean, a number, a date, undefined, null, a string.
 * Everything used to be written as a string, so a date came back as text and a number as text - and
 * that is what the schema then carried.
 * </p>
 */
public class ALiteralGetsTheClassOfItsKindTest
{
    private static String kindOf(String written)
    {
        Object value = BmDcsHelper.createLiteralValue(written);
        assertNotNull("the mcore factory has to be reachable in this runtime", value); //$NON-NLS-1$
        for (Class<?> each : value.getClass().getInterfaces())
        {
            if (each.getName().startsWith("com._1c.g5.v8.dt.mcore.")) //$NON-NLS-1$
            {
                return each.getSimpleName();
            }
        }
        return value.getClass().getSimpleName();
    }

    /** The words for true and false, in either language. */
    @Test
    public void aBooleanIsABooleanValue()
    {
        assertEquals("BooleanValue", kindOf("true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("BooleanValue", kindOf("False")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("BooleanValue", kindOf("Истина")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("BooleanValue", kindOf("Ложь")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A number, whole or not. */
    @Test
    public void aNumberIsANumberValue()
    {
        assertEquals("NumberValue", kindOf("42")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("NumberValue", kindOf("-3.5")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("NumberValue", kindOf("0")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The two words that name no value at all. */
    @Test
    public void undefinedAndNullHaveTheirOwnClasses()
    {
        assertEquals("UndefinedValue", kindOf("Undefined")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("UndefinedValue", kindOf("Неопределено")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("NullValue", kindOf("Null")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Anything the model does not read as another kind stays a string. */
    @Test
    public void everythingElseIsAStringValue()
    {
        assertEquals("StringValue", kindOf("Товары")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("StringValue", kindOf("42 штуки")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("StringValue", kindOf("")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a word that looks like a date but is not one", "StringValue", //$NON-NLS-1$ //$NON-NLS-2$
            kindOf("вчера")); //$NON-NLS-1$
    }
}
