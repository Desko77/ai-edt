/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import org.eclipse.swt.widgets.Display;
import org.junit.Test;

/**
 * The activator creates no display of its own: without a running workbench the UI contributions
 * stay pending, and the call that would place them is a no-op that leaves the thread's display
 * as it was - none, or the one another test made. A display created here would be the one the
 * workbench later finds on the wrong thread.
 */
public class TheActivatorLeavesTheWorkbenchAloneUntilItRunsTest
{
    @Test
    public void completingTheUiWithoutAWorkbenchCreatesNoDisplay()
    {
        Activator activator = Activator.getDefault();
        assertNotNull(activator);
        Display before = Display.findDisplay(Thread.currentThread());
        activator.completeUiInitialization();
        assertSame("the activator must not create a display on this thread", before, //$NON-NLS-1$
            Display.findDisplay(Thread.currentThread()));
    }
}
