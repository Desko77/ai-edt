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
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Each promise the call road makes, kept under the load that breaks it.
 * <p>
 * A live {@code runKey} exempts a call from the heavy gates only when the called tool is the one
 * that resumes that run - a stray key on a heavy tool that reads none must not start ungated work.
 * A cancelled tracking future returns no permit while the work body is still running, because a
 * cancel completes the future without reaching the body. A light caller's heavy child lends its
 * permit to a grandchild under it, and the caller's own scope comes back afterwards. A finished
 * entry stops holding the call scope it was started under.
 * </p>
 * <p>
 * The fakes behave like the real parts, as in {@link TheRoadHoldsThePermitForAsLongAsTheWorkRunsTest}:
 * the Pending envelope is a real envelope the {@link PendingExecutor} built over the real
 * {@link PendingWorkRegistry#REFERENCES} domain, and the catalogue is the real one, with each probe
 * published as a bundle would publish a tool.
 * </p>
 */
public class TheRoadKeepsEachOfItsPromisesTest
{
    /** Soft wait short enough that a held latch always turns into a Pending answer. */
    private static final long SOFT_MS = 120L;

    private BundleContext context;

    private final java.util.List<ServiceRegistration<?>> registrations = new java.util.ArrayList<>();

    private Semaphore permits;

    private ToolRoad road;

    /**
     * The latch of whichever probe the running test held open, drained in the after-block: a test
     * that fails mid-way would otherwise leave its work sitting on the shared domain's executor
     * for the full await, and the next test's dispatch would queue behind it.
     */
    private CountDownLatch openHold;

    @Before
    public void aRoadOfItsOwn()
    {
        context = FrameworkUtil.getBundle(TheRoadKeepsEachOfItsPromisesTest.class).getBundleContext();
        permits = new Semaphore(1);
        road = new ToolRoad(permits);
    }

    @After
    public void theProbesGo()
    {
        if (openHold != null)
        {
            openHold.countDown();
            openHold = null;
        }
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
     * A live runKey handed to a heavy tool that reads no key opens no gate: while one work runs,
     * its key as a stray argument on another heavy call leaves the concurrency limit and the heap
     * gate exactly where they were.
     */
    @Test
    public void aStrayRunKeyClearsNeitherGateForAToolThatReadsNone() throws Exception
    {
        CountDownLatch hold = new CountDownLatch(1);
        openHold = hold;
        PendingProbe running = new PendingProbe("road_vow_stray_" + System.nanoTime(), hold, null);
        PlainProbe stranger = new PlainProbe("road_vow_stranger_" + System.nanoTime());
        publish(running);
        publish(stranger);
        waitFor(stranger.name);

        ToolRoadOutcome pending = road.call(running.name, Map.of("tag", "stray"), "vow-test"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(pending.finished());
        assertNotNull(pending.runKey());
        assertEquals(0, permits.availablePermits());

        ToolRoad.Admission atTheLimit = road.admit(stranger.name,
            Map.of("runKey", pending.runKey())); //$NON-NLS-1$
        assertEquals("a key the tool never reads clears no concurrency limit", //$NON-NLS-1$
            ToolRoad.MSG_HEAVY_BUSY, atTheLimit.refusal());

        ToolRoad noHeap = new ToolRoad(new Semaphore(1),
            () -> ToolRoad.MSG_HEAP_EXHAUSTED + "vow-test"); //$NON-NLS-1$
        ToolRoad.Admission atTheHeap = noHeap.admit(stranger.name,
            Map.of("runKey", pending.runKey())); //$NON-NLS-1$
        assertTrue("a key the tool never reads clears no heap gate: " + atTheHeap.refusal(), //$NON-NLS-1$
            atTheHeap.refusal() != null
                && atTheHeap.refusal().startsWith(ToolRoad.MSG_HEAP_EXHAUSTED));

        hold.countDown();
        road.resume(pending.runKey(), 10_000L);
    }

    /**
     * The run's own poll is still free: a call under the tool that started the live run takes no
     * permit and clears no heap gate, at either door.
     */
    @Test
    public void aPollOfItsOwnRunStillTakesNoPermitAndClearsNoHeapGate() throws Exception
    {
        CountDownLatch hold = new CountDownLatch(1);
        openHold = hold;
        PendingProbe running = new PendingProbe("road_vow_own_" + System.nanoTime(), hold, null);
        publish(running);
        waitFor(running.name);

        ToolRoadOutcome pending = road.call(running.name, Map.of("tag", "own"), "vow-test"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(pending.finished());
        assertNotNull(pending.runKey());
        assertEquals(0, permits.availablePermits());

        ToolRoad.Admission atTheLimit = road.admit(running.name,
            Map.of("runKey", pending.runKey())); //$NON-NLS-1$
        assertNull("the run's own poll is not refused", atTheLimit.refusal()); //$NON-NLS-1$
        assertFalse("and takes no permit", atTheLimit.ticket().holdsPermit()); //$NON-NLS-1$

        ToolRoad noHeap = new ToolRoad(new Semaphore(1),
            () -> ToolRoad.MSG_HEAP_EXHAUSTED + "vow-test"); //$NON-NLS-1$
        ToolRoad.Admission atTheHeap = noHeap.admit(running.name,
            Map.of("runKey", pending.runKey())); //$NON-NLS-1$
        assertNull("the run's own poll clears no heap gate either", atTheHeap.refusal()); //$NON-NLS-1$

        hold.countDown();
        assertTrue(eventually(() -> permits.availablePermits() == 1));
        road.resume(pending.runKey(), 10_000L);
    }

    /**
     * Cancelling the tracking future returns no permit while the work body is still running: the
     * cancel completes the future without reaching the body, and the permit comes back only when
     * the body leaves. The probe ignores the cancellation and waits on its latch.
     */
    @Test
    public void aCancelledFutureReturnsNoPermitUntilTheWorkBodyLeaves() throws Exception
    {
        CountDownLatch hold = new CountDownLatch(1);
        openHold = hold;
        AtomicBoolean bodyLeft = new AtomicBoolean();
        PendingProbe running = new PendingProbe("road_vow_cancel_" + System.nanoTime(), hold, bodyLeft);
        PlainProbe other = new PlainProbe("road_vow_cancel_other_" + System.nanoTime());
        publish(running);
        publish(other);
        waitFor(running.name);

        ToolRoadOutcome pending = road.call(running.name, Map.of("tag", "cancel"), "vow-test"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(pending.finished());
        assertNotNull(pending.runKey());
        assertEquals(0, permits.availablePermits());

        assertTrue(road.cancel(pending.runKey()));
        assertFalse("the body is still inside its latch", bodyLeft.get()); //$NON-NLS-1$
        assertEquals("a cancelled future returns no permit while the work still runs", //$NON-NLS-1$
            0, permits.availablePermits());
        assertEquals("and the next heavy call is still turned away", ToolRoad.MSG_HEAVY_BUSY, //$NON-NLS-1$
            road.admit(other.name, Map.of()).refusal());

        hold.countDown();
        assertTrue("the permit comes back when the body leaves", //$NON-NLS-1$
            eventually(() -> permits.availablePermits() == 1 && bodyLeft.get()));
    }

    /**
     * A light caller's heavy child takes the one permit, and the heavy grandchild under that child
     * runs on the child's ticket rather than a second one. When the child returns, the caller's
     * own scope is the one on the thread again, its own ticket untouched.
     */
    @Test
    public void aGrandchildRunsUnderTheChildTicketAndTheCallersScopeComesBack()
    {
        PlainProbe grand = new PlainProbe("road_vow_grand_" + System.nanoTime());
        HeavyChildProbe child = new HeavyChildProbe("road_vow_child_" + System.nanoTime(), road,
            grand.name, permits);
        LightParentProbe parent = new LightParentProbe("road_vow_light_" + System.nanoTime(), road,
            child.name);
        publish(grand);
        publish(child);
        publishLight(parent);
        waitFor(parent.name);

        ToolRoadOutcome outcome = road.call(parent.name, Map.of(), "vow-test"); //$NON-NLS-1$
        assertFalse(outcome.text(), outcome.refused());
        assertTrue("the grandchild ran under the child's ticket: " + outcome.text(), //$NON-NLS-1$
            outcome.text().contains("grand-child-ran")); //$NON-NLS-1$
        assertEquals("one permit covered the child and the grandchild", 0, child.seenPermits.get()); //$NON-NLS-1$
        assertTrue("the caller got its own scope back", parent.scopeCameBack.get()); //$NON-NLS-1$
        assertTrue("and its own ticket with it", parent.ticketCameBack.get()); //$NON-NLS-1$
        assertEquals("after the whole tree no permit is held", 1, permits.availablePermits()); //$NON-NLS-1$
    }

    /**
     * The same inheritance one level deeper in time: a heavy child that answers Pending dispatches
     * work which itself calls a heavy grandchild, and that grandchild runs under the ticket the
     * child handed its entry. The caller above both is light.
     */
    @Test
    public void aGrandchildOfAPendingChildRunsUnderTheChildTicket() throws Exception
    {
        SlowProbe grand = new SlowProbe("road_vow_bg_grand_" + System.nanoTime());
        PendingChildProbe child = new PendingChildProbe("road_vow_bg_child_" + System.nanoTime(),
            road, grand.name);
        LightParentProbe parent = new LightParentProbe("road_vow_bg_light_" + System.nanoTime(),
            road, child.name);
        publish(grand);
        publish(child);
        publishLight(parent);
        waitFor(parent.name);

        ToolRoadOutcome outcome = road.call(parent.name, Map.of("tag", "bg"), "vow-test"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(outcome.refused());

        String childRunKey = parent.childRunKey.get();
        assertNotNull("the child answered Pending and named its run", childRunKey); //$NON-NLS-1$
        assertTrue(eventually(() -> permits.availablePermits() == 1));
        ToolRoadOutcome done = road.resume(childRunKey, 10_000L);
        assertTrue("the grandchild of the background work ran under the child's ticket: " //$NON-NLS-1$
            + done.text(), done.text().contains("bg-grand-ran")); //$NON-NLS-1$
        assertEquals(1, permits.availablePermits());
    }

    /**
     * A finished entry stops holding the call scope it was started under: the scope - with the
     * call record and the exchange it references - goes as soon as the run completes, not when
     * somebody comes to collect the result.
     */
    @Test
    public void aFinishedEntryNoLongerHoldsTheCallScope() throws Exception
    {
        ToolCallScope scope = ToolCallScope.create(null);
        ToolCallScope.enter(scope);
        try
        {
            String key = "road_vow_scope_" + System.nanoTime(); //$NON-NLS-1$
            AtomicBoolean heldWhileRunning = new AtomicBoolean();
            PendingWorkRegistry.PendingEntry entry = PendingWorkRegistry.REFERENCES.getOrStart(key,
                running -> {
                    heldWhileRunning.set(running.scope == scope);
                    return "scope-done"; //$NON-NLS-1$
                });
            String result = entry.await(10_000L);
            assertEquals("scope-done", result); //$NON-NLS-1$
            assertTrue("while the run went, the entry held the scope it was started under", //$NON-NLS-1$
                heldWhileRunning.get());
            assertNull("a finished entry no longer holds the call scope", entry.scope); //$NON-NLS-1$
            PendingWorkRegistry.REFERENCES.remove(key, entry);
        }
        finally
        {
            ToolCallScope.exit();
        }
    }

    private void publish(IMcpTool probe)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.HEAVY, Boolean.TRUE);
        properties.put(ToolWhiteboard.WRITES, Boolean.FALSE);
        registrations.add(context.registerService(IMcpTool.class, probe, properties));
    }

    private void publishLight(IMcpTool probe)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.HEAVY, Boolean.FALSE);
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
     * that waits on the test's latch and never reads any cancellation flag.
     */
    private static final class PendingProbe implements IMcpTool
    {
        final String name;

        private final CountDownLatch hold;

        private final AtomicBoolean bodyLeft;

        PendingProbe(String name, CountDownLatch hold, AtomicBoolean bodyLeft)
        {
            this.name = name;
            this.hold = hold;
            this.bodyLeft = bodyLeft;
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
                    try
                    {
                        hold.await(30, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    finally
                    {
                        if (bodyLeft != null)
                        {
                            bodyLeft.set(true);
                        }
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
            return "ran"; //$NON-NLS-1$
        }
    }

    /**
     * A heavy tool that calls a heavy grandchild through the road, recording how many permits
     * were free when its own body began.
     */
    private static final class HeavyChildProbe implements IMcpTool
    {
        final String name;

        final AtomicInteger seenPermits = new AtomicInteger(-1);

        private final ToolRoad road;

        private final String grandName;

        private final Semaphore permits;

        HeavyChildProbe(String name, ToolRoad road, String grandName, Semaphore permits)
        {
            this.name = name;
            this.road = road;
            this.grandName = grandName;
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
            seenPermits.set(permits.availablePermits());
            ToolRoadOutcome grand = road.call(grandName, new LinkedHashMap<>(), "vow-test"); //$NON-NLS-1$
            return grand.refused() ? "grand-child-refused:" + grand.refusal() //$NON-NLS-1$
                : "grand-child-" + grand.text(); //$NON-NLS-1$
        }
    }

    /**
     * A light tool that calls a heavy child through the road, recording whether its own scope and
     * ticket are the ones on the thread once the child returns, and the runKey when the child
     * answered Pending.
     */
    private static final class LightParentProbe implements IMcpTool
    {
        final String name;

        final AtomicBoolean scopeCameBack = new AtomicBoolean();

        final AtomicBoolean ticketCameBack = new AtomicBoolean();

        final java.util.concurrent.atomic.AtomicReference<String> childRunKey =
            new java.util.concurrent.atomic.AtomicReference<>();

        private final ToolRoad road;

        private final String childName;

        LightParentProbe(String name, ToolRoad road, String childName)
        {
            this.name = name;
            this.road = road;
            this.childName = childName;
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
            ToolCallScope before = ToolCallScope.current();
            ToolRoad.Ticket beforeTicket = before == null ? null : before.ticket();
            ToolRoadOutcome child = road.call(childName, new LinkedHashMap<>(), "vow-test"); //$NON-NLS-1$
            ToolCallScope after = ToolCallScope.current();
            scopeCameBack.set(before == after);
            ticketCameBack.set(beforeTicket != null && beforeTicket == after.ticket());
            if (!child.finished() && child.runKey() != null)
            {
                childRunKey.set(child.runKey());
            }
            return child.refused() ? "child-refused:" + child.refusal() : "child-ok:" + child.text(); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * A heavy tool whose background work calls a heavy grandchild through the road, the way a
     * bundle inside a long-running tool would.
     */
    private static final class PendingChildProbe implements IMcpTool
    {
        final String name;

        private final ToolRoad road;

        private final String grandName;

        PendingChildProbe(String name, ToolRoad road, String grandName)
        {
            this.name = name;
            this.road = road;
            this.grandName = grandName;
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
                    ToolRoadOutcome grand = road.call(grandName, new LinkedHashMap<>(), //$NON-NLS-1$
                        "vow-test");
                    return grand.refused() ? "bg-grand-refused:" + grand.refusal() //$NON-NLS-1$
                        : "bg-grand-" + grand.text(); //$NON-NLS-1$
                }, null);
        }
    }
}
