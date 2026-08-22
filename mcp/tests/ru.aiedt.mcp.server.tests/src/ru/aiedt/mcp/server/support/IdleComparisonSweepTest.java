/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;

import org.junit.Before;
import org.junit.Test;

/**
 * Guards the timer that closes comparisons nobody came back for.
 * <p>
 * <b>The idle limit was written down and nothing reached it.</b> Expiry ran only as a side effect of
 * the next comparison call, so a caller who took one report and stopped left the session open until
 * the plugin shut down. The cost is not memory - an open comparison holds a transaction on the
 * environment's comparison store, and a held transaction has already left an EDT unable to shut
 * down once.
 * </p>
 * <p>
 * A sweep that finds something to close needs a live comparison subsystem and is measured on a
 * stand. What is pinned here is the arming rule, which decides whether the timer exists at all.
 * </p>
 */
public class IdleComparisonSweepTest
{
    @Before
    public void startFromAKnownState()
    {
        // shutdown is deliberately final, which makes the class order-dependent under test: the
        // first test to stop it would make every later arming assertion pass without arming
        // anything. Lifting it here is what keeps these assertions about the code.
        IdleComparisonSweep.shutdown();
        IdleComparisonSweep.allowRestartForTest();
        // The registry is static and shared with every other suite that opens a session. Without
        // this, "an empty registry does not arm the timer" is testing whoever ran first.
        ComparisonSessions.forgetEverything();
    }

    @Test
    public void anEmptyRegistryDoesNotArmTheTimer()
    {
        // Armed only while something is held. Otherwise an install where nobody compares carries a
        // thread that wakes every five minutes to find nothing, for as long as EDT runs.
        assertFalse(ComparisonSessions.anythingOpen());
        IdleComparisonSweep.ensureRunning();
        assertFalse(IdleComparisonSweep.isRunning());
    }

    @Test
    public void armingTwiceLeavesOneTimer()
    {
        // ensureRunning is called on every kept session, so it is called far more often than a
        // timer is wanted.
        IdleComparisonSweep.ensureRunning();
        IdleComparisonSweep.ensureRunning();
        assertFalse(IdleComparisonSweep.isRunning());
    }

    @Test
    public void aStoppedSweepDoesNotReArm()
    {
        // The flag is checked by every path that schedules. Without it, a run finishing while the
        // bundle stops could put itself back on the queue and outlive the classloader that owns
        // it - the same shape of leak this class exists to close.
        IdleComparisonSweep.shutdown();
        IdleComparisonSweep.ensureRunning();
        assertFalse("a stopped sweep must not re-arm", IdleComparisonSweep.isRunning());
    }

    @Test
    public void shutdownTwiceIsNotAnError()
    {
        IdleComparisonSweep.shutdown();
        IdleComparisonSweep.shutdown();
        assertFalse(IdleComparisonSweep.isRunning());
    }
}
