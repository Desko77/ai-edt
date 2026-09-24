/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * A permit shared with a nested call stays held until the last holder departs.
 * <p>
 * The share is what a nested heavy call inherits instead of a second permit: the parent's return
 * departs only its own hold, and the permit comes back - once - when the work that inherited it is
 * done with it.
 * </p>
 */
public class AShareOfAPermitOutlivesItsFirstHolderTest
{
    /** The permit returns when the last holder departs, and only then. */
    @Test
    public void thePermitReturnsWhenTheLastHolderDeparts()
    {
        AtomicInteger released = new AtomicInteger();
        ToolRoad.Ticket ticket = new ToolRoad.Ticket(released::incrementAndGet);
        assertTrue(ticket.holdsPermit());
        ToolRoad.Ticket share = ticket.share();

        ticket.release();
        assertTrue("the share keeps the permit after its first holder let go", ticket.holdsPermit()); //$NON-NLS-1$
        assertEquals("nothing has returned the permit yet", 0, released.get()); //$NON-NLS-1$

        share.release();
        assertFalse("the permit is gone once every holder departed", ticket.holdsPermit()); //$NON-NLS-1$
        assertEquals("and it returned exactly once", 1, released.get()); //$NON-NLS-1$
    }

    /** A second release of a spent ticket departs nothing. */
    @Test
    public void aSecondReleaseOfASpentTicketDepartsNothing()
    {
        AtomicInteger released = new AtomicInteger();
        ToolRoad.Ticket ticket = new ToolRoad.Ticket(released::incrementAndGet);
        ticket.release();
        ticket.release();
        assertEquals(1, released.get());
    }

    /**
     * A share taken after the count has already fallen to zero is refused, and the limiter does
     * not rise above the permit it was given.
     * <p>
     * {@code share} used to increment unconditionally. A second thread that still saw the lease
     * through a scope whose ticket was already spent could resurrect the count after the last
     * holder had returned the permit, and the later departure released it again.
     * </p>
     */
    @Test
    public void aShareAfterThePermitWasReturnedRefuses()
    {
        Semaphore permits = new Semaphore(1);
        assertTrue(permits.tryAcquire());
        ToolRoad.Ticket ticket = new ToolRoad.Ticket(permits::release);
        ticket.release();
        assertEquals("the permit is back at the limit", 1, permits.availablePermits()); //$NON-NLS-1$

        ToolRoad.Ticket share = ticket.share();
        if (share != null)
        {
            share.release();
        }
        assertEquals("refusing the share leaves the limiter at its one permit", //$NON-NLS-1$
            1, permits.availablePermits());
        assertNull("a share of a returned permit is refused", share); //$NON-NLS-1$
    }
}
