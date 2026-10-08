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

import org.junit.Test;

/**
 * {@code remove_breakpoint} takes the coordinates under the names its schema uses.
 *
 * <p>The facade schema names a module {@code modulePath} and a line {@code line}, and every breakpoint
 * action is described by that schema, so a caller that read it and sent those two was answered as if it
 * had named no coordinates at all - a valid call refused for missing arguments it had just supplied.
 * Both names are read now, and the ones this tool shipped with still win when both arrive.</p>
 */
public class ABreakpointIsAddressedByEitherNameTest
{
    private static final String MODULE = "CommonModules/МойМодуль/Module.bsl"; //$NON-NLS-1$

    @Test
    public void theModuleIsReadFromEitherName()
    {
        assertEquals(MODULE, BreakpointRemover.moduleOf(FakeDebugToolCalls.args("module", MODULE))); //$NON-NLS-1$
        assertEquals("the schema name is read too", MODULE, //$NON-NLS-1$
            BreakpointRemover.moduleOf(FakeDebugToolCalls.args("modulePath", MODULE))); //$NON-NLS-1$
        assertEquals("an empty name is not a name", MODULE, //$NON-NLS-1$
            BreakpointRemover.moduleOf(FakeDebugToolCalls.args(
                "module", "", "modulePath", MODULE))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("the name this tool shipped with wins", "CommonModules/Old/Module.bsl", //$NON-NLS-1$ //$NON-NLS-2$
            BreakpointRemover.moduleOf(FakeDebugToolCalls.args(
                "module", "CommonModules/Old/Module.bsl", "modulePath", MODULE))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(BreakpointRemover.moduleOf(FakeDebugToolCalls.args())); //$NON-NLS-1$
    }

    @Test
    public void theLineIsReadFromEitherName()
    {
        assertEquals(42, BreakpointRemover.lineOf(FakeDebugToolCalls.args("lineNumber", "42"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the schema name is read too", 42, //$NON-NLS-1$
            BreakpointRemover.lineOf(FakeDebugToolCalls.args("line", "42"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the name this tool shipped with wins", 7, //$NON-NLS-1$
            BreakpointRemover.lineOf(FakeDebugToolCalls.args("lineNumber", "7", "line", "42"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("a line counts from 1, so zero names none", 42, //$NON-NLS-1$
            BreakpointRemover.lineOf(FakeDebugToolCalls.args("lineNumber", "0", "line", "42"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("no line named is -1", -1, BreakpointRemover.lineOf(FakeDebugToolCalls.args())); //$NON-NLS-1$
    }

    @Test
    public void aCallBySchemaNamesIsNotRefusedForMissingCoordinates()
    {
        String answer = new BreakpointRemover().execute(FakeDebugToolCalls.args(
            "modulePath", MODULE, "line", "42")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        String said = FakeDebugToolCalls.error(answer);
        assertFalse("the coordinates arrived, so they cannot be missing: " + said, //$NON-NLS-1$
            said.contains("Provide either breakpointId")); //$NON-NLS-1$
        assertTrue("what stops the call is the workspace it has not got: " + said, //$NON-NLS-1$
            said.contains("Could not find module file")); //$NON-NLS-1$
    }
}
