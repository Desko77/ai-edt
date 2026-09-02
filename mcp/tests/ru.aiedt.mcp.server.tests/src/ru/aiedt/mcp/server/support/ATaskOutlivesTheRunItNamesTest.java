/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A task is kept at least as long as the run it names.
 * <p>
 * The lifetime used to be one number for every domain. A domain that accepts an hour-long run then
 * lost its task after thirty minutes: the run carried on, and the caller was left holding a handle
 * that answered with an unknown task. The handle has to outlive the thing it is a handle to.
 * </p>
 */
public class ATaskOutlivesTheRunItNamesTest
{
    private static String openRunIn(PendingWorkRegistry domain, String what)
    {
        String key = PendingWorkRegistry.computeRunKey(what, String.valueOf(System.nanoTime()));
        domain.getOrStart(key, () -> "{}"); //$NON-NLS-1$
        return key;
    }

    @Test
    public void aTaskIsKeptAsLongAsItsDomainKeepsTheRun()
    {
        String key = openRunIn(PendingWorkRegistry.VANESSA, "vanessa-task-lifetime"); //$NON-NLS-1$

        TaskDirectory.Task task = TaskDirectory.getInstance()
            .open(key, "vanessa", "vanessa", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("the domain keeps the run " + PendingWorkRegistry.VANESSA.abandonedTtlMs() //$NON-NLS-1$
            + "ms and the task would go after " + task.lifetimeMs + "ms", //$NON-NLS-1$ //$NON-NLS-2$
            task.lifetimeMs >= PendingWorkRegistry.VANESSA.abandonedTtlMs());
    }

    @Test
    public void aTaskInAnOrdinaryDomainKeepsTheOrdinaryLifetime()
    {
        String key = openRunIn(PendingWorkRegistry.GENERIC, "generic-task-lifetime"); //$NON-NLS-1$

        TaskDirectory.Task task = TaskDirectory.getInstance()
            .open(key, "generic_tool", "generic_tool", null); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("a domain that keeps no run longer changes nothing", //$NON-NLS-1$
            TaskDirectory.TTL_MS, task.lifetimeMs);
    }

    @Test
    public void aTaskForAKeyNoDomainHoldsFallsBackToTheOrdinaryLifetime()
    {
        TaskDirectory.Task task = TaskDirectory.getInstance()
            .open("a-key-no-domain-holds", "something", "something", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(TaskDirectory.TTL_MS, task.lifetimeMs);
    }
}
