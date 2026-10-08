/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmReferencesHelper;

/**
 * At the node cap an edge is kept only when both of its ends are in the node list.
 */
public class AModuleEdgeNeverPointsOutsideTheNodeListTest
{
    private static BmReferencesHelper.BfsResult graphOf(Set<String> visited)
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();
        for (String fqn : visited)
        {
            result.nodes.put(fqn, null);
        }
        return result;
    }

    /** Two modules already in the graph keep their edge at a full node list. */
    @Test
    public void anEdgeBetweenTwoTakenModulesPassesAtTheCap()
    {
        Set<String> visited = new HashSet<>(Set.of("CommonModule.A", "CommonModule.B")); //$NON-NLS-1$ //$NON-NLS-2$
        BmReferencesHelper.BfsResult result = graphOf(visited);

        assertTrue(DependencyGraphTool.endpointsFitTheNodeCap(result, visited,
            "CommonModule.A", "CommonModule.B", 2)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.truncated);
    }

    /** An edge to a module the cap keeps out is left out, and the answer says it is cut. */
    @Test
    public void anEdgeToAModuleTheCapKeepsOutIsLeftOut()
    {
        Set<String> visited = new HashSet<>(Set.of("CommonModule.A", "CommonModule.B")); //$NON-NLS-1$ //$NON-NLS-2$
        BmReferencesHelper.BfsResult result = graphOf(visited);

        assertFalse(DependencyGraphTool.endpointsFitTheNodeCap(result, visited,
            "CommonModule.A", "CommonModule.C", 2)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.truncated);
    }

    /** Below the cap a new end is admitted. */
    @Test
    public void anEdgeToANewModulePassesWhileThereIsRoom()
    {
        Set<String> visited = new HashSet<>(Set.of("CommonModule.A")); //$NON-NLS-1$
        BmReferencesHelper.BfsResult result = graphOf(visited);

        assertTrue(DependencyGraphTool.endpointsFitTheNodeCap(result, visited,
            "CommonModule.A", "CommonModule.C", 2)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.truncated);
    }
}
