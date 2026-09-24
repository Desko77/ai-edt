/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmReferencesHelper;

/**
 * On the module level one connection between two modules is one row.
 * <p>
 * The two halves of the call graph name the same connection: walking the caller reports it as an
 * outgoing edge and walking the callee reports it as an incoming one, so under
 * {@code direction=both} the same connection arrived twice and was recorded twice.
 * </p>
 */
public class AModuleEdgeIsOneRowWhicheverEndReportsItTest
{
    /** The same connection reported from both halves is one row, with the tally of one reference. */
    @Test
    public void oneConnectionReportedTwiceIsOneRow()
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();

        assertTrue(DependencyGraphTool.addModuleEdge(result, "CommonModule.A", "CommonModule.B", //$NON-NLS-1$ //$NON-NLS-2$
            BmReferencesHelper.Side.FORWARD, 1));
        assertTrue(DependencyGraphTool.addModuleEdge(result, "CommonModule.A", "CommonModule.B", //$NON-NLS-1$ //$NON-NLS-2$
            BmReferencesHelper.Side.BACKWARD, 1));

        assertEquals(1, result.edges.size());
        assertEquals("calls", result.edges.get(0).featureName); //$NON-NLS-1$
        assertEquals("the two halves report one reference each", 1, result.edges.get(0).count); //$NON-NLS-1$
        assertFalse("a merged row is not a row the cap refused", result.truncated); //$NON-NLS-1$
    }

    /** A connection the cap refuses says the walk was cut, and a repeat of a known one does not. */
    @Test
    public void aNewConnectionOverTheCapIsRefusedAndSaysSo()
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();

        assertTrue(DependencyGraphTool.addModuleEdge(result, "CommonModule.A", "CommonModule.B", //$NON-NLS-1$ //$NON-NLS-2$
            BmReferencesHelper.Side.FORWARD, 1));
        assertFalse(DependencyGraphTool.addModuleEdge(result, "CommonModule.B", "CommonModule.C", //$NON-NLS-1$ //$NON-NLS-2$
            BmReferencesHelper.Side.FORWARD, 1));

        assertEquals(1, result.edges.size());
        assertTrue(result.truncated);
    }

    /** An end that is not there and a connection of a module with itself describe nothing. */
    @Test
    public void anEdgeWithoutTwoDistinctEndsIsNotRecorded()
    {
        BmReferencesHelper.BfsResult result = new BmReferencesHelper.BfsResult();

        assertFalse(DependencyGraphTool.addModuleEdge(result, null, "CommonModule.B", //$NON-NLS-1$
            BmReferencesHelper.Side.FORWARD, 10));
        assertFalse(DependencyGraphTool.addModuleEdge(result, "CommonModule.A", null, //$NON-NLS-1$
            BmReferencesHelper.Side.FORWARD, 10));
        assertFalse(DependencyGraphTool.addModuleEdge(result, "CommonModule.A", "CommonModule.A", //$NON-NLS-1$ //$NON-NLS-2$
            BmReferencesHelper.Side.FORWARD, 10));

        assertTrue(result.edges.isEmpty());
        assertFalse(result.truncated);
    }
}
