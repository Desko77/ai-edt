/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Set;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The customPatterns argument of the sensitive-data scan: what a pattern matches once compiled,
 * and what happens to one that never compiles.
 * <p>
 * Attribute names are written in Cyrillic as often as in Latin, so case folding that stops at
 * ASCII answers "no match" for half the names a caller would mean. And a pattern the compiler
 * rejects has to refuse the call rather than vanish, because a vanished pattern and a pattern
 * that matched nothing give the same empty answer.
 * </p>
 */
public class ACustomPatternIsHonoredOrRefusedTest
{
    /** A lower-case Cyrillic fragment finds the name it sits in, whatever the case mix. */
    @Test
    public void aCyrillicPatternFoldsCaseAcrossTheWholeOfUnicode()
    {
        Set<Pattern> patterns = SensitiveDataScanTool.parseCustomPatterns("паспорт"); //$NON-NLS-1$
        assertTrue("паспорт must find НомерПаспорта", matchesAny(patterns, "НомерПаспорта")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A comma inside a {n,m} quantifier is part of the pattern, in either spelling. */
    @Test
    public void aQuantifierCommaDoesNotSplitThePattern()
    {
        Set<Pattern> fromString = SensitiveDataScanTool.parseCustomPatterns("Паспорт[0-9]{4,6}"); //$NON-NLS-1$
        assertEquals(1, fromString.size());
        assertTrue(matchesAny(fromString, "Паспорт12345")); //$NON-NLS-1$

        Set<Pattern> fromArray = SensitiveDataScanTool.parseCustomPatterns(
            "[\"Паспорт[0-9]{4,6}\", \"инн\"]"); //$NON-NLS-1$
        assertEquals(2, fromArray.size());
        assertTrue(matchesAny(fromArray, "Паспорт12345")); //$NON-NLS-1$
        assertTrue(matchesAny(fromArray, "КонтрагентИНН")); //$NON-NLS-1$
    }

    /** A pattern the compiler rejects refuses the call and names itself. */
    @Test
    public void aPatternThatDoesNotCompileRefusesTheCall()
    {
        try
        {
            SensitiveDataScanTool.parseCustomPatterns("паспорт["); //$NON-NLS-1$
            fail("a broken pattern must not be skipped"); //$NON-NLS-1$
        }
        catch (IllegalArgumentException refusal)
        {
            assertTrue(refusal.getMessage(), refusal.getMessage().contains("паспорт[")); //$NON-NLS-1$
        }
    }

    private static boolean matchesAny(Set<Pattern> patterns, String name)
    {
        for (Pattern pattern : patterns)
        {
            if (pattern.matcher(name).find())
            {
                return true;
            }
        }
        return false;
    }
}
