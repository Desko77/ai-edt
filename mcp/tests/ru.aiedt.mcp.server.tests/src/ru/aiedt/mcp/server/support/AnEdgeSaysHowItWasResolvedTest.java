/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmReferencesHelper.Edge;
import ru.aiedt.mcp.server.support.BmReferencesHelper.Side;

/**
 * An edge of the JSON answer says how it was resolved, and a name-resolved edge lists the places
 * its calls sit at.
 * <p>
 * The walk stamps the resolution and collects the places; this test reads the rendering of a graph
 * whose edges already carry both. The places are fed from more than one file on purpose: the
 * rendering has to order them whatever order they arrived in.
 * </p>
 */
public class AnEdgeSaysHowItWasResolvedTest
{
    /** A name-resolved edge lists its places in file-then-line order and counts them all. */
    @Test
    public void aNameResolvedEdgeListsItsPlacesInOrder()
    {
        Edge edge = new Edge("CommonModule.A.Module", "CommonModule.B.Module", "calls", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        edge.resolution = "name"; //$NON-NLS-1$
        edge.observeName("CommonModule.B.Module", 30); //$NON-NLS-1$
        edge.observeName("CommonModule.A.Module", 12); //$NON-NLS-1$
        edge.observeName("CommonModule.A.Module", 7); //$NON-NLS-1$

        Map<String, Object> rendered = renderedEdgeOf(edge);

        assertEquals("name", rendered.get("resolution")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(3), rendered.get("count")); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sites = (List<Map<String, Object>>)rendered.get("sites"); //$NON-NLS-1$
        assertEquals(3, sites.size());
        assertEquals("CommonModule.A.Module", sites.get(0).get("file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(7), sites.get(0).get("line")); //$NON-NLS-1$
        assertEquals("CommonModule.A.Module", sites.get(1).get("file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(12), sites.get(1).get("line")); //$NON-NLS-1$
        assertEquals("CommonModule.B.Module", sites.get(2).get("file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Integer.valueOf(30), sites.get(2).get("line")); //$NON-NLS-1$
    }

    /** More places than the list carries are cut, and the count keeps the full number. */
    @Test
    public void morePlacesThanTheListCarriesAreCounted()
    {
        Edge edge = new Edge("CommonModule.A.Module", "CommonModule.B.Module", "calls", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        edge.resolution = "name"; //$NON-NLS-1$
        for (int line = 1; line <= 6; line++)
        {
            edge.observeName("CommonModule.A.Module", line); //$NON-NLS-1$
        }

        Map<String, Object> rendered = renderedEdgeOf(edge);

        assertEquals(Integer.valueOf(6), rendered.get("count")); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sites = (List<Map<String, Object>>)rendered.get("sites"); //$NON-NLS-1$
        assertEquals(5, sites.size());
        assertEquals(Integer.valueOf(1), sites.get(0).get("line")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(5), sites.get(4).get("line")); //$NON-NLS-1$
    }

    /** A model-resolved edge says so and lists no places. */
    @Test
    public void aModelResolvedEdgeListsNoPlaces()
    {
        Edge edge = new Edge("CommonModule.A.Module", "CommonModule.B.Module", "calls", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Side.FORWARD);
        edge.resolution = "model"; //$NON-NLS-1$
        edge.observeName("CommonModule.A.Module", 7); //$NON-NLS-1$

        Map<String, Object> rendered = renderedEdgeOf(edge);

        assertEquals("model", rendered.get("resolution")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a model-resolved edge lists no places", rendered.containsKey("sites")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("one reference stays unwritten, as before", rendered.containsKey("count")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An edge of a level that does not say how it was resolved says nothing about it. */
    @Test
    public void anEdgeOfALevelThatDoesNotSayStaysSilent()
    {
        Edge edge = new Edge("Catalog.A", "Catalog.B", "attributes"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Map<String, Object> rendered = renderedEdgeOf(edge);

        assertFalse(rendered.containsKey("resolution")); //$NON-NLS-1$
        assertFalse(rendered.containsKey("sites")); //$NON-NLS-1$
        assertTrue("from and to stay as they were", rendered.containsKey("from")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The rendered form of the only edge of a graph.
     *
     * @param edge the edge to render
     * @return the edge's JSON object
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> renderedEdgeOf(Edge edge)
    {
        BmReferencesHelper.BfsResult bfs = new BmReferencesHelper.BfsResult();
        bfs.edges.add(edge);
        Map<String, Object> out = DependencyGraphBuilder.render(bfs, DependencyGraphBuilder.Format.JSON);
        List<Map<String, Object>> edges = (List<Map<String, Object>>)out.get("edges"); //$NON-NLS-1$
        return edges.get(0);
    }
}
