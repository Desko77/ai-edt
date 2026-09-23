/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
 * A cancel through the road reaches the background work: the flag the work watches - through
 * {@link WatchForCancel}, on the scope the work re-entered - rises, and the domain's stopper is
 * asked when the domain has one.
 */
public class TheRoadCarriesCancellationToTheWorkTest
{
    private BundleContext context;

    private final java.util.List<ServiceRegistration<?>> registrations = new java.util.ArrayList<>();

    private ToolRoad road;

    private AtomicReference<String> stopperAsked;

    @Before
    public void aRoadOfItsOwn()
    {
        context = FrameworkUtil.getBundle(TheRoadCarriesCancellationToTheWorkTest.class)
            .getBundleContext();
        road = new ToolRoad(new Semaphore(1));
        stopperAsked = new AtomicReference<>();
        PendingWorkRegistry.IMPORT_BINARY.stopsWith(key -> {
            stopperAsked.set(key);
            return PendingWorkRegistry.StopOutcome.STOPPED;
        });
    }

    @After
    public void theProbeAndTheStopperGo()
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
        PendingWorkRegistry.IMPORT_BINARY.stopsWith(null);
    }

    /**
     * The flag of an internal call's own scope is what the tool watches, and cancel raises it
     * where the work can see it.
     */
    @Test
    public void cancelRaisesTheFlagTheToolWatches() throws Exception
    {
        CancellingProbe probe = new CancellingProbe("road_cancel_watch_" + System.nanoTime());
        publish(probe);
        waitFor(probe.name);

        ToolRoadOutcome pending = road.call(probe.name, Map.of("tag", "cancel"), "cancel-test"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(pending.finished());
        assertNotNull(pending.runKey());
        assertTrue("the work is running its watch loop", probe.started.await(20, TimeUnit.SECONDS)); //$NON-NLS-1$

        assertTrue(road.cancel(pending.runKey()));
        assertTrue("the work saw the flag and stopped", //$NON-NLS-1$
            eventually(() -> probe.outcome.get() != null && probe.outcome.get().contains("cancelled"))); //$NON-NLS-1$
        assertEquals("the domain's stopper was asked for this run", pending.runKey(), //$NON-NLS-1$
            stopperAsked.get());

        ToolRoadOutcome gone = road.resume(pending.runKey(), 50L);
        assertTrue("a cancelled run is no longer tracked", gone.refused()); //$NON-NLS-1$
    }

    /** A runKey no domain holds is not cancelled, and the caller is told so. */
    @Test
    public void anUnknownRunKeyIsNotCancelled()
    {
        assertFalse(road.cancel("no-such-runkey")); //$NON-NLS-1$
    }

    private void publish(IMcpTool probe)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.HEAVY, Boolean.TRUE);
        properties.put(ToolWhiteboard.WRITES, Boolean.FALSE);
        registrations.add(context.registerService(IMcpTool.class, probe, properties));
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

    /**
     * A heavy tool whose background work watches the cancel flag the way the cancellable scans do,
     * and reports what it saw where the test can read it after the entry is gone.
     */
    private static final class CancellingProbe implements IMcpTool
    {
        final String name;

        final CountDownLatch started = new CountDownLatch(1);

        final AtomicReference<String> outcome = new AtomicReference<>();

        CancellingProbe(String name)
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
            String runKey = PendingWorkRegistry.computeRunKey(name, params.get("tag")); //$NON-NLS-1$
            return PendingExecutor.execute(PendingWorkRegistry.IMPORT_BINARY, name, params, runKey,
                120L, () -> {
                    WatchForCancel watch = WatchForCancel.begin();
                    started.countDown();
                    while (!watch.stopHere())
                    {
                        sleep(20L);
                    }
                    String note = watch.note("steps");
                    String answer = note != null ? note : "ran to the end"; //$NON-NLS-1$
                    outcome.set(answer);
                    return answer;
                }, null);
        }
    }
}
