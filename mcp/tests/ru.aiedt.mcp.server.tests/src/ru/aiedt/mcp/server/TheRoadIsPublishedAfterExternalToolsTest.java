/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolWhiteboard;

/**
 * The road is published from the whiteboard's opener thread, after that thread has opened the
 * external-tool tracker.
 * <p>
 * A tracker receives {@code IToolRoad} the moment it is registered. Publishing on the activating
 * thread, before {@code ServiceTracker.open} has run, answers {@code Tool not found} for a service
 * that is already registered. The opener thread is the one place that knows the tracker is open,
 * and the activating thread must not join it.
 * </p>
 */
public class TheRoadIsPublishedAfterExternalToolsTest
{
    /** The live activator published the road on the whiteboard thread, not its own. */
    @Test
    public void theRoadIsPublishedOnTheWhiteboardThread() throws Exception
    {
        long deadline = System.currentTimeMillis() + 10_000L;
        String thread = Activator.getDefault().publishedRoadOn();
        while (thread == null && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20L);
            thread = Activator.getDefault().publishedRoadOn();
        }
        assertEquals("AI-EDT whiteboard", thread); //$NON-NLS-1$
        assertFalse("publication does not run on the test thread", //$NON-NLS-1$
            Thread.currentThread().getName().equals(thread));
    }

    /**
     * The substitutable publication hook runs after {@code open}, on the opener thread, with an
     * external tool that was already registered visible in the catalogue.
     */
    @Test
    public void thePublicationHookSeesExternalToolsAfterOpen() throws Exception
    {
        BundleContext context = FrameworkUtil.getBundle(TheRoadIsPublishedAfterExternalToolsTest.class)
            .getBundleContext();
        String name = "road_publish_order_" + System.nanoTime(); //$NON-NLS-1$
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.WRITES, Boolean.FALSE);
        Probe probe = new Probe(name);
        ServiceRegistration<IMcpTool> registration =
            context.registerService(IMcpTool.class, probe, properties);
        ToolWhiteboard board = new ToolWhiteboard();
        AtomicBoolean visible = new AtomicBoolean();
        AtomicReference<String> thread = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        try
        {
            board.openInBackground(context, () -> {
                visible.set(McpToolCatalog.getInstance().getTool(name) == probe);
                thread.set(Thread.currentThread().getName());
                published.countDown();
            });
            assertTrue("the publication hook ran", published.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
            assertTrue("the external tool is in the catalogue when the road would be published", //$NON-NLS-1$
                visible.get());
            assertSame(probe, McpToolCatalog.getInstance().getTool(name));
            assertEquals("AI-EDT whiteboard", thread.get()); //$NON-NLS-1$
            assertFalse("the hook is not the caller's thread", //$NON-NLS-1$
                Thread.currentThread().getName().equals(thread.get()));
        }
        finally
        {
            try
            {
                registration.unregister();
            }
            catch (IllegalStateException alreadyGone)
            {
                // unregistered already
            }
            board.close();
        }
    }

    /** The least a tool can be. */
    private static final class Probe implements IMcpTool
    {
        private final String name;

        Probe(String name)
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
