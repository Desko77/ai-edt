/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 1C reads keywords and names without case, Cyrillic included. An interceptor's target written in
 * other case than the base declares it is the same method, and a base module written in capitals
 * declares its methods all the same.
 */
public class AnInterceptorTargetIsFoundWhateverItsCaseTest
{
    private static final String BASE = "&НаСервере\r\n" //$NON-NLS-1$
        + "ПРОЦЕДУРА ПередЗаписью(Отказ)\r\n" //$NON-NLS-1$
        + "    Отказ = Ложь;\r\n" //$NON-NLS-1$
        + "КОНЕЦПРОЦЕДУРЫ\r\n"; //$NON-NLS-1$

    /** A keyword in capitals is still the keyword. */
    @Test
    public void aKeywordInCapitalsDeclaresTheMethod()
    {
        assertTrue(ListInterceptorsTool.methodDeclared(BASE, "ПередЗаписью")); //$NON-NLS-1$
    }

    /** The name is compared without case, and still as a whole name. */
    @Test
    public void theNameIsComparedWithoutCase()
    {
        assertTrue(ListInterceptorsTool.methodDeclared(BASE, "передзаписью")); //$NON-NLS-1$
        assertFalse(ListInterceptorsTool.methodDeclared(BASE, "ПередЗапис")); //$NON-NLS-1$
        assertFalse(ListInterceptorsTool.methodDeclared(BASE, "ПослеЗаписи")); //$NON-NLS-1$
    }

    /** The body between a declaration and its end is found whatever the case of either. */
    @Test
    public void theBodyIsFoundWhateverTheCase()
    {
        assertEquals("    Отказ = Ложь;", ListInterceptorsTool.bodyOf(BASE, "ПЕРЕДЗАПИСЬЮ")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
