/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.DependencyGraphBuilder.Format;

/**
 * Covers the envelope every dependency-graph answer is wrapped in.
 * <p>
 * Filling the graph needs a live model, but the envelope does not, and it is the part a caller
 * reads first: which format came back, how big the graph is, and whether the walk was cut short.
 * That last flag is the one that matters - a truncated graph presented as complete is a conclusion
 * drawn from missing edges - so it is reported whatever the format.
 * </p>
 */
public class DependencyGraphBuilderTest
{
    @Test
    public void anEmptyGraphIsReportedAsEmptyRatherThanRefused()
    {
        Map<String, Object> out = DependencyGraphBuilder.render(new BmReferencesHelper.BfsResult(),
            Format.JSON);

        assertEquals("json", out.get("format")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, out.get("nodeCount")); //$NON-NLS-1$
        assertEquals(0, out.get("edgeCount")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, out.get("truncated")); //$NON-NLS-1$
        assertNotNull("the JSON shape always carries its collections", out.get("nodes")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(out.get("edges")); //$NON-NLS-1$
    }

    @Test
    public void everyTextFormatAnswersWithText()
    {
        for (Format format : new Format[]{Format.MERMAID, Format.PLANTUML, Format.DOT})
        {
            Map<String, Object> out = DependencyGraphBuilder.render(
                new BmReferencesHelper.BfsResult(), format);

            assertEquals(format.name().toLowerCase(), out.get("format")); //$NON-NLS-1$
            assertNotNull(format + " must render to the text key", out.get("text")); //$NON-NLS-1$
        }
    }

    @Test
    public void noFormatMeansJson()
    {
        // The parameter is optional on the wire, and JSON is the shape a caller can always parse.
        assertEquals("json", //$NON-NLS-1$
            DependencyGraphBuilder.render(new BmReferencesHelper.BfsResult(), null).get("format")); //$NON-NLS-1$
    }

    @Test
    public void aTruncatedWalkSaysSoInEveryFormat()
    {
        BmReferencesHelper.BfsResult cutShort = new BmReferencesHelper.BfsResult();
        cutShort.truncated = true;

        for (Format format : Format.values())
        {
            Map<String, Object> out = DependencyGraphBuilder.render(cutShort, format);

            assertEquals(format + " must admit the graph is incomplete", //$NON-NLS-1$
                Boolean.TRUE, out.get("truncated")); //$NON-NLS-1$
            assertEquals("both spellings of the flag have to agree", //$NON-NLS-1$
                out.get("truncated"), out.get("partial")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void everyFormatIsRenderable()
    {
        // Guards the switch: a format added to the enum but not to the renderer would otherwise
        // fall through to whatever the default branch happens to do.
        for (Format format : Format.values())
        {
            Map<String, Object> out = DependencyGraphBuilder.render(
                new BmReferencesHelper.BfsResult(), format);

            assertTrue(format + " produced no payload", //$NON-NLS-1$
                out.containsKey("text") || out.containsKey("nodes")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
