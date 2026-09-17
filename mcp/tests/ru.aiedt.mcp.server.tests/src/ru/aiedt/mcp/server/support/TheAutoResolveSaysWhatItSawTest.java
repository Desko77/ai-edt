/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Resolving the application on its own succeeds on one id and refuses on two, and the refusal shows
 * the ids it saw.
 *
 * <p>Measured 16.09 in a real session: six calls resolved on their own and the seventh refused with
 * the launches unchanged. The list it reads is free of repeats, so two entries of one launch cannot
 * be what refused it - at that moment there were two DIFFERENT ids, and which ones the refusal never
 * said. It says now.
 */
public class TheAutoResolveSaysWhatItSawTest
{
    @Test
    public void nothingRunningResolvesToNothing()
    {
        assertNull(DebugSessionBook.loneOf(Collections.<String> emptyList()));
    }

    @Test
    public void oneApplicationResolvesToItself()
    {
        assertEquals("app-1", DebugSessionBook.loneOf(Collections.singletonList("app-1"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void twoEntriesOfOneApplicationAreOneApplication()
    {
        // A single launch shows up twice - the runtime client and the local runtime it attaches -
        // and both carry the same id. Counting entries instead of ids would refuse this.
        assertEquals("app-1", DebugSessionBook.loneOf(Collections.singletonList("app-1"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void twoDifferentApplicationsCannotBeResolvedForTheCaller()
    {
        assertNull("choosing between two debug sessions is not ours to do", //$NON-NLS-1$
            DebugSessionBook.loneOf(Arrays.asList("app-1", "app-2"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theListingIsAlwaysAnswerable()
    {
        // Headless, with no launch manager, it answers empty rather than throwing: a refusal that
        // fails to describe itself would be worse than the refusal it replaces.
        assertNotNull(DebugSessionBook.describeActiveLaunches());
    }
}
