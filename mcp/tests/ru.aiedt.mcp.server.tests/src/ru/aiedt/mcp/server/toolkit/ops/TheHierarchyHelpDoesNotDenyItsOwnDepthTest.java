/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * The facade forwards the depth of a call hierarchy, and its help no longer says it does not.
 *
 * <p>Found by cross-review 12.09 and measured against the code: the help printed "single level - no
 * recursion depth" and, two lines below, the delegate's own depth argument with "default 1, max 5".
 * Nothing in the routing drops the argument - the delegate grew multi-hop walking and the two
 * sentences were left behind. An agent reads the sentence and stops using what works.
 */
public class TheHierarchyHelpDoesNotDenyItsOwnDepthTest
{
    @Test
    public void theHelpDoesNotDenyTheDepth()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("topic", "call_hierarchy"); //$NON-NLS-1$ //$NON-NLS-2$

        String help = new CodeSearchTool().execute(params);

        assertFalse("the help used to deny the depth the same page documents", //$NON-NLS-1$
            help.contains("no recursion depth")); //$NON-NLS-1$
        assertTrue("and now it says what walking callers of callers costs", //$NON-NLS-1$
            help.contains("depth")); //$NON-NLS-1$
    }

    @Test
    public void theDepthReachesTheDelegate()
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "call_hierarchy"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("direction", "incoming"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("depth", "3"); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> prepared = CodeSearchTool.prepareForDelegate("call_hierarchy", params); //$NON-NLS-1$

        assertTrue("a depth the caller passed has to survive the routing", //$NON-NLS-1$
            "3".equals(prepared.get("depth"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and the facade's vocabulary is translated to the delegate's", //$NON-NLS-1$
            "callers".equals(prepared.get("direction"))); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
