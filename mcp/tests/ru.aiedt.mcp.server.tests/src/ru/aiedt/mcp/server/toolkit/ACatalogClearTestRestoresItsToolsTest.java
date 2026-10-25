/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The clear test of the catalogue policy leaves the shared OSGi runtime with the catalogue's own
 * tools still in place.
 * <p>
 * {@link McpToolCatalog#clear()} forgets this server's own tools by contract - the server
 * registers its list again after every clear - so a test that clears and stops there leaves the
 * shared runtime without one own tool. The existing
 * {@link AMixedExternalToolKeepsItsReadsUnderWriteBlockingPresetsTest#thePolicySurvivesAClear()}
 * is that test. This one registers a plain tool of its own, runs that exact fixture - its
 * set-up, its clear test, its tear-down, the methods themselves - and expects the same own tool
 * to answer by name once the fixture is over, together with every own tool the catalogue held
 * before it. Whatever the fixture answers, the finally here puts the catalogue's own tools back
 * and removes the probe, so a red run of this test damages the shared runtime no more than a
 * green one.
 * </p>
 */
public class ACatalogClearTestRestoresItsToolsTest
{
    private static final String OWN_PROBE = "regression_probe_own_tool"; //$NON-NLS-1$

    /**
     * Runs the existing clear test as a fixture and expects the catalogue's own tools to survive
     * it: the probe this test registered as an own tool answers by name as the same instance,
     * and so does every own tool that was there before the fixture ran.
     */
    @Test
    public void theExistingClearTestLeavesTheCataloguesOwnToolsCallable()
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        List<IMcpTool> ownBefore = ownToolsOf(catalog);
        IMcpTool probe = new PlainProbe();
        catalog.register(probe);
        try
        {
            AMixedExternalToolKeepsItsReadsUnderWriteBlockingPresetsTest fixture =
                new AMixedExternalToolKeepsItsReadsUnderWriteBlockingPresetsTest();
            fixture.aContextAStoreAndAnEmptyHistory();
            try
            {
                fixture.thePolicySurvivesAClear();
            }
            finally
            {
                fixture.thePresetAndTheProbesGo();
            }

            assertSame("a clear test puts back the own tools it cleared, the probe included", //$NON-NLS-1$
                probe, catalog.getTool(OWN_PROBE));
            for (IMcpTool own : ownBefore)
            {
                assertNotNull("a clear test must not lose the own tool " + own.getName(), //$NON-NLS-1$
                    catalog.getTool(own.getName()));
            }
        }
        finally
        {
            for (IMcpTool own : ownBefore)
            {
                if (catalog.getTool(own.getName()) == null)
                {
                    catalog.register(own);
                }
            }
            catalog.unregister(OWN_PROBE);
        }
    }

    /**
     * The catalogue's own tools: every registered tool that no other bundle published.
     *
     * @param catalog the catalogue
     * @return the own tools, in registry order
     */
    private static List<IMcpTool> ownToolsOf(McpToolCatalog catalog)
    {
        List<IMcpTool> own = new ArrayList<>();
        for (IMcpTool tool : catalog.getAllTools())
        {
            if (!catalog.getExternalTools().containsValue(tool))
            {
                own.add(tool);
            }
        }
        return own;
    }

    /**
     * A plain tool of this server: one name, no write doors, an execute that answers and changes
     * nothing. Registered the way the server registers its own - straight into the catalogue,
     * not through the whiteboard - so a clear that forgets own tools forgets it.
     */
    private static final class PlainProbe implements IMcpTool
    {
        /** Returns the fixed name of the synthetic registered tool. */
        @Override
        public String getName()
        {
            return OWN_PROBE;
        }

        /** Returns the description without changing the tool or registry. */
        @Override
        public String getDescription()
        {
            return "a probe of the catalogue's own tools"; //$NON-NLS-1$
        }

        /** Returns the empty object schema of the synthetic tool. */
        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        /**
         * Returns a fixed answer without modifying state.
         *
         * @param params unused probe arguments
         * @return the fixed probe answer
         */
        @Override
        public String execute(Map<String, String> params)
        {
            return "ran"; //$NON-NLS-1$
        }
    }
}
