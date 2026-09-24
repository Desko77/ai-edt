/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;
import ru.aiedt.mcp.server.toolkit.ToolWhiteboard;

/**
 * A tool that threw is a call that ran, not a call the road turned away.
 * <p>
 * The tool may have applied part of what it was asked for before it threw, and a caller that
 * retries refusals - because a refusal means "never ran, safe to try again" - would repeat a
 * mutation. The wire path reports an execution error for the same case; the internal outcome has
 * to keep the two apart as well.
 * </p>
 */
public class AThrownToolIsAFailedCallNotARefusalTest
{
    private BundleContext context;

    private final java.util.List<ServiceRegistration<?>> registrations = new java.util.ArrayList<>();

    private Semaphore permits;

    private ToolRoad road;

    @Before
    public void aRoadOfItsOwn()
    {
        context = FrameworkUtil.getBundle(AThrownToolIsAFailedCallNotARefusalTest.class)
            .getBundleContext();
        permits = new Semaphore(1);
        road = new ToolRoad(permits);
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

    /** The outcome says the call failed, not that the road refused it. */
    @Test
    public void aToolThatThrowsIsAFailedCallNotARefusal()
    {
        String name = "road_throws_" + System.nanoTime(); //$NON-NLS-1$
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.HEAVY, Boolean.TRUE);
        properties.put(ToolWhiteboard.WRITES, Boolean.FALSE);
        registrations.add(context.registerService(IMcpTool.class, new ThrowingProbe(name),
            properties));
        waitFor(name);

        ToolRoadOutcome outcome = road.call(name, Map.of(), "failure-test"); //$NON-NLS-1$
        assertFalse("a tool that ran and threw was not turned away before it ran", //$NON-NLS-1$
            outcome.refused());
        assertTrue("the outcome says the call failed", outcome.failed()); //$NON-NLS-1$
        assertTrue(outcome.failure(), outcome.failure().contains("IllegalStateException")); //$NON-NLS-1$
        assertTrue(outcome.failure(), outcome.failure().contains("half-applied")); //$NON-NLS-1$
        assertNull("a failed call carries no answer text", outcome.text()); //$NON-NLS-1$
        assertTrue("a failed call is over, with nothing to resume", outcome.finished()); //$NON-NLS-1$
        assertEquals("the failed call's permit came back", 1, permits.availablePermits()); //$NON-NLS-1$
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

    /** A heavy tool that throws halfway through its work. */
    private static final class ThrowingProbe implements IMcpTool
    {
        private final String name;

        ThrowingProbe(String name)
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
            throw new IllegalStateException("half-applied"); //$NON-NLS-1$
        }
    }
}
