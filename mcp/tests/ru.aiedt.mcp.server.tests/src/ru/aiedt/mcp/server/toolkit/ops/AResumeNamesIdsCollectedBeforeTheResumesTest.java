/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ru.aiedt.mcp.server.support.DebugSessionBook;
import ru.aiedt.mcp.server.support.FakeDebugFrames;

/**
 * The thread ids a resume answers with are read before the resumes happen.
 *
 * <p>The platform's RESUME event forgets every thread id the application issued, and it can be
 * delivered while the resumes are still being made. Reading an id after the thread it belongs to
 * resumed answered -1: the registry had already been told the application moved on, and the
 * answer named threads the caller could address nothing by. The ids of every thread to be resumed
 * are collected before the first resume goes out, so the answer names them whatever the events
 * do.</p>
 */
public class AResumeNamesIdsCollectedBeforeTheResumesTest
{
    private static final String APP = "d31-resume-ids-before-resume"; //$NON-NLS-1$

    private final DebugSessionBook registry = DebugSessionBook.get();

    @After
    public void forgetTheSessions()
    {
        registry.clearSnapshot(APP);
    }

    @Test
    public void theAnswerNamesEveryThreadEvenWhenThePlatformForgetsMidway() throws Exception
    {
        FakeDebugFrames.Session first = FakeDebugFrames.session(APP);
        long firstId = FakeDebugToolCalls.register(APP, first);
        registry.clearSnapshot(APP);
        FakeDebugFrames.Session second = FakeDebugFrames.session(APP);
        long secondId = FakeDebugToolCalls.register(APP, second);

        FakeDebugFrames.Target target = new FakeDebugFrames.Target(APP);
        target.with(first.thread()).with(second.thread());
        // The RESUME event of the first resume arrives while the resumes are still being made,
        // and the listener forgets every id the application issued.
        second.onResumeRequest = () -> FakeDebugFrames.resumeEvent(APP);

        JsonObject answer = json(DebugResumer.resumeApplication(registry, APP,
            target.asDebugTarget(), false).toJson());

        assertEquals(2, answer.get("resumedCount").getAsInt()); //$NON-NLS-1$
        assertEquals("both threads were resumed", 1, first.resumeRequests); //$NON-NLS-1$
        assertEquals("both threads were resumed", 1, second.resumeRequests); //$NON-NLS-1$
        List<Long> ids = resumedIds(answer);
        assertFalse("no thread this call resumed is named as unknown", ids.contains(Long.valueOf(-1L))); //$NON-NLS-1$
        assertTrue("the id read before the resumes is what the answer names", //$NON-NLS-1$
            ids.contains(Long.valueOf(firstId)));
        assertTrue("the id read before the resumes is what the answer names", //$NON-NLS-1$
            ids.contains(Long.valueOf(secondId)));
    }

    @Test
    public void withoutAnEventMidwayTheAnswerNamesTheSameIds() throws Exception
    {
        FakeDebugFrames.Session first = FakeDebugFrames.session(APP);
        long firstId = FakeDebugToolCalls.register(APP, first);
        registry.clearSnapshot(APP);
        FakeDebugFrames.Session second = FakeDebugFrames.session(APP);
        long secondId = FakeDebugToolCalls.register(APP, second);

        FakeDebugFrames.Target target = new FakeDebugFrames.Target(APP);
        target.with(first.thread()).with(second.thread());

        JsonObject answer = json(DebugResumer.resumeApplication(registry, APP,
            target.asDebugTarget(), false).toJson());

        List<Long> ids = resumedIds(answer);
        assertFalse(ids.contains(Long.valueOf(-1L)));
        assertTrue(ids.contains(Long.valueOf(firstId)));
        assertTrue(ids.contains(Long.valueOf(secondId)));
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
