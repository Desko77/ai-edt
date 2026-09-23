/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The heavy-tool permit is held for as long as the WORK runs, not for as long as the call waited.
 * <p>
 * A {@code Pending} answer used to give the permit back on its soft timeout while the background
 * work carried on uncounted; a poll then took a second permit for the same run. Here the ticket
 * travels to the registry entry and the permit returns when the entry's future completes, a poll
 * of a live run takes nothing, and a nested heavy call inherits its caller's permit - including
 * from inside the background work, which now runs under the caller's scope.
 * </p>
 * <p>
 * The fakes behave like the real parts: the Pending envelope is a real envelope the
 * {@link PendingExecutor} built over a real {@link PendingWorkRegistry} domain of its own
 * (REFERENCES, not the shared GENERIC one, so a leftover here cannot meet another test's run),
 * and the catalogue is the real one, with each probe published as a bundle would publish a tool.
 * </p>
 */
public class TheRoadHoldsThePermitForAsLongAsTheWorkRunsTest
{
    /** Soft wait short enough that a held latch always turns into a Pending answer. */
    private static final long SOFT_MS = 120L;

    private BundleContext context;

    private final java.util.List<ServiceRegistration<?>> registrations = new java.util.ArrayList<>();

    private Semaphore permits;

    private ToolRoad road;

    @Before
    public void aRoadOfItsOwn()
    {
        context = FrameworkUtil.getBundle(TheRoadHoldsThePermitForAsLongAsTheWorkRunsTest.class)
            .getBundleContext();
        permits = new Semaphore(1);
        road = new ToolRoad(permits);
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

    /**
     * A Pending answer keeps the permit; a second heavy call is refused at both doors; once the
     * work finishes the permit is free and a third call passes.
     */
    @Test
    public void aPendingAnswerHoldsThePermitUntilTheWorkEnds() throws Exception
    {
        CountDownLatch hold = new CountDownLatch(1);
        PendingProbe probe = new PendingProbe("road_life_pending_" + System.nanoTime(), hold);
        PlainProbe other = new PlainProbe("road_life_plain_" + System.nanoTime());
        publish(probe);
        publish(other);
        waitFor(probe.name);

        ToolRoadOutcome pending = road.call(probe.name, Map.of("tag", "one"), "permit-test"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the held work answers Pending", pending.finished()); //$NON-NLS-1$
        assertNotNull(pending.runKey());
        assertEquals(PendingWorkRegistry.REFERENCES.domain(), pending.domain());
        assertEquals("the permit is held while the work runs", 0, permits.availablePermits()); //$NON-NLS-1$

        // The external door: this is the question the endpoint asks before it starts a thread.
        ToolRoad.Admission external = road.admit(other.name, Map.of());
        assertEquals("an external heavy call is turned away with the limit's wording", //$NON-NLS-1$
            ToolRoad.MSG_HEAVY_BUSY, external.refusal());
        // The internal door: a bundle's call is refused the same way.
        ToolRoadOutcome internal = road.call(other.name, Map.of(), "permit-test"); //$NON-NLS-1$
        assertTrue(internal.refused());
        assertEquals(ToolRoad.MSG_HEAVY_BUSY, internal.refusal());
        assertEquals(0, permits.availablePermits());

        hold.countDown();
        assertTrue("the permit comes back when the work ends", eventually(() -> permits.availablePermits() == 1)); //$NON-NLS-1$

        ToolRoadOutcome third = road.call(other.name, Map.of(), "permit-test"); //$NON-NLS-1$
        assertFalse(third.refused());
        assertEquals("ran", third.text()); //$NON-NLS-1$
        assertEquals("a finished synchronous call leaves the permit free", 1, permits.availablePermits()); //$NON-NLS-1$

        road.resume(pending.runKey(), 10_000L);
    }

    /** A poll by a live runKey takes no permit and clears no gate, at either door. */
    @Test
    public void aPollOfALiveRunTakesNoPermit() throws Exception
    {
        CountDownLatch hold = new CountDownLatch(1);
        PendingProbe probe = new PendingProbe("road_life_poll_" + System.nanoTime(), hold);
        publish(probe);
        waitFor(probe.name);

        ToolRoadOutcome pending = road.call(probe.name, Map.of("tag", "poll"), "permit-test"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, permits.availablePermits());

        // The external door: the endpoint's admission of a poll.
        ToolRoad.Admission external = road.admit(probe.name,
            Map.of("runKey", pending.runKey())); //$NON-NLS-1$
        assertNull("a poll is not refused", external.refusal()); //$NON-NLS-1$
        assertFalse("a poll takes no permit", external.ticket().holdsPermit()); //$NON-NLS-1$
        // The internal door: resume waits and still takes nothing.
        ToolRoadOutcome stillRunning = road.resume(pending.runKey(), 50L);
        assertFalse(stillRunning.finished());
        assertEquals("the poll left the only permit with the running work", 0, permits.availablePermits()); //$NON-NLS-1$

        hold.countDown();
        assertTrue(eventually(() -> permits.availablePermits() == 1));
        ToolRoadOutcome done = road.resume(pending.runKey(), 10_000L);
        assertTrue(done.finished());
        assertTrue(done.text(), done.text().startsWith("finished:")); //$NON-NLS-1$
    }

    /**
     * A nested heavy call inherits its caller's permit: with room for one heavy caller, a parent
     * that calls another heavy tool through the road still runs both.
     */
    @Test
    public void aNestedHeavyCallInheritsItsCallersPermit()
    {
        PlainProbe nested = new PlainProbe("road_life_nested_" + System.nanoTime());
        NestedProbe parent = new NestedProbe("road_life_parent_" + System.nanoTime(), road, nested.name,
            permits);
        publish(nested);
        publish(parent);
        waitFor(parent.name);

        AtomicInteger seenInside = new AtomicInteger(-1);
        parent.seenPermits = seenInside;
        ToolRoadOutcome outcome = road.call(parent.name, Map.of(), "permit-test"); //$NON-NLS-1$
        assertFalse(outcome.refused());
        assertEquals("nested:ran", outcome.text()); //$NON-NLS-1$
        assertEquals("while both ran there was one permit held, by the parent", //$NON-NLS-1$
            0, seenInside.get());
        assertEquals("after both finished no permit is held", 1, permits.availablePermits()); //$NON-NLS-1$
    }

    /** The same inheritance from inside the background work of a Pending call. */
    @Test
    public void aNestedCallFromTheBackgroundWorkInheritsThePermit() throws Exception
    {
        SlowProbe nested = new SlowProbe("road_life_bg_nested_" + System.nanoTime());
        BackgroundNestedProbe parent = new BackgroundNestedProbe(
            "road_life_bg_parent_" + System.nanoTime(), road, nested.name);
        publish(nested);
        publish(parent);
        waitFor(parent.name);

        ToolRoadOutcome pending = road.call(parent.name, Map.of("tag", "bg"), "permit-test"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the parent's work waits inside its nested call, so the parent answers Pending", //$NON-NLS-1$
            pending.finished());
        assertNotNull(pending.runKey());
        assertEquals("the parent's permit covers the work, nested call included", //$NON-NLS-1$
            0, permits.availablePermits());

        assertTrue("the permit comes back when the work, nested call included, ends", //$NON-NLS-1$
            eventually(() -> permits.availablePermits() == 1));
        ToolRoadOutcome done = road.resume(pending.runKey(), 10_000L);
        assertEquals("nested:slow-ok", done.text()); //$NON-NLS-1$
    }

    /** A long call hands out its runKey in the outcome, and resume waits for the work. */
    @Test
    public void aLongCallHandsOutItsRunKeyAndResumeWaits() throws Exception
    {
        CountDownLatch hold = new CountDownLatch(1);
        PendingProbe probe = new PendingProbe("road_life_wait_" + System.nanoTime(), hold);
        publish(probe);
        waitFor(probe.name);

        ToolRoadOutcome pending = road.call(probe.name, Map.of("tag", "wait"), "permit-test"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(pending.runKey());
        assertFalse(pending.finished());

        ToolRoadOutcome notYet = road.resume(pending.runKey(), 50L);
        assertFalse(notYet.finished());

        hold.countDown();
        ToolRoadOutcome done = road.resume(pending.runKey(), 10_000L);
        assertTrue(done.finished());
        assertEquals("finished:road", done.text()); //$NON-NLS-1$
    }

    /** A runKey no domain holds is not a poll, and goes the ordinary way. */
    @Test
    public void anUnknownRunKeyIsRefusedByResume()
    {
        ToolRoadOutcome outcome = road.resume("no-such-runkey", 10L); //$NON-NLS-1$
        assertTrue(outcome.refused());
        assertTrue(outcome.refusal(), outcome.refusal().contains("Not a registry entry")); //$NON-NLS-1$
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
     * A heavy tool whose answer goes through the real Pending flow of a real domain, with work
     * that waits on the test's latch.
     */
    private static final class PendingProbe implements IMcpTool
    {
        final String name;

        private final CountDownLatch hold;

        PendingProbe(String name, CountDownLatch hold)
        {
            this.name = name;
            this.hold = hold;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String resumes(String domain, String operation)
        {
            // This probe polls through PendingExecutor. The road exempts that poll only because
            // the probe says so; a matching name is no longer enough.
            return PendingWorkRegistry.REFERENCES.domain().equals(domain) ? name : null;
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
        public ResponseType getResponseType()
        {
            return ResponseType.TEXT;
        }

        @Override
        public String execute(Map<String, String> params)
        {
            String runKey = PendingWorkRegistry.computeRunKey(name, params.get("tag")); //$NON-NLS-1$
            return PendingExecutor.execute(PendingWorkRegistry.REFERENCES, name, params, runKey,
                SOFT_MS, () -> {
                    try
                    {
                        hold.await(30, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    return "finished:road"; //$NON-NLS-1$
                }, null);
        }
    }

    /** A heavy tool that answers at once. */
    private static final class PlainProbe implements IMcpTool
    {
        final String name;

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

    /** A heavy tool that calls another tool through the road, the way a bundle would. */
    private static final class NestedProbe implements IMcpTool
    {
        final String name;

        private final ToolRoad road;

        private final String nestedName;

        private final Semaphore permits;

        volatile AtomicInteger seenPermits;

        NestedProbe(String name, ToolRoad road, String nestedName, Semaphore permits)
        {
            this.name = name;
            this.road = road;
            this.nestedName = nestedName;
            this.permits = permits;
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
            AtomicInteger seen = seenPermits;
            if (seen != null)
            {
                seen.set(permits.availablePermits());
            }
            ToolRoadOutcome nested = road.call(nestedName, new LinkedHashMap<>(), "nested-probe"); //$NON-NLS-1$
            return "nested:" + nested.text(); //$NON-NLS-1$
        }
    }

    /** A heavy tool that answers after a beat, long enough to outlast the soft wait. */
    private static final class SlowProbe implements IMcpTool
    {
        final String name;

        SlowProbe(String name)
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
            sleep(400L);
            return "slow-ok"; //$NON-NLS-1$
        }
    }

    /**
     * A heavy tool whose background work calls another tool through the road, the way a bundle
     * inside a long-running tool would.
     */
    private static final class BackgroundNestedProbe implements IMcpTool
    {
        final String name;

        private final ToolRoad road;

        private final String nestedName;

        BackgroundNestedProbe(String name, ToolRoad road, String nestedName)
        {
            this.name = name;
            this.road = road;
            this.nestedName = nestedName;
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
            return PendingExecutor.execute(PendingWorkRegistry.REFERENCES, name, params, runKey,
                SOFT_MS, () -> {
                    ToolRoadOutcome nested = road.call(nestedName, new LinkedHashMap<>(), //$NON-NLS-1$
                        "nested-probe");
                    return "nested:" + nested.text(); //$NON-NLS-1$
                }, null);
        }
    }
}
