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

import org.junit.Test;

/**
 * Stopping early is half of cancelling; saying so is the other half.
 * <p>
 * A scan that stops and answers as though it had finished is worse than one that does not stop at
 * all: the caller is handed part of the work under the name of the whole, and "nothing was found"
 * then means "nothing was looked at". That is the same confusion the project refuses everywhere
 * else, and it arrives here by simply forgetting one line at the end.
 * </p>
 */
public class AStoppedScanSaysItWasStoppedTest
{
    @Test
    public void withNoCallAroundItNothingIsEverStopped()
    {
        // A test, or a path the scope does not cover. The behaviour there is what it was before
        // any of this existed: the scan runs to the end.
        WatchForCancel watch = WatchForCancel.begin();

        assertFalse(watch.stopHere());
        assertFalse(watch.stopHere());
        assertFalse(watch.stopped());
        assertNull("a scan that ran to the end has nothing to report", watch.note("modules"));
    }

    @Test
    public void everyBoundaryIsCounted()
    {
        WatchForCancel watch = WatchForCancel.begin();
        for (int i = 0; i < 5; i++)
        {
            watch.stopHere();
        }

        assertEquals("the count is what the note reports, so it has to be the real one", 5,
            watch.reached());
    }

    @Test
    public void aStoppedScanReportsWhereItStopped()
    {
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        ToolCallScope.enter(ToolCallScope.forCancellation(flag));
        try
        {
            WatchForCancel watch = WatchForCancel.begin();
            watch.stopHere();
            watch.stopHere();
            flag.cancel("the operator asked to stop");
            watch.stopHere();

            assertTrue("a raised flag must stop the next boundary", watch.stopped());
            String note = watch.note("modules");
            assertNotNull("a stopped scan must have something to say", note);
            assertTrue(note, note.contains("3"));
            assertTrue(note, note.contains("modules"));
            assertTrue("the note has to warn that the answer is partial",
                note.contains("not the whole answer"));
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    @Test
    public void theCountStopsWhereTheWorkStopped()
    {
        // A loop that keeps asking after the stop - one that goes on recording what it is skipping -
        // must not push the number the answer reports up towards the size of the whole project.
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        ToolCallScope.enter(ToolCallScope.forCancellation(flag));
        try
        {
            WatchForCancel watch = WatchForCancel.begin();
            watch.stopHere();
            watch.stopHere();
            flag.cancel("the operator asked to stop");
            watch.stopHere();
            for (int i = 0; i < 97; i++)
            {
                watch.stopHere();
            }

            assertEquals("the count names where the work ended, not how often it was asked", 3,
                watch.reached());
            assertTrue(watch.note("files"), watch.note("files").contains(" 3 files"));
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    @Test
    public void onceStoppedItStaysStopped()
    {
        // The caller breaks out of its loop on the first true, but a caller that checks again -
        // in a nested loop, say - must not be told to carry on.
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        ToolCallScope.enter(ToolCallScope.forCancellation(flag));
        try
        {
            WatchForCancel watch = WatchForCancel.begin();
            flag.cancel("the operator asked to stop");

            assertTrue(watch.stopHere());
            assertTrue(watch.stopHere());
            assertTrue(watch.stopped());
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    @Test
    public void askingWithoutCountingStillMakesTheAnswerSaySo()
    {
        // The boundary belongs to loops written here; a walk handed to someone else's progress
        // monitor reports through the same watch without adding to a count it cannot keep.
        ToolCallScope.Cancellation flag = new ToolCallScope.Cancellation();
        ToolCallScope.enter(ToolCallScope.forCancellation(flag));
        try
        {
            WatchForCancel watch = WatchForCancel.begin();
            watch.stopHere();
            flag.cancel("the operator asked to stop");

            assertTrue("the flag is raised, so the walk has to stop", watch.raised());
            assertEquals("asking without counting must not move the count", 1, watch.reached());
            assertNotNull("a walk stopped this way still has to say so", watch.note("modules"));
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    @Test
    public void askingWithoutCountingOnAQuietFlagStopsNothing()
    {
        ToolCallScope.enter(ToolCallScope.forCancellation(new ToolCallScope.Cancellation()));
        try
        {
            WatchForCancel watch = WatchForCancel.begin();

            assertFalse(watch.raised());
            assertFalse(watch.stopped());
            assertNull(watch.note("modules"));
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    @Test
    public void aScopeWithAFlagThatWasNeverRaisedStopsNothing()
    {
        // The control. Without it the two tests above would pass just as well against a watch that
        // stops on every boundary, which would turn every heavy call into a one-step call.
        ToolCallScope.enter(ToolCallScope.forCancellation(new ToolCallScope.Cancellation()));
        try
        {
            WatchForCancel watch = WatchForCancel.begin();

            assertFalse(watch.stopHere());
            assertFalse(watch.stopHere());
            assertFalse(watch.stopped());
            assertNull(watch.note("modules"));
        }
        finally
        {
            ToolCallScope.exit();
        }
    }
}
