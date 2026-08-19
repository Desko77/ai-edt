/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire.jsonrpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.TaskDirectory;
import ru.aiedt.mcp.server.wire.McpServerMeta;

/**
 * Pins the two shapes a task takes on the wire and, above all, which is which.
 * <p>
 * The whole point of {@code resultType} is that a client can tell "here is your answer" from "here
 * is where your answer will be" without inspecting the rest. Getting that field wrong on either
 * shape would have a client treat a handle as the answer, or go on polling something that already
 * finished.
 * </p>
 */
public class TaskResultTest
{
    private TaskDirectory directory;

    @Before
    public void emptyDirectory()
    {
        directory = TaskDirectory.getInstance();
        directory.clear();
    }

    @After
    public void tidyUp()
    {
        directory.clear();
    }

    /** A handle says it is not the answer, and says where the answer will be. */
    @Test
    public void aHandleSaysItIsNotTheAnswer()
    {
        TaskResult handle = TaskResult.handle(task());

        assertEquals(McpServerMeta.RESULT_TASK, handle.getResultType());
        assertNotNull(handle.getTaskId());
        assertEquals(TaskDirectory.WORKING, handle.getStatus());
        assertEquals(Long.valueOf(TaskDirectory.TTL_MS), handle.getTtlMs());
        assertEquals(Long.valueOf(TaskDirectory.POLL_INTERVAL_MS), handle.getPollIntervalMs());
        assertNull("a handle carries no answer - that is what makes it a handle", //$NON-NLS-1$
            handle.getResult());
        assertNull(handle.getError());
    }

    /**
     * A poll is a finished answer about an unfinished thing.
     * <p>
     * Worth stating because it reads as a contradiction: the task may well still be working, but
     * the ANSWER to {@code tasks/get} is complete - the client asked where the task had got to and
     * this is where it has got to.
     * </p>
     */
    @Test
    public void aPollIsItselfAFinishedAnswer()
    {
        TaskResult state = TaskResult.state(task(), null, null);

        assertEquals(McpServerMeta.RESULT_COMPLETE, state.getResultType());
    }

    /** A finished task carries what the original call would have returned. */
    @Test
    public void aFinishedTaskCarriesTheOriginalCallsResult()
    {
        TaskResult state = TaskResult.state(task(), ToolCallResult.text("the answer"), null); //$NON-NLS-1$

        assertNotNull(state.getResult());
        assertNull(state.getError());
    }

    /** A failed task carries the error instead. */
    @Test
    public void aFailedTaskCarriesTheErrorInstead()
    {
        TaskResult state = TaskResult.state(task(), null, new LinkedHashMap<String, Object>());

        assertNull(state.getResult());
        assertNotNull(state.getError());
    }

    /** The timestamps are ISO 8601 instants, which is what the extension asks for. */
    @Test
    public void theTimestampsAreInstantsNotMilliseconds()
    {
        TaskResult handle = TaskResult.handle(task());

        assertTrue("createdAt is not an instant: " + handle.getCreatedAt(), //$NON-NLS-1$
            handle.getCreatedAt().endsWith("Z") && handle.getCreatedAt().contains("T")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(handle.getLastUpdatedAt());
        assertEquals("both instants must parse", //$NON-NLS-1$
            java.time.Instant.parse(handle.getCreatedAt()).getClass(),
            java.time.Instant.parse(handle.getLastUpdatedAt()).getClass());
    }

    /** The status message travels with the task, so a person reading a poll learns something. */
    @Test
    public void theStatusMessageTravelsWithIt()
    {
        assertTrue("a poll should say what is going on: " + TaskResult.handle(task()).getStatusMessage(), //$NON-NLS-1$
            TaskResult.handle(task()).getStatusMessage().contains("find_references")); //$NON-NLS-1$
    }

    private TaskDirectory.Task task()
    {
        return directory.open("a-key", "find_references", "find_references", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            new LinkedHashMap<>());
    }
}
