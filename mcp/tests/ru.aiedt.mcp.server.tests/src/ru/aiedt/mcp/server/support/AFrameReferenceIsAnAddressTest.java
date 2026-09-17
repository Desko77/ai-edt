/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * A frame reference names a position in a thread's stack, not a Java object that was seen once.
 *
 * <p>Measured 16.09 in a real debugging session: reading variables through the reference
 * <code>wait_for_break</code> had just handed out failed with "Cannot read the array length because
 * this.variables is null", while the same frame reached through the thread and its index answered
 * normally. The debug model rebuilds its frame objects on each suspend, so what is remembered has to
 * be the address.
 *
 * <p>The reading itself needs a suspended 1C session and is checked on the stand; what is put to the
 * test here is the rule that decides whether the remembered position still exists.
 */
public class AFrameReferenceIsAnAddressTest
{
    @Test
    public void apositionInsideTheStackIsRead()
    {
        assertEquals(0, DebugSessionBook.pickIndex(3, 0));
        assertEquals(2, DebugSessionBook.pickIndex(3, 2));
    }

    @Test
    public void aStackThatNoLongerReachesThePositionIsStale()
    {
        // The session ran on and stopped in a shallower place: the reference named a frame that is
        // not there any more, and a shallower stack must not answer with somebody else's frame.
        assertEquals(-1, DebugSessionBook.pickIndex(2, 5));
    }

    @Test
    public void anEmptyStackNamesNothing()
    {
        assertEquals(-1, DebugSessionBook.pickIndex(0, 0));
    }

    @Test
    public void anUnknownPositionIsStale()
    {
        // -1 is what registration records when the frame could not be found in its own stack.
        assertEquals(-1, DebugSessionBook.pickIndex(5, -1));
    }
}
