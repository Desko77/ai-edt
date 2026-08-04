/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Verifies the response cap: it measures UTF-8 length correctly across 1/2/4-byte
 * characters, truncates on a code-point boundary (never splitting a character),
 * applies the threshold exactly, and clamps the configured limit to the ceiling.
 */
public class ResponseCapTest
{
    private static int utf8(String s)
    {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    public void byteLengthCountsUtf8NotChars()
    {
        assertEquals(3, ResponseCap.byteLength("abc")); //$NON-NLS-1$
        assertEquals(2, ResponseCap.byteLength("П")); // Cyrillic П = 2 bytes //$NON-NLS-1$
        assertEquals(4, ResponseCap.byteLength("😀")); // emoji = 1 code point, 4 bytes //$NON-NLS-1$
    }

    @Test
    public void exceedsUsesByteLengthAndDisablesOnNonPositive()
    {
        assertFalse("no cap when limit <= 0", ResponseCap.exceeds("anything", 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("null never exceeds", ResponseCap.exceeds(null, 10)); //$NON-NLS-1$
        assertFalse("5 bytes not over 5", ResponseCap.exceeds("abcde", 5)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("6 bytes over 5", ResponseCap.exceeds("abcdef", 5)); //$NON-NLS-1$ //$NON-NLS-2$
        // Two 2-byte Cyrillic chars = 4 bytes; the length*3 fast path must not mis-skip.
        assertTrue("4 bytes over 3", ResponseCap.exceeds("ПП", 3)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void truncateReturnsWholeWhenItFitsOrCapOff()
    {
        assertEquals("abcde", ResponseCap.truncateUtf8("abcde", 5)); // exactly the limit //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("abc", ResponseCap.truncateUtf8("abc", 100)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("abc", ResponseCap.truncateUtf8("abc", 0)); // cap off //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("abc", ResponseCap.truncateUtf8("abc", -1)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void truncateAscii()
    {
        assertEquals("abcde", ResponseCap.truncateUtf8("abcdefgh", 5)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void truncateNeverSplitsA2ByteChar()
    {
        // "ПППП" is 8 bytes; a 5-byte cap must fall back to 2 whole chars (4 bytes),
        // never leaving a dangling lead/continuation byte.
        String out = ResponseCap.truncateUtf8("ПППП", 5); //$NON-NLS-1$
        assertEquals("ПП", out); //$NON-NLS-1$
        assertTrue("stays within the cap", utf8(out) <= 5); //$NON-NLS-1$
    }

    @Test
    public void truncateNeverSplitsA4ByteChar()
    {
        // Two emoji = 8 bytes; a 6-byte cap must keep exactly one whole emoji (4 bytes).
        String out = ResponseCap.truncateUtf8("😀😀", 6); //$NON-NLS-1$
        assertEquals("😀", out); //$NON-NLS-1$
        assertTrue("stays within the cap", utf8(out) <= 6); //$NON-NLS-1$
    }

    @Test
    public void truncatedResultIsAlwaysValidAndWithinCap()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++)
        {
            sb.append("aП€😀"); // 1-byte, 2-byte, 3-byte (euro), 4-byte (emoji) //$NON-NLS-1$
        }
        String big = sb.toString();
        assertFalse("fixture itself has no replacement char", //$NON-NLS-1$
            big.indexOf((char) 0xFFFD) >= 0);
        for (int cap = 1; cap <= 60; cap++)
        {
            String out = ResponseCap.truncateUtf8(big, cap);
            assertTrue("cap " + cap + " respected", utf8(out) <= cap); //$NON-NLS-1$ //$NON-NLS-2$
            // A cut inside a multi-byte sequence would decode to U+FFFD; there must be none,
            // since the fixture had none. This catches a mid-sequence truncation that a plain
            // round-trip (the output is already a decoded String) would miss.
            assertFalse("cap " + cap + " must not split a character", //$NON-NLS-1$
                out.indexOf((char) 0xFFFD) >= 0);
        }
    }

    @Test
    public void applyCeilingDisablesAndClamps()
    {
        assertEquals("<= 0 disables", 0, ResponseCap.applyCeiling(0)); //$NON-NLS-1$
        assertEquals("negative disables", 0, ResponseCap.applyCeiling(-5)); //$NON-NLS-1$
        assertEquals("in range passes", 1000, ResponseCap.applyCeiling(1000)); //$NON-NLS-1$
        assertEquals("clamped to ceiling", ResponseCap.HARD_CEILING_BYTES, //$NON-NLS-1$
            ResponseCap.applyCeiling(100L * 1024 * 1024));
    }
}
