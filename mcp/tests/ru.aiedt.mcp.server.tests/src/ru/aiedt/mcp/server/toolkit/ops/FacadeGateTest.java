/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.settings.ToolCategory;

/**
 * Covers the names a facade has to gate by.
 * <p>
 * Reaching a tool through a facade is still reaching that tool, so a facade asks whether the active
 * preset switched the folded tool off before running it. That question is asked by name, and the
 * debugger facade is the one that renamed several of what it folds in: {@code action=launch} reaches
 * {@code debug_launch}, {@code action=evaluate} reaches {@code evaluate_expression}. Gating the
 * short spelling would leave the tool's own name an open door and look like it was covered.
 * </p>
 * <p>
 * That every facade calls the gate at all is structural rather than behavioural, so it is checked by
 * {@code scripts/check-facade-gates.py} in CI instead of here - a source-reading test cannot find the
 * sources from inside the OSGi test runtime, and one that quietly skips proves nothing.
 * </p>
 */
public class FacadeGateTest
{
    private static Set<String> everyGroupedToolName()
    {
        Set<String> known = new LinkedHashSet<>();
        for (ToolCategory group : ToolCategory.values())
        {
            known.addAll(group.getToolNames());
        }
        return known;
    }

    @Test
    public void everyRenamedDebuggerActionResolvesToARealToolName()
    {
        // A mapping pointing at a name no preset knows would gate nothing while looking like it did.
        Set<String> known = everyGroupedToolName();
        for (String action : new String[] { "launch", "add_breakpoint", "get_state", "terminate", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "evaluate", "step_over", "step_into", "step_out" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String canonical = LaunchDebuggerTool.canonicalName(action);
            assertTrue(action + " maps to '" + canonical + "', which is not a tool any preset knows", //$NON-NLS-1$ //$NON-NLS-2$
                known.contains(canonical));
        }
    }

    @Test
    public void aRenamedActionDoesNotResolveToItself()
    {
        // The point of the map is that these two differ; an entry that maps a name to itself would be
        // a rename nobody noticed had been undone.
        assertEquals("debug_launch", LaunchDebuggerTool.canonicalName("launch")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("evaluate_expression", LaunchDebuggerTool.canonicalName("evaluate")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("set_breakpoint", LaunchDebuggerTool.canonicalName("add_breakpoint")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void allThreeStepDirectionsAnswerToTheOneTool()
    {
        // They are one tool with a mode, so switching that tool off has to stop all three.
        String stepper = LaunchDebuggerTool.canonicalName("step_over"); //$NON-NLS-1$
        assertEquals(stepper, LaunchDebuggerTool.canonicalName("step_into")); //$NON-NLS-1$
        assertEquals(stepper, LaunchDebuggerTool.canonicalName("step_out")); //$NON-NLS-1$
        assertTrue(everyGroupedToolName().contains(stepper));
    }

    @Test
    public void anActionThatNeedsNoRenamingIsLeftAlone()
    {
        assertEquals("debug_status", LaunchDebuggerTool.canonicalName("debug_status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("resume", LaunchDebuggerTool.canonicalName("resume")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anUnknownActionIsHandedBackUnchanged()
    {
        // The gate then finds it in nobody's disabled set and lets it through to the switch, which
        // rejects it as unknown - the facade's own job, not the gate's.
        assertEquals("no_such_action", LaunchDebuggerTool.canonicalName("no_such_action")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(null, LaunchDebuggerTool.canonicalName(null));
    }
}
