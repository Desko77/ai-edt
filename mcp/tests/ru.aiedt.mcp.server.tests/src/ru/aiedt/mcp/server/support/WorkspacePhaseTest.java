/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/**
 * Pins the three phase names and the one rule that turns them into a decision.
 * <p>
 * The names are a wire contract: they are what /health reports under {@code phase}, and a client
 * that has just relaunched EDT waits for {@code ready} before trusting the semantic model. Renaming
 * one would leave that client waiting for a value nobody sends any more.
 * </p>
 */
public class WorkspacePhaseTest
{
    @Test
    public void thePhaseNamesAreTheOnesClientsWaitFor()
    {
        assertEquals("ready", WorkspacePhase.READY); //$NON-NLS-1$
        assertEquals("indexing", WorkspacePhase.INDEXING); //$NON-NLS-1$
        assertEquals("unknown", WorkspacePhase.UNKNOWN); //$NON-NLS-1$
    }

    @Test
    public void anUnreadableStateIsNotReportedAsReady()
    {
        // Distinctness is the whole point of having a third value: collapsing "could not tell" into
        // "ready" would send a waiting client racing the build it was waiting for.
        assertNotEquals(WorkspacePhase.READY, WorkspacePhase.UNKNOWN);
        assertNotEquals(WorkspacePhase.INDEXING, WorkspacePhase.UNKNOWN);
    }

    @Test
    public void thePhaseIsAlwaysOneOfTheThree()
    {
        String phase = WorkspacePhase.current();
        assertTrue("unexpected phase: " + phase, //$NON-NLS-1$
            Arrays.asList(WorkspacePhase.READY, WorkspacePhase.INDEXING, WorkspacePhase.UNKNOWN)
                .contains(phase));
    }

    @Test
    public void onlyIndexingCountsAsBusy()
    {
        assertTrue(WorkspacePhase.busy(WorkspacePhase.INDEXING));
        assertFalse(WorkspacePhase.busy(WorkspacePhase.READY));
        assertFalse(WorkspacePhase.busy(null));
    }

    @Test
    public void anUnreadableStateDoesNotCountAsBusyEither()
    {
        // The asymmetry is deliberate and worth pinning: "could not tell" must not be reported as
        // ready, but it must not postpone background work for ever either. One unreadable workspace
        // would otherwise mean a check that never runs.
        assertFalse(WorkspacePhase.busy(WorkspacePhase.UNKNOWN));
    }
}
