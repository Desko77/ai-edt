/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmInfobaseExtensionHelper.HandshakeOutcome;

/**
 * The Designer run for an import goes through the infobase handshake, and every step of it says
 * on its own how it ended.
 * <p>
 * Checked as a sequence, not as a final state: a final state of "connected" is also what a run
 * that never disconnected leaves behind, and that is the run this replaces.
 * </p>
 */
public class TheImportReleasesAndTakesBackTest
{
    // ---- 1: released, the work failed, taken back --------------------------------------------

    @Test
    public void theInfobaseIsReleasedTheWorkFailsAndTheInfobaseIsTakenBack()
    {
        HandshakeOutcome outcome = BmInfobaseExtensionHelper.runUnderHandshake(
            () -> true,
            () -> {
                throw new IllegalStateException("the Designer exited with code 1"); //$NON-NLS-1$
            },
            () -> {
                // taken back
            });
        assertEquals(Arrays.asList("release", "work", "reconnect"), outcome.sequence); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(outcome.released);
        assertNull(outcome.releaseError);
        assertNotNull(outcome.workError);
        assertNull(outcome.reconnectError);
    }

    // ---- 2: a release that fails stops everything ------------------------------------------

    @Test
    public void aReleaseThatFailsRunsNothingElse()
    {
        boolean[] ran = new boolean[2];
        HandshakeOutcome outcome = BmInfobaseExtensionHelper.runUnderHandshake(
            () -> {
                throw new IOException("the synchronization manager refused"); //$NON-NLS-1$
            },
            () -> ran[0] = true,
            () -> ran[1] = true);
        assertEquals(Arrays.asList("release"), outcome.sequence); //$NON-NLS-1$
        assertNotNull(outcome.releaseError);
        assertFalse("the Designer must not run on an infobase EDT still holds", ran[0]); //$NON-NLS-1$
        assertFalse("nothing was released, so nothing is taken back", ran[1]); //$NON-NLS-1$
        assertNull(outcome.workError);
        assertNull(outcome.reconnectError);
    }

    // ---- 3, 4: a reconnection that fails is named beside the work's outcome -----------------

    @Test
    public void aReconnectionThatFailsIsNamedOnItsOwn()
    {
        HandshakeOutcome outcome = BmInfobaseExtensionHelper.runUnderHandshake(
            () -> true,
            () -> {
                // the conversion went fine
            },
            () -> {
                throw new IllegalStateException("connectInfobase: infobase is locked"); //$NON-NLS-1$
            });
        assertEquals(Arrays.asList("release", "work", "reconnect"), outcome.sequence); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(outcome.workError);
        assertNotNull(outcome.reconnectError);
    }

    @Test
    public void aDoubleFailureShowsBoth()
    {
        HandshakeOutcome outcome = BmInfobaseExtensionHelper.runUnderHandshake(
            () -> true,
            () -> {
                throw new IllegalStateException("the Designer exited with code 1"); //$NON-NLS-1$
            },
            () -> {
                throw new IllegalStateException("connectInfobase: infobase is locked"); //$NON-NLS-1$
            });
        assertNotNull(outcome.workError);
        assertNotNull(outcome.reconnectError);
        assertEquals("the Designer exited with code 1", outcome.workError.getMessage()); //$NON-NLS-1$
        assertEquals("connectInfobase: infobase is locked", outcome.reconnectError.getMessage()); //$NON-NLS-1$
    }

    // ---- 6: an infobase that was not connected is not reconnected ---------------------------

    @Test
    public void anInfobaseThatWasNotConnectedIsLeftThatWay()
    {
        boolean[] reconnected = new boolean[1];
        HandshakeOutcome outcome = BmInfobaseExtensionHelper.runUnderHandshake(
            () -> false,
            () -> {
                // the conversion went fine
            },
            () -> reconnected[0] = true);
        assertEquals(Arrays.asList("release", "work"), outcome.sequence); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(outcome.released);
        assertFalse("a disconnected infobase stays disconnected", reconnected[0]); //$NON-NLS-1$
        assertNull(outcome.workError);
    }

    @Test
    public void aRunThatWentWellReportsNothingWrong()
    {
        HandshakeOutcome outcome = BmInfobaseExtensionHelper.runUnderHandshake(
            () -> true,
            () -> {
                // the conversion went fine
            },
            () -> {
                // taken back
            });
        assertEquals(Arrays.asList("release", "work", "reconnect"), outcome.sequence); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(outcome.releaseError);
        assertNull(outcome.workError);
        assertNull(outcome.reconnectError);
    }
}
