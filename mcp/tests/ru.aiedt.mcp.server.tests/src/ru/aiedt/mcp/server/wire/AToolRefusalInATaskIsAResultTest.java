/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.TaskDirectory;

/**
 * A task that failed on the tool's own refusal answers that refusal as its result.
 * <p>
 * The tasks specification asks {@code tasks/result} for exactly what the call would have returned:
 * a tool refusal is a result with {@code isError}, so only a run that produced no answer reports a
 * JSON-RPC error.
 * </p>
 */
public class AToolRefusalInATaskIsAResultTest
{
    private TaskDirectory directory;

    private final List<String> keysToClean = new ArrayList<>();

    @Before
    public void emptyDirectory()
    {
        directory = TaskDirectory.getInstance();
        directory.clear();
    }

    @After
    public void releaseRuns()
    {
        for (String key : keysToClean)
        {
            PendingWorkRegistry.GENERIC.remove(key);
        }
        keysToClean.clear();
        directory.clear();
    }

    /** A refusal fails the task and leaves no JSON-RPC error to report. */
    @Test
    public void aRefusalReportsNoError() throws Exception
    {
        TaskDirectory.Task task = settled(() -> ToolResult.error("the object is not there").toJson()); //$NON-NLS-1$

        assertEquals(TaskDirectory.FAILED, task.status);
        assertNotNull(task.result);
        assertNull("the refusal goes out as the result, not as an error", //$NON-NLS-1$
            McpRequestRouter.failureOf(task));
    }

    /** A run the server no longer holds has no answer, and reports the failure as a JSON-RPC error. */
    @Test
    public void aRunThatVanishedReportsAnError() throws Exception
    {
        TaskDirectory.Task task = directory.open("no-such-run", "op", "tool", new LinkedHashMap<>()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        TaskDirectory.Task polled = directory.poll(task.taskId);

        assertEquals(TaskDirectory.FAILED, polled.status);
        assertNull(polled.result);
        Object error = McpRequestRouter.failureOf(polled);
        assertTrue(error instanceof Map);
        assertEquals(polled.failure, ((Map<?, ?>)error).get("message")); //$NON-NLS-1$
    }

    private TaskDirectory.Task settled(Supplier<String> body) throws InterruptedException
    {
        String key = PendingWorkRegistry.computeRunKey("refusal-result", //$NON-NLS-1$
            String.valueOf(System.nanoTime()));
        keysToClean.add(key);
        PendingWorkRegistry.GENERIC.getOrStart(key, body);
        PendingWorkRegistry.PendingEntry entry = PendingWorkRegistry.GENERIC.get(key);
        for (int attempt = 0; attempt < 100 && !entry.isDone(); attempt++)
        {
            Thread.sleep(20L);
        }
        TaskDirectory.Task task = directory.open(key, "edit_metadata", "edit_metadata", //$NON-NLS-1$ //$NON-NLS-2$
            new LinkedHashMap<>());
        for (int attempt = 0; attempt < 100; attempt++)
        {
            TaskDirectory.Task polled = directory.poll(task.taskId);
            if (polled.isTerminal())
            {
                return polled;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("the task never settled"); //$NON-NLS-1$
    }
}
