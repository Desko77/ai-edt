/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.Test;

/**
 * A thick-client call claims success only after the whole exchange, the reconnection included.
 * <p>
 * The launcher call returning is not the end of it: EDT still has to take the infobase back, and a
 * reconnection that fails leaves it disconnected - in EDT, where the user sees it and the answer
 * does not. A claim made from inside the call therefore answered a listing or a removal as done
 * while the base stayed disconnected, and the tools read that flag and nothing else. These tests
 * drive the order with every step handed in, which is the seam the claim sits on.
 * </p>
 */
public class TheClaimOfSuccessComesAfterTheHandshakeTest
{
    /** A reconnection that failed reaches the caller, and the call has not claimed success. */
    @Test
    public void aFailedReconnectionLeavesTheCallUnclaimed() throws Exception
    {
        AtomicBoolean claimed = new AtomicBoolean();
        IllegalStateException reconnectFailure =
            new IllegalStateException("the base stayed disconnected"); //$NON-NLS-1$

        try
        {
            BmInfobaseExtensionHelper.handshakeThenClaimSuccess(new ReentrantLock(), () -> true,
                () -> { /* the launcher call went through */ },
                () -> { throw reconnectFailure; },
                () -> claimed.set(true));
            fail("a reconnection that failed is not swallowed"); //$NON-NLS-1$
        }
        catch (IllegalStateException failed)
        {
            assertSame("the caller gets the reconnection's own failure", reconnectFailure, failed); //$NON-NLS-1$
            assertFalse("the call is not reported as done while the base is disconnected", //$NON-NLS-1$
                claimed.get());
        }
    }

    /** A reconnection that went through is where the success is claimed, and after it. */
    @Test
    public void aReconnectionThatWentThroughIsFollowedByTheClaim() throws Exception
    {
        AtomicBoolean claimed = new AtomicBoolean();
        List<String> steps = new ArrayList<>();

        BmInfobaseExtensionHelper.handshakeThenClaimSuccess(new ReentrantLock(),
            () -> { steps.add("release"); return true; }, //$NON-NLS-1$
            () -> steps.add("work"), //$NON-NLS-1$
            () -> steps.add("reconnect"), //$NON-NLS-1$
            () -> { steps.add("claim"); claimed.set(true); }); //$NON-NLS-1$

        assertEquals("the claim is the last step", "release, work, reconnect, claim", //$NON-NLS-1$ //$NON-NLS-2$
            String.join(", ", steps)); //$NON-NLS-1$
        assertTrue("the success is claimed once the base is back", claimed.get()); //$NON-NLS-1$
    }

    /** A launcher call that failed leaves the success unclaimed and reaches the caller. */
    @Test
    public void aFailedCallLeavesTheSuccessUnclaimed() throws Exception
    {
        AtomicBoolean claimed = new AtomicBoolean();
        IllegalStateException callFailure = new IllegalStateException("the extension was not found"); //$NON-NLS-1$
        AtomicBoolean reconnected = new AtomicBoolean();

        try
        {
            BmInfobaseExtensionHelper.handshakeThenClaimSuccess(new ReentrantLock(), () -> true,
                () -> { throw callFailure; },
                () -> reconnected.set(true),
                () -> claimed.set(true));
            fail("a call that failed is not swallowed"); //$NON-NLS-1$
        }
        catch (IllegalStateException failed)
        {
            assertSame(callFailure, failed);
            assertTrue("the base is taken back even so", reconnected.get()); //$NON-NLS-1$
            assertFalse(claimed.get());
        }
    }

    /** An infobase that was not connected owes no reconnection; the success is still claimed. */
    @Test
    public void aBaseThatWasNeverConnectedIsStillClaimed() throws Exception
    {
        AtomicBoolean reconnected = new AtomicBoolean();
        AtomicBoolean claimed = new AtomicBoolean();

        BmInfobaseExtensionHelper.handshakeThenClaimSuccess(new ReentrantLock(), () -> false,
            () -> { /* the launcher call went through */ },
            () -> reconnected.set(true),
            () -> claimed.set(true));

        assertFalse("an infobase that was not connected is not taken back", reconnected.get()); //$NON-NLS-1$
        assertTrue(claimed.get());
    }
}
