/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com._1c.g5.v8.dt.compare.core.IComparisonManager;

/**
 * Holds what a call without a comparison manager does to the dropped-session queue.
 * <p>
 * Draining the queue is final: the handles in it are the only things that can release the
 * environment's transactions, and a drain that cannot close them destroys them while the
 * comparisons run on - the registry reads empty, the sweep stops asking, the leak stays for the
 * life of the process. The idle sweep already refuses to drain without a manager; the exit path of
 * every comparison has to hold the same line.
 * </p>
 */
public class ADroppedQueueSurvivesACallWithoutAManagerTest
{
    private static final String SIDES = "P | other | ancestor"; //$NON-NLS-1$

    /** Drops one idle session into the queue and leaves it there. */
    @Before
    public void startWithOneDropped()
    {
        ComparisonSessions.forgetEverything();
        ComparisonSessions.Session session = ComparisonSessions.open(SIDES, "handle", 1_000L); //$NON-NLS-1$
        ComparisonSessions.release(session);
        ComparisonSessions.findByFingerprint(SIDES,
            1_000L + ComparisonSessions.IDLE_LIMIT_MS + 1);
    }

    @Test
    public void aCallThatNeverReachedAManagerLeavesTheQueueAlone()
    {
        BmComparisonHelper.closeDropped(null);

        assertTrue("the dropped session still counts as something to close", //$NON-NLS-1$
            ComparisonSessions.anythingOpen());
        List<ComparisonSessions.Session> stillQueued = ComparisonSessions.drainDropped();
        assertEquals("and is still there for whoever can close it", 1, stillQueued.size()); //$NON-NLS-1$
    }

    @Test
    public void aManagerDrainsWhatTheQueueHolds()
    {
        IComparisonManager manager = (IComparisonManager)Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{IComparisonManager.class},
            (proxy, method, args) -> null);

        BmComparisonHelper.closeDropped(manager);

        assertTrue("drained, because this call could close what it drained", //$NON-NLS-1$
            ComparisonSessions.drainDropped().isEmpty());
    }
}
