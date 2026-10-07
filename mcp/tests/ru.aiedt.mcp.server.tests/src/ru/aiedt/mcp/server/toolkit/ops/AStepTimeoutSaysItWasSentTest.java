/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;

/**
 * A step that times out says the step was already sent.
 *
 * <p>The wait is bounded by {@code SuspendWaiter.MAX_TIMEOUT}, and a caller that asked for longer is
 * told which bound it got. The step itself left the thread before the wait began, so an answer that
 * only reported "nothing stopped" read as if the call had done nothing - the caller would send the
 * step again, doubling it. The answer now names the sent step and sends the caller to
 * {@code wait_for_break} for the stop.</p>
 */
public class AStepTimeoutSaysItWasSentTest
{
    private static final String APP = "d31-step-timeout"; //$NON-NLS-1$

    /** One second above the cap, so the cut is visible without waiting a second longer than needed. */
    private static final int ASKED_SECONDS = SuspendWaiter.MAX_TIMEOUT + 1;

    private final DebugSessionBook registry = DebugSessionBook.get();

    @After
    public void forgetTheSession()
    {
        registry.clearSnapshot(APP);
    }

    @Test
    public void aWaitLongerThanTheCapIsCutAndTheAnswerSaysSo() throws Exception
    {
        FakeDebugFrames.Session session = FakeDebugFrames.session(APP);
        long threadId = FakeDebugToolCalls.register(APP, session);
        session.canStepOver = true;

        long startedAt = System.currentTimeMillis();
        JsonObject answer = FakeDebugToolCalls.json(new DebugStepper().execute(FakeDebugToolCalls.args(
            "threadId", String.valueOf(threadId), "kind", "over", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "timeoutSeconds", String.valueOf(ASKED_SECONDS)))); //$NON-NLS-1$
        long waitedMillis = System.currentTimeMillis() - startedAt;

        assertFalse("no stop arrived, and the answer must not pretend one did", //$NON-NLS-1$
            answer.get("hit").getAsBoolean()); //$NON-NLS-1$
        assertEquals("timeout", answer.get("reason").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the wait is cut to the cap, not run for what was asked", //$NON-NLS-1$
            SuspendWaiter.MAX_TIMEOUT, answer.get("waitedSeconds").getAsInt()); //$NON-NLS-1$
        assertTrue("a cut wait is reported as cut", answer.get("timeoutCapped").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the ask is still named, so the caller can see what was cut", //$NON-NLS-1$
            ASKED_SECONDS, answer.get("requestedSeconds").getAsInt()); //$NON-NLS-1$
        String note = answer.get("note").getAsString(); //$NON-NLS-1$
        assertTrue("the step left the thread before the wait: " + note, //$NON-NLS-1$
            note.contains("was sent")); //$NON-NLS-1$
        assertTrue("the caller is sent to the tool that catches the stop: " + note, //$NON-NLS-1$
            note.contains("wait_for_break")); //$NON-NLS-1$
        assertTrue("the wait really was cut, not merely reported as cut", //$NON-NLS-1$
            waitedMillis < ASKED_SECONDS * 1000L);
        assertTrue("the step reached the thread", session.stepRequests > 0); //$NON-NLS-1$
    }

    @Test
    public void theSchemaNamesTheCapAndTheSentStep()
    {
        String schema = new DebugStepper().getInputSchema();

        assertTrue("a caller builds its call from the schema: " + schema, //$NON-NLS-1$
            schema.contains(String.valueOf(SuspendWaiter.MAX_TIMEOUT) + " at most")); //$NON-NLS-1$
        assertTrue("a caller builds its call from the schema: " + schema, //$NON-NLS-1$
            schema.contains("already sent")); //$NON-NLS-1$
    }
}
