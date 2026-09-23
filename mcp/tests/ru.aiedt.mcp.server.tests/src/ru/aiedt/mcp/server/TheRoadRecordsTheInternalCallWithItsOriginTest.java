/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

import ru.aiedt.mcp.server.support.ToolRoad;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolWhiteboard;

/**
 * An internal call is its own entry in the history: it names the bundle that made it, and its
 * arguments are masked by the same keys a wire call's are.
 */
public class TheRoadRecordsTheInternalCallWithItsOriginTest
{
    private BundleContext context;

    private final java.util.List<ServiceRegistration<?>> registrations = new java.util.ArrayList<>();

    @Before
    public void aContextAndAnEmptyHistory()
    {
        context = FrameworkUtil.getBundle(TheRoadRecordsTheInternalCallWithItsOriginTest.class)
            .getBundleContext();
        McpHistory.clear();
    }

    @After
    public void theProbeGoes()
    {
        for (ServiceRegistration<?> registration : registrations)
        {
            try
            {
                registration.unregister();
            }
            catch (IllegalStateException alreadyGone)
            {
                // unregistered by the test itself
            }
        }
    }

    /** The entry carries the caller's symbolic name and a masked credential. */
    @Test
    public void theEntryNamesItsOriginAndMasksItsCredentials()
    {
        String name = "road_history_probe_" + System.nanoTime(); //$NON-NLS-1$
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.WRITES, Boolean.FALSE);
        registrations.add(context.registerService(IMcpTool.class, new PlainProbe(name), properties));
        waitFor(name);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("password", "s3cret-value"); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.put("plain", "visible-value"); //$NON-NLS-1$ //$NON-NLS-2$
        ru.aiedt.mcp.server.toolkit.ToolRoadOutcome outcome =
            new ToolRoad(new Semaphore(1)).call(name, arguments, "origin-bundle-x"); //$NON-NLS-1$

        assertEquals("ran", outcome.text()); //$NON-NLS-1$
        Map<String, Object> entry = McpHistory.recent(1).get(0);
        assertEquals(name, entry.get("tool")); //$NON-NLS-1$
        assertEquals("the entry names the calling bundle", "origin-bundle-x", entry.get("origin")); //$NON-NLS-1$ //$NON-NLS-2$
        String args = String.valueOf(entry.get("args")); //$NON-NLS-1$
        assertTrue("an ordinary argument is kept: " + args, args.contains("visible-value")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a credential never reaches the history: " + args, args.contains("s3cret-value")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the credential is masked by the same key the wire masks", args.contains("***")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The activator publishes the road as the service a bundle calls. */
    @Test
    public void theRoadIsPublishedAsAService()
    {
        ru.aiedt.mcp.server.toolkit.IToolRoad published =
            context.getServiceReference(ru.aiedt.mcp.server.toolkit.IToolRoad.class) == null ? null
                : context.getService(
                    context.getServiceReference(ru.aiedt.mcp.server.toolkit.IToolRoad.class));
        assertTrue("the road answers as its published service", published != null); //$NON-NLS-1$
        ru.aiedt.mcp.server.toolkit.ToolRoadOutcome refused =
            published.call("no_such_tool_at_all", Map.of(), "service-test"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(refused.refused());
    }

    private static void waitFor(String name)
    {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (McpToolCatalog.getInstance().getTool(name) == null)
        {
            if (System.currentTimeMillis() > deadline)
            {
                throw new IllegalStateException("the whiteboard never picked up " + name); //$NON-NLS-1$
            }
            sleep(20L);
        }
    }

    private static void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", e); //$NON-NLS-1$
        }
    }

    /** The least a tool can be. */
    private static final class PlainProbe implements IMcpTool
    {
        private final String name;

        PlainProbe(String name)
        {
            this.name = name;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return "a probe"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "ran"; //$NON-NLS-1$
        }
    }
}
