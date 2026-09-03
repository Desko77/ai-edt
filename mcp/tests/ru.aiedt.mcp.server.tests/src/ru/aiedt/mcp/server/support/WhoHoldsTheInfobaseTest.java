/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * A file infobase admits one owner. While EDT holds the designer session on it, a client started
 * against the same infobase does not connect: the process lives out its whole deadline without one
 * event reaching the infobase log, and the answer reads as a run that timed out rather than one
 * that never began. {@link InfobaseAddress#release} has EDT let go for the length of a launch.
 */
public class WhoHoldsTheInfobaseTest
{
    /**
     * A caller who named the connection string itself has no project, and there is nothing to let
     * go of. The hold is still handed back, so the launch site needs no branch of its own.
     */
    @Test
    public void withoutAProjectThereIsNothingToRelease()
    {
        try (InfobaseAddress.Hold hold = InfobaseAddress.release(null))
        {
            assertNotNull(hold);
            assertFalse(hold.released());
        }
    }

    /**
     * Closing a hold that released nothing takes nothing back. An infobase the user had
     * disconnected themselves must stay disconnected: connecting it back would change what they
     * set up, and it reaches this class as the same "not released by us" state.
     */
    @Test
    public void aHoldThatReleasedNothingTakesNothingBack()
    {
        InfobaseAddress.Hold hold = InfobaseAddress.release(null);
        assertFalse(hold.released());
        hold.close();
        hold.close();
        assertFalse(hold.released());
    }

    /**
     * An address that names no infobase releases nothing and says nothing: there was no hold to
     * begin with, so there is no reason to pass back to the caller.
     */
    @Test
    public void anAddressWithoutAnInfobaseReleasesNothing()
    {
        InfobaseAddress.Address nowhere = new InfobaseAddress.Address(null, null, null, null);
        try (InfobaseAddress.Hold hold = InfobaseAddress.release(nowhere))
        {
            assertFalse(hold.released());
            assertNull(hold.why());
        }
    }
}
