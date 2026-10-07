/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;

/**
 * {@code resume} by application id leaves no stopped thread behind.
 *
 * <p>The call is addressed to a session, not to a thread, so it is the whole stopped state of that
 * session the caller is asking to continue. Answering after the snapshot thread alone left the other
 * threads of the same target stopped, and a caller that then waited read a stop that had been left
 * behind rather than the one it was waiting for. The snapshot thread and the target's other stopped
 * threads are collected before any of them resumes, and the answer names every one of them.</p>
 *
 * <p>An explicit thread id stays a call about exactly one thread.</p>
 */
public class AResumeCoversEveryStoppedThreadTest
{
    private static final String APP = "d31-resume-every-thread"; //$NON-NLS-1$
    private static final String FOREIGN_APP = "d31-resume-foreign"; //$NON-NLS-1$
    private static final String NAMED_APP = "d31-resume-named"; //$NON-NLS-1$

    private static final List<String> TOUCHED = List.of(APP, FOREIGN_APP, NAMED_APP);

    private final DebugSessionBook registry = DebugSessionBook.get();

    @After
    public void forgetTheSessions()
    {
        for (String applicationId : TOUCHED)
        {
            registry.clearSnapshot(applicationId);
        }
    }

    @Test
    public void everyStoppedThreadOfTheTargetIsResumed() throws Exception
    {
        FakeDebugFrames.Session first = FakeDebugFrames.session(APP);
        long firstId = FakeDebugToolCalls.register(APP, first);
        registry.clearSnapshot(APP);
        FakeDebugFrames.Session second = FakeDebugFrames.session(APP);
        long secondId = FakeDebugToolCalls.register(APP, second);
        assertEquals("the second stop is the one the snapshot names", secondId, //$NON-NLS-1$
            registry.getSnapshot(APP).threadId);

        FakeDebugFrames.Session running = FakeDebugFrames.session(APP);
        running.suspended = false;
        FakeDebugFrames.Target target = new FakeDebugFrames.Target(APP);
        target.with(first.thread()).with(second.thread()).with(running.thread());

        JsonObject answer = json(DebugResumer.resumeApplication(registry, APP, target.asDebugTarget(),
            false).toJson());

        assertEquals("the call covers the session, not one thread of it", "target", //$NON-NLS-1$ //$NON-NLS-2$
            answer.get("scope").getAsString()); //$NON-NLS-1$
        assertEquals(2, answer.get("resumedCount").getAsInt()); //$NON-NLS-1$
        assertEquals("every stopped thread of the session was resumed", 1, first.resumeRequests); //$NON-NLS-1$
        assertEquals("including the one the snapshot no longer names", 1, second.resumeRequests); //$NON-NLS-1$
        assertEquals("a running thread is left alone", 0, running.resumeRequests); //$NON-NLS-1$
        assertTrue("the caller can address each of them", //$NON-NLS-1$
            resumedIds(answer).contains(Long.valueOf(firstId)));
        assertTrue("the caller can address each of them", //$NON-NLS-1$
            resumedIds(answer).contains(Long.valueOf(secondId)));
        assertFalse("nothing stopped is still recorded as waiting", registry.hasSnapshot(APP)); //$NON-NLS-1$
    }

    @Test
    public void aSessionWithNothingStoppedHasNoThreadToResume() throws Exception
    {
        FakeDebugFrames.Session running = FakeDebugFrames.session(APP);
        running.suspended = false;
        FakeDebugFrames.Target target = new FakeDebugFrames.Target(APP);
        target.with(running.thread());

        assertNull("a running session must be refused, not answered as resumed", //$NON-NLS-1$
            DebugResumer.resumeApplication(registry, APP, target.asDebugTarget(), false));
    }

    @Test
    public void anExplicitThreadIdStillResumesExactlyThatThread() throws Exception
    {
        FakeDebugFrames.Session first = FakeDebugFrames.session(APP);
        FakeDebugToolCalls.register(APP, first);
        registry.clearSnapshot(APP);
        FakeDebugFrames.Session second = FakeDebugFrames.session(APP);
        long secondId = FakeDebugToolCalls.register(APP, second);

        JsonObject answer = json(new DebugResumer().execute(
            FakeDebugToolCalls.args("threadId", String.valueOf(secondId)))); //$NON-NLS-1$

        assertTrue(answer.get("resumed").getAsBoolean()); //$NON-NLS-1$
        assertEquals("a named thread is one thread", "thread", answer.get("scope").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the named thread was resumed", 1, second.resumeRequests); //$NON-NLS-1$
        assertEquals("no other thread of the session was touched", 0, first.resumeRequests); //$NON-NLS-1$
    }

    @Test
    public void aThreadOfAnotherSessionIsRefusedRatherThanResumed() throws Exception
    {
        FakeDebugFrames.Session foreign = FakeDebugFrames.session(FOREIGN_APP);
        long threadId = FakeDebugToolCalls.register(FOREIGN_APP, foreign);

        Map<String, String> params = FakeDebugToolCalls.args(
            "threadId", String.valueOf(threadId), "applicationId", NAMED_APP); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = json(new DebugResumer().execute(params));

        assertEquals(DebugThreadOwnership.REASON, answer.get("reason").getAsString()); //$NON-NLS-1$
        assertEquals(FOREIGN_APP, answer.get("applicationId").getAsString()); //$NON-NLS-1$
        assertEquals("a foreign thread is not resumed", 0, foreign.resumeRequests); //$NON-NLS-1$
    }

    /**
     * @param answer the result document
     * @return the thread ids it names
     */
    private static List<Long> resumedIds(JsonObject answer)
    {
        List<Long> ids = new ArrayList<>();
        for (JsonElement element : answer.get("resumedThreadIds").getAsJsonArray()) //$NON-NLS-1$
        {
            ids.add(Long.valueOf(element.getAsLong()));
        }
        return ids;
    }

    /**
     * @param answer what the tool returned
     * @return the document a client reads
     */
    private static JsonObject json(String answer)
    {
        return FakeDebugToolCalls.json(answer);
    }
}
