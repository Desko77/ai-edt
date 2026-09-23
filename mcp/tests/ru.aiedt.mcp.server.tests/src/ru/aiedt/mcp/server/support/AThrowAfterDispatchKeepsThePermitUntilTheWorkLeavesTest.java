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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * A heavy call that dispatches work and then throws keeps the permit until that work leaves.
 * <p>
 * {@code getOrStart} notes the entry on the call's scope before it returns to the tool. Spending
 * the ticket only after {@code execute} returns misses that note: the call's {@code finally}
 * gives the permit back while the supplier is still running.
 * </p>
 */
public class AThrowAfterDispatchKeepsThePermitUntilTheWorkLeavesTest
{
    private BundleContext context;

    private final java.util.List<ServiceRegistration<?>> registrations = new java.util.ArrayList<>();

    private Semaphore permits;

    private ToolRoad road;

    @Before
    public void aRoadOfItsOwn()
    {
        context = FrameworkUtil.getBundle(
            AThrowAfterDispatchKeepsThePermitUntilTheWorkLeavesTest.class).getBundleContext();
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

    /**
     * The permit is occupied while the dispatched supplier is still inside its body, and free
     * once that body has left.
     */
    @Test
    public void aThrowAfterGetOrStartKeepsThePermitUntilTheWorkLeaves() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicBoolean left = new AtomicBoolean();
        String name = "road_throw_dispatch_" + System.nanoTime(); //$NON-NLS-1$
        DispatchThenThrowProbe probe = new DispatchThenThrowProbe(name, entered, hold, left);
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.HEAVY, Boolean.TRUE);
        properties.put(ToolWhiteboard.WRITES, Boolean.FALSE);
        registrations.add(context.registerService(IMcpTool.class, probe, properties));
        waitFor(name);

        try
        {
            ToolRoadOutcome outcome = road.call(name, Map.of(), "throw-dispatch"); //$NON-NLS-1$
            assertTrue("the call failed because the tool threw", outcome.failed()); //$NON-NLS-1$
            assertFalse("a throw after dispatch is not a refusal", outcome.refused()); //$NON-NLS-1$
            assertTrue("the supplier is inside the dispatched body", //$NON-NLS-1$
                entered.await(10, TimeUnit.SECONDS));
            assertFalse("the supplier has not left", left.get()); //$NON-NLS-1$
            assertEquals("the permit stays with the work the throw left running", //$NON-NLS-1$
                0, permits.availablePermits());
        }
        finally
        {
            hold.countDown();
        }
        assertTrue("the permit comes back when the dispatched work leaves", //$NON-NLS-1$
            eventually(() -> permits.availablePermits() == 1 && left.get()));
        if (probe.entry != null)
        {
            PendingWorkRegistry.REFERENCES.remove(probe.runKey, probe.entry);
        }
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

    private static boolean eventually(java.util.function.BooleanSupplier condition)
    {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline)
        {
            if (condition.getAsBoolean())
            {
                return true;
            }
            sleep(20L);
        }
        return condition.getAsBoolean();
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

    /** A heavy tool that starts a run and then throws, leaving the supplier blocked. */
    private static final class DispatchThenThrowProbe implements IMcpTool
    {
        private final String name;

        private final CountDownLatch entered;

        private final CountDownLatch hold;

        private final AtomicBoolean left;

        private String runKey;

        private PendingWorkRegistry.PendingEntry entry;

        DispatchThenThrowProbe(String name, CountDownLatch entered, CountDownLatch hold,
            AtomicBoolean left)
        {
            this.name = name;
            this.entered = entered;
            this.hold = hold;
            this.left = left;
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
            runKey = PendingWorkRegistry.computeRunKey(name, "throw-after-dispatch"); //$NON-NLS-1$
            entry = PendingWorkRegistry.REFERENCES.getOrStart(runKey, () -> {
                entered.countDown();
                try
                {
                    hold.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                }
                left.set(true);
                return "left"; //$NON-NLS-1$
            });
            throw new IllegalStateException("dispatched then threw"); //$NON-NLS-1$
        }
    }
}
