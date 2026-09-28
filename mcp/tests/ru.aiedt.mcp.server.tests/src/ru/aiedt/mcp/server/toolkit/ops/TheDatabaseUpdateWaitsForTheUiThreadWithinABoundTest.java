/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;
import org.junit.Test;

import com.e1c.g5.dt.applications.ExecutionContext;

/**
 * update_database asks the UI thread for the active shell before it loads the configuration; the
 * wait is bounded the same way as for a launch, so a blocked UI thread costs the wait and no more.
 */
public class TheDatabaseUpdateWaitsForTheUiThreadWithinABoundTest
{
    /**
     * With the UI thread blocked, the update's context is built once the bound runs out, carrying
     * no shell.
     *
     * @throws InterruptedException if the test thread is interrupted while waiting
     */
    @Test
    public void aBlockedUiThreadCostsTheWaitAndNoMore() throws InterruptedException
    {
        Display display = Display.getDefault();
        // This thread owns the display and dispatches nothing while it waits in join() below.
        assumeTrue(display.getThread() == Thread.currentThread());

        AtomicReference<ExecutionContext> built = new AtomicReference<>();
        Thread worker = new Thread(() -> built.set(DatabaseUpdater.contextWithActiveShell()),
            "database-update-context-probe"); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();
        worker.join(15_000L);

        assertFalse("the update's context waited on the UI thread past its bound", //$NON-NLS-1$
            worker.isAlive());
        assertNotNull(built.get());
        assertFalse("no shell is named when the UI thread did not answer", //$NON-NLS-1$
            built.get().getProperty(ExecutionContext.ACTIVE_SHELL_NAME).isPresent());
    }
}
