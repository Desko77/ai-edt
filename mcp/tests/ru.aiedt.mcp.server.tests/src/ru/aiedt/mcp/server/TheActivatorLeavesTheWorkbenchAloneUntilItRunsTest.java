/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.eclipse.swt.widgets.Display;
import org.junit.Test;

/**
 * The activator creates no display of its own: without a running workbench the UI contributions
 * stay pending, and the call that would place them is a no-op that leaves the thread without a
 * display. A display created here would be the one the workbench later finds on the wrong thread.
 */
public class TheActivatorLeavesTheWorkbenchAloneUntilItRunsTest
{
    @Test
    public void completingTheUiWithoutAWorkbenchCreatesNoDisplay()
    {
        Activator activator = Activator.getDefault();
        assertNotNull(activator);
        activator.completeUiInitialization();
        assertNull("the activator must not create a display on this thread", Display.findDisplay(Thread.currentThread())); //$NON-NLS-1$
        assertNull(Display.getCurrent());
    }
}
