/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;
import org.junit.Test;

import com.e1c.g5.dt.applications.ExecutionContext;

/**
 * The infobase update asks the UI thread for the active shell while the launch lock is held, so
 * the wait is bounded: a UI thread that does not answer costs the wait, and the update goes on
 * without a shell.
 */
public class AnUpdateWaitsForTheUiThreadWithinABoundTest
{
    /**
     * With the UI thread blocked, building the context returns once the bound runs out, carrying
     * no shell.
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     */
    @Test
    public void aBlockedUiThreadCostsTheWaitAndNoMore() throws InterruptedException
    {
        Display display = Display.getDefault();
        // The display's thread is the one that must not answer; here that is this thread, which
        // waits in join() below and dispatches nothing.
        assumeTrue(display.getThread() == Thread.currentThread());

        AtomicReference<ExecutionContext> built = new AtomicReference<>();
        Thread worker = new Thread(() -> built.set(ApplicationUpdater.buildExecutionContext(200L)),
            "update-context-probe"); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();
        worker.join(10_000L);

        assertFalse("building the context waited on the UI thread past its bound", //$NON-NLS-1$
            worker.isAlive());
        assertNotNull(built.get());
        assertFalse("no shell is named when the UI thread did not answer", //$NON-NLS-1$
            built.get().getProperty(ExecutionContext.ACTIVE_SHELL_NAME).isPresent());
    }
}
