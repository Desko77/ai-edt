/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Guards the registry that keeps comparisons open between calls.
 * <p>
 * <b>Two costs it balances.</b> Re-comparing two whole configurations takes minutes, so paging
 * through what changed has to reuse one comparison. But a comparison left open holds the
 * environment's comparison store, and one that outlives its session is how an EDT ends up unable to
 * close - measured, on a stand, for an hour. So sessions expire and are capped, and what is dropped
 * has to reach whoever can close it.
 * </p>
 */
public class ComparisonSessionsTest
{
    private static final String SIDES = "P | other | ancestor"; //$NON-NLS-1$

    @Before
    public void startEmpty()
    {
        ComparisonSessions.forgetEverything();
    }

    @Test
    public void theSameThreeSidesFindTheOpenSession()
    {
        long now = 1_000L;
        ComparisonSessions.Session opened = ComparisonSessions.open(SIDES, "handle", now);
        ComparisonSessions.Session found = ComparisonSessions.findByFingerprint(SIDES, now + 1);
        assertNotNull("reuse is the whole point: a page must not cost a comparison", found);
        assertEquals(opened.key, found.key);
    }

    @Test
    public void differentSidesDoNotShareASession()
    {
        long now = 1_000L;
        ComparisonSessions.open(SIDES, "handle", now);
        assertNull("a different ancestor is a different comparison, and answering from the wrong "
            + "one would attribute changes to the wrong side",
            ComparisonSessions.findByFingerprint("P | other | somewhere else", now));
    }

    @Test
    public void aFingerprintTellsTwoSidedFromThree()
    {
        // Comparing without an ancestor attributes nothing, so a two-sided comparison must never
        // be handed back to a caller who asked for three sides.
        String twoSided = ComparisonSessions.fingerprintOf("P", "other", null);
        String threeSided = ComparisonSessions.fingerprintOf("P", "other", "ancestor");
        assertNotEquals(twoSided, threeSided);
        assertEquals(twoSided, ComparisonSessions.fingerprintOf("P", "other", "   "));
    }

    @Test
    public void anIdleSessionExpiresAndIsHandedBackForClosing()
    {
        long opened = 1_000L;
        ComparisonSessions.Session session = ComparisonSessions.open(SIDES, "handle", opened);
        long later = opened + ComparisonSessions.IDLE_LIMIT_MS + 1;
        assertNull(ComparisonSessions.findByFingerprint(SIDES, later));

        List<ComparisonSessions.Session> dropped = ComparisonSessions.drainDropped();
        assertEquals("a forgotten session whose comparison is never closed leaves the environment "
            + "holding one nobody can name", 1, dropped.size());
        assertEquals(session.key, dropped.get(0).key);
    }

    @Test
    public void useKeepsASessionAlive()
    {
        long opened = 1_000L;
        ComparisonSessions.open(SIDES, "handle", opened);
        long nearlyExpired = opened + ComparisonSessions.IDLE_LIMIT_MS - 1;
        assertNotNull(ComparisonSessions.findByFingerprint(SIDES, nearlyExpired));
        // Touched, so the clock restarts from there rather than from when it was opened.
        assertNotNull(ComparisonSessions.findByFingerprint(SIDES,
            nearlyExpired + ComparisonSessions.IDLE_LIMIT_MS - 1));
    }

    @Test
    public void theOldestGoesWhenThereAreTooMany()
    {
        long now = 1_000L;
        ComparisonSessions.Session first = ComparisonSessions.open("sides 0", "h0", now);
        for (int i = 1; i <= ComparisonSessions.MAX_SESSIONS; i++)
        {
            ComparisonSessions.open("sides " + i, "h" + i, now + i);
        }
        assertEquals(ComparisonSessions.MAX_SESSIONS, ComparisonSessions.list(now).size());
        assertNull("without a ceiling a caller looping over projects exhausts the heap",
            ComparisonSessions.findByFingerprint("sides 0", now));

        List<ComparisonSessions.Session> dropped = ComparisonSessions.drainDropped();
        assertEquals(1, dropped.size());
        assertEquals(first.key, dropped.get(0).key);
    }

    @Test
    public void closingForgetsAndHandsBackTheHandle()
    {
        ComparisonSessions.Session opened = ComparisonSessions.open(SIDES, "handle", 1_000L);
        ComparisonSessions.Session closed = ComparisonSessions.close(opened.key);
        assertNotNull(closed);
        assertEquals("handle", closed.handle);
        assertNull(ComparisonSessions.findByKey(opened.key, 1_001L));
    }

    @Test
    public void anUnknownKeyIsNothingRatherThanAnAccident()
    {
        ComparisonSessions.open(SIDES, "handle", 1_000L);
        assertNull(ComparisonSessions.findByKey("cmp-nothing", 1_000L));
        assertNull(ComparisonSessions.findByKey(null, 1_000L));
        assertNull(ComparisonSessions.close(null));
    }

    @Test
    public void shutdownHandsBackEverythingStillOpen()
    {
        // A comparison that outlives the server keeps the environment's comparison store busy.
        ComparisonSessions.open("sides a", "ha", 1_000L);
        ComparisonSessions.open("sides b", "hb", 1_001L);
        List<ComparisonSessions.Session> were = ComparisonSessions.closeAll();
        assertEquals(2, were.size());
        assertTrue(ComparisonSessions.list(1_002L).isEmpty());
    }

    @Test
    public void drainingTwiceDoesNotHandTheSameSessionBack()
    {
        ComparisonSessions.open(SIDES, "handle", 1_000L);
        ComparisonSessions.findByFingerprint(SIDES, 1_000L + ComparisonSessions.IDLE_LIMIT_MS + 1);
        assertEquals(1, ComparisonSessions.drainDropped().size());
        assertTrue("closing the same comparison twice is an error the environment reports as a "
            + "warning, and a drain that repeats itself would cause it every call",
            ComparisonSessions.drainDropped().isEmpty());
    }

    @Test
    public void nothingOpenMeansNothingToCloseAtShutdown()
    {
        // Asked on the way out by a caller that must not load the comparison types unless there
        // is something to close: those imports are optional, and touching them on an install
        // without the comparison packages would fail while the plugin is stopping.
        assertFalse(ComparisonSessions.anythingOpen());
        ComparisonSessions.open(SIDES, "handle", 1_000L);
        assertTrue(ComparisonSessions.anythingOpen());
        ComparisonSessions.closeAll();
        assertFalse(ComparisonSessions.anythingOpen());
    }

    @Test
    public void aDroppedSessionStillCountsAsSomethingToClose()
    {
        // The defect this guards: expiry forgets a session, and only a later call came back to
        // close the comparison behind it. A shutdown that asked "is anything OPEN" would answer
        // no, and the comparison would outlive the plugin holding the comparison store.
        ComparisonSessions.open(SIDES, "handle", 1_000L);
        ComparisonSessions.findByFingerprint(SIDES, 1_000L + ComparisonSessions.IDLE_LIMIT_MS + 1);
        assertTrue("expired is not closed - the environment still holds the comparison",
            ComparisonSessions.anythingOpen());
        ComparisonSessions.drainDropped();
        assertFalse(ComparisonSessions.anythingOpen());
    }

    @Test
    public void keysDifferBetweenSessions()
    {
        ComparisonSessions.Session first = ComparisonSessions.open("sides a", "ha", 1_000L);
        ComparisonSessions.Session second = ComparisonSessions.open("sides b", "hb", 1_000L);
        assertNotEquals("two sessions opened in the same millisecond must still be tellable apart",
            first.key, second.key);
        assertFalse(first.key.isEmpty());
    }
}
