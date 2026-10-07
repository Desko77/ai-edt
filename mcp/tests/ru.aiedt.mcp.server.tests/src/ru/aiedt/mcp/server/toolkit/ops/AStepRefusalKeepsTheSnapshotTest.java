/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.After;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;

/**
 * A step the thread refuses leaves the session as describable as it was.
 *
 * <p>The suspend snapshot is what a caller reads the stopped state from, and the step is what
 * invalidates it. Dropping the snapshot before the switch threw it away even for a step the thread
 * answered {@code canStep*} with no - the session had not moved, and the record of where it stopped
 * was gone. It goes only once the step is really sent, inside the branch that sends it.</p>
 */
public class AStepRefusalKeepsTheSnapshotTest
{
    private static final String APP = "d31-step-refused"; //$NON-NLS-1$

    private final DebugSessionBook registry = DebugSessionBook.get();

    @After
    public void forgetTheSession()
    {
        registry.clearSnapshot(APP);
    }

    @Test
    public void aRefusedStepOverLeavesTheSnapshotInPlace() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        long threadId = FakeDebugToolCalls.register(APP, session);
        session.canStepOver = false;

        String answer = new DebugStepper().execute(
            FakeDebugToolCalls.args("threadId", String.valueOf(threadId), "kind", "over")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue("the refusal is what the caller is told: " + answer, //$NON-NLS-1$
            FakeDebugToolCalls.error(answer).contains("cannot step over")); //$NON-NLS-1$
        assertTrue("nothing was sent, so nothing is stale: the stop is still readable", //$NON-NLS-1$
            registry.hasSnapshot(APP));
        assertFalse("no step reached the thread", session.stepRequests > 0); //$NON-NLS-1$
    }

    @Test
    public void aSentStepDropsTheStaleSnapshot() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        long threadId = FakeDebugToolCalls.register(APP, session);
        session.canStepOver = true;

        // The wait is not what this test is about; the step leaving is.
        Map<String, String> params = FakeDebugToolCalls.args(
            "threadId", String.valueOf(threadId), "kind", "over", "timeoutSeconds", "1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        String answer = new DebugStepper().execute(params);

        assertFalse("the record of the old stop must not survive the step: " + answer, //$NON-NLS-1$
            registry.hasSnapshot(APP));
        assertTrue("the step reached the thread", session.stepRequests > 0); //$NON-NLS-1$
    }
}
