/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import ru.aiedt.mcp.server.support.DependencyGraphBuilder.Format;

/**
 * The cycle search of a dependency graph finds a cycle whatever its length.
 * <p>
 * The search descended one call per node, so a chain as long as the graph used thread stack in
 * proportion to it and ended in {@link StackOverflowError}, which no caller of the render catches.
 * </p>
 */
public class ACycleAsLongAsTheGraphIsFoundTest
{
    /** A stack the recursive search runs out of on the ring below. */
    private static final long SMALL_STACK = 256 * 1024;

    private static final int RING = 100_000;

    @Test
    public void aRingOfAHundredThousandNodesIsOneCycle() throws Exception
    {
        BmReferencesHelper.BfsResult bfs = new BmReferencesHelper.BfsResult();
        for (int i = 0; i < RING; i++)
        {
            bfs.nodes.put("N" + i, null); //$NON-NLS-1$
            bfs.edges.add(new BmReferencesHelper.Edge("N" + i, "N" + ((i + 1) % RING), null)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        AtomicReference<Map<String, Object>> out = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread search = new Thread(null, () -> {
            try
            {
                out.set(DependencyGraphBuilder.render(bfs, Format.JSON));
            }
            catch (Throwable t)
            {
                failure.set(t);
            }
        }, "cycle-search", SMALL_STACK); //$NON-NLS-1$
        search.start();
        search.join();

        assertNull("the search must not run out of stack: " + failure.get(), failure.get()); //$NON-NLS-1$
        List<?> cycles = (List<?>) out.get().get("cycles"); //$NON-NLS-1$
        assertEquals(1, cycles.size());
        assertEquals(RING, ((List<?>) cycles.get(0)).size());
    }

    @Test
    public void theComponentsComeOutInTheOrderTheSearchClosesThem()
    {
        BmReferencesHelper.BfsResult bfs = new BmReferencesHelper.BfsResult();
        for (String node : new String[]{"a", "b", "c", "d", "e"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            bfs.nodes.put(node, null);
        }
        edge(bfs, "a", "b"); //$NON-NLS-1$ //$NON-NLS-2$
        edge(bfs, "b", "a"); //$NON-NLS-1$ //$NON-NLS-2$
        edge(bfs, "b", "c"); //$NON-NLS-1$ //$NON-NLS-2$
        edge(bfs, "c", "d"); //$NON-NLS-1$ //$NON-NLS-2$
        edge(bfs, "d", "e"); //$NON-NLS-1$ //$NON-NLS-2$
        edge(bfs, "e", "c"); //$NON-NLS-1$ //$NON-NLS-2$

        Object cycles = DependencyGraphBuilder.render(bfs, Format.JSON).get("cycles"); //$NON-NLS-1$

        assertEquals(List.of(List.of("e", "d", "c"), List.of("b", "a")), cycles); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    @Test
    public void aChainWithoutABackEdgeHasNoCycle()
    {
        BmReferencesHelper.BfsResult bfs = new BmReferencesHelper.BfsResult();
        for (String node : new String[]{"a", "b", "c"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            bfs.nodes.put(node, null);
        }
        edge(bfs, "a", "b"); //$NON-NLS-1$ //$NON-NLS-2$
        edge(bfs, "b", "c"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(List.of(), DependencyGraphBuilder.render(bfs, Format.JSON).get("cycles")); //$NON-NLS-1$
    }

    private static void edge(BmReferencesHelper.BfsResult bfs, String from, String to)
    {
        bfs.edges.add(new BmReferencesHelper.Edge(from, to, null));
    }
}
