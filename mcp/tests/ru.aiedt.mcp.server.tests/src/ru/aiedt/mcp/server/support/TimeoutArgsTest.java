/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Verifies the unified timeout reader: the canonical {@code timeoutSeconds} and the legacy aliases
 * ({@code timeout} in seconds, {@code timeoutMs} in milliseconds) with the documented precedence,
 * the seconds default, and the clamp - including the SuspendWaiter rule that {@code timeoutMs} beats
 * {@code timeout} when no canonical value is given.
 */
public class TimeoutArgsTest
{
    private static Map<String, String> args(String... kv)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void absentUsesDefault()
    {
        assertEquals(60, TimeoutArgs.readSeconds(args(), 60, 1, 0));
    }

    @Test
    public void canonicalTimeoutSeconds()
    {
        assertEquals(45, TimeoutArgs.readSeconds(args("timeoutSeconds", "45"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void legacyTimeoutAliasStillWorks()
    {
        assertEquals(20, TimeoutArgs.readSeconds(args("timeout", "20"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void timeoutMsAliasConvertsToSeconds()
    {
        assertEquals(5, TimeoutArgs.readSeconds(args("timeoutMs", "5000"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, TimeoutArgs.readSeconds(args("timeoutMs", "1500"), 60, 1, 0)); // rounds //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void canonicalWinsOverBothAliases()
    {
        assertEquals(45, TimeoutArgs.readSeconds( //$NON-NLS-1$
            args("timeoutSeconds", "45", "timeoutMs", "9000", "timeout", "10"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    @Test
    public void timeoutMsBeatsTimeoutWhenNoCanonical()
    {
        // Preserves the pre-existing wait_for_break precedence.
        assertEquals(9, TimeoutArgs.readSeconds(args("timeoutMs", "9000", "timeout", "10"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void clampBelowMinAndAboveMax()
    {
        assertEquals(1, TimeoutArgs.readSeconds(args("timeoutSeconds", "0"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, TimeoutArgs.readSeconds(args("timeoutSeconds", "-5"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(120, TimeoutArgs.readSeconds(args("timeoutSeconds", "999"), 30, 5, 120)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nonPositiveMaxMeansNoUpperBound()
    {
        assertEquals(999, TimeoutArgs.readSeconds(args("timeoutSeconds", "999"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void unparseableFallsThroughToDefault()
    {
        assertEquals(60, TimeoutArgs.readSeconds(args("timeoutSeconds", "abc"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$
        // An unparseable canonical falls through to a usable alias.
        assertEquals(20, TimeoutArgs.readSeconds(args("timeoutSeconds", "abc", "timeout", "20"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // Unparseable at any position is skipped, not treated as zero.
        assertEquals(15, TimeoutArgs.readSeconds(args("timeoutMs", "xx", "timeout", "15"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void subSecondTimeoutMsClampsToMin()
    {
        // 499ms rounds to 0s, then the minimum lifts it to 1s.
        assertEquals(1, TimeoutArgs.readSeconds(args("timeoutMs", "499"), 60, 1, 0)); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
