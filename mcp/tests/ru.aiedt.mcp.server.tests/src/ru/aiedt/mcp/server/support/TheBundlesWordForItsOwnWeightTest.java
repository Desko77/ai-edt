/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

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
 * A tool another bundle published is as heavy as its bundle says: the
 * {@code ru.aiedt.mcp.tool.heavy} property puts it behind the heap gate and under the limiter, and
 * a tool that says nothing is light.
 */
public class TheBundlesWordForItsOwnWeightTest
{
    private BundleContext context;

    private final java.util.List<ServiceRegistration<?>> registrations = new java.util.ArrayList<>();

    @Before
    public void aContext()
    {
        context = FrameworkUtil.getBundle(TheBundlesWordForItsOwnWeightTest.class).getBundleContext();
    }

    @After
    public void theProbesGo()
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

    /** A tool that says it is heavy clears the heap gate and takes a permit while it runs. */
    @Test
    public void aHeavyDeclarationPutsTheToolBehindTheHeapGateAndUnderTheLimiter()
    {
        Semaphore permits = new Semaphore(1);
        String name = "road_weight_heavy_" + System.nanoTime(); //$NON-NLS-1$
        AtomicInteger seenInside = new AtomicInteger(-1);
        registrations.add(context.registerService(IMcpTool.class, new SeeingProbe(name, permits,
            seenInside), properties(Boolean.TRUE)));
        waitFor(name);

        assertTrue("the declaration makes the tool heavy", HeavyTools.isHeavy(name)); //$NON-NLS-1$

        // The heap gate refuses only this call: the seam answers for a heap that has no room.
        ToolRoad tightRoad = new ToolRoad(permits, () -> ToolRoad.MSG_HEAP_EXHAUSTED + "test reading"); //$NON-NLS-1$
        ToolRoad.Admission refused = tightRoad.admit(name, Map.of());
        assertTrue("a heavy tool is refused when the heap has no room", refused.refusal() != null); //$NON-NLS-1$
        assertTrue(refused.refusal(), refused.refusal().startsWith(ToolRoad.MSG_HEAP_EXHAUSTED));

        ToolRoadOutcome ran = new ToolRoad(permits).call(name, Map.of(), "weight-test"); //$NON-NLS-1$
        assertEquals("ran", ran.text()); //$NON-NLS-1$
        assertEquals("the permit was held while the tool ran", 0, seenInside.get()); //$NON-NLS-1$
        assertEquals("and given back when it finished", 1, permits.availablePermits()); //$NON-NLS-1$
    }

    /** A tool that says nothing about its weight is light: no heap gate, no permit. */
    @Test
    public void aToolThatSaysNothingIsLight()
    {
        Semaphore permits = new Semaphore(1);
        String name = "road_weight_light_" + System.nanoTime(); //$NON-NLS-1$
        AtomicInteger seenInside = new AtomicInteger(-1);
        registrations.add(context.registerService(IMcpTool.class, new SeeingProbe(name, permits,
            seenInside), properties(null)));
        waitFor(name);

        assertFalse(HeavyTools.isHeavy(name));
        ToolRoad tightRoad = new ToolRoad(permits, () -> ToolRoad.MSG_HEAP_EXHAUSTED + "test reading"); //$NON-NLS-1$
        ToolRoad.Admission admitted = tightRoad.admit(name, Map.of());
        assertTrue("a light tool clears an exhausted heap unasked", admitted.refusal() == null); //$NON-NLS-1$
        assertFalse("and takes no permit", admitted.ticket().holdsPermit()); //$NON-NLS-1$
        assertEquals("the limiter still holds everything it started with", 1, permits.availablePermits()); //$NON-NLS-1$
    }

    private static Hashtable<String, Object> properties(Boolean heavy)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.WRITES, Boolean.FALSE);
        if (heavy != null)
        {
            properties.put(ToolWhiteboard.HEAVY, heavy);
        }
        return properties;
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

    /** A tool that reports the limiter's state while it runs. */
    private static final class SeeingProbe implements IMcpTool
    {
        private final String name;

        private final Semaphore permits;

        private final AtomicInteger seen;

        SeeingProbe(String name, Semaphore permits, AtomicInteger seen)
        {
            this.name = name;
            this.permits = permits;
            this.seen = seen;
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
            seen.set(permits.availablePermits());
            return "ran"; //$NON-NLS-1$
        }
    }
}
