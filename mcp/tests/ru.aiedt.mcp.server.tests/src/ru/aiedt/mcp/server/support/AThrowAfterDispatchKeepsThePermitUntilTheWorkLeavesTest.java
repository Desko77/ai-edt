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
import ru.aiedt.mcp.server.wire.McpRequestRouter;
import ru.aiedt.mcp.server.wire.McpServerMeta;

/**
 * A heavy call that dispatches work and then throws keeps the permit until that work leaves.
 * <p>
 * {@code getOrStart} notes the entry on the call's scope before it returns to the tool. Spending
 * the ticket only after {@code execute} returns misses that note: the call's {@code finally}
 * gives the permit back while the supplier is still running. The same hand-off has to live on the
 * path the router takes, survive a cancel that completes the tracking future, and cover every
 * run the call started.
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

    /**
     * The HTTP door does not go through {@link ToolRoad#call}. It admits, then the router runs
     * the body, and the worker's {@code finally} releases the ticket the way
     * {@code ToolExecution} does. A throw after {@code getOrStart} must already have handed the
     * ticket to the run, or that release gives the permit back while the supplier is still inside.
     */
    @Test
    public void aThrowThroughTheRouterKeepsThePermitUntilTheWorkLeaves() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicBoolean left = new AtomicBoolean();
        String name = "road_throw_router_" + System.nanoTime(); //$NON-NLS-1$
        DispatchThenThrowProbe probe = new DispatchThenThrowProbe(name, entered, hold, left);
        registerHeavy(probe);
        waitFor(name);

        ToolRoad.Admission admission = road.admit(name, Map.of());
        assertEquals("the router path is a heavy call that was let in", //$NON-NLS-1$
            null, admission.refusal());
        ToolCallScope scope = ToolCallScope.create(null);
        scope.adoptTicket(admission.ticket());
        String document = null;
        try
        {
            ToolCallScope.enter(scope);
            try
            {
                document = new McpRequestRouter().processRequest(toolsCall(name), null);
            }
            finally
            {
                // What McpHttpEndpoint.ToolExecution.run does after the router returns: leave the
                // scope, then release. A ticket the body already handed to live work does nothing
                // here; a ticket nobody spent gives the permit back.
                ToolCallScope.exit();
                admission.ticket().release();
            }
            assertTrue("the router ran the tool, which started the supplier", //$NON-NLS-1$
                entered.await(10, TimeUnit.SECONDS));
            assertFalse("the supplier has not left", left.get()); //$NON-NLS-1$
            assertEquals("the worker's release does not free a permit the thrown call already handed over", //$NON-NLS-1$
                0, permits.availablePermits());
            assertTrue("the router reported the throw instead of a successful answer", //$NON-NLS-1$
                document != null && document.contains("dispatched then threw")); //$NON-NLS-1$
        }
        finally
        {
            hold.countDown();
            if (ToolCallScope.current() == scope)
            {
                ToolCallScope.exit();
            }
        }
        assertTrue("the permit comes back when the dispatched work leaves", //$NON-NLS-1$
            eventually(() -> permits.availablePermits() == 1 && left.get()));
        if (probe.entry != null)
        {
            PendingWorkRegistry.REFERENCES.remove(probe.runKey, probe.entry);
        }
    }

    /**
     * A cancel completes the tracking future and drops the entry while the body keeps running.
     * Liveness of the hand-off is that body's exit, so the permit stays taken until the supplier
     * leaves.
     */
    @Test
    public void aThrowAfterCancelKeepsThePermitUntilTheWorkLeaves() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicBoolean left = new AtomicBoolean();
        String name = "road_throw_cancel_" + System.nanoTime(); //$NON-NLS-1$
        CancelThenThrowProbe probe = new CancelThenThrowProbe(name, entered, hold, left);
        registerHeavy(probe);
        waitFor(name);

        try
        {
            ToolRoadOutcome outcome = road.call(name, Map.of(), "throw-after-cancel"); //$NON-NLS-1$
            assertTrue("the call failed because the tool threw", outcome.failed()); //$NON-NLS-1$
            assertTrue("the cancel completed the tracking future", probe.entry.isDone()); //$NON-NLS-1$
            assertFalse("the supplier has not left", left.get()); //$NON-NLS-1$
            assertEquals("a completed future is not the work leaving", //$NON-NLS-1$
                0, permits.availablePermits());
        }
        finally
        {
            hold.countDown();
        }
        assertTrue("the permit comes back when the cancelled run's body leaves", //$NON-NLS-1$
            eventually(() -> permits.availablePermits() == 1 && left.get()));
    }

    /**
     * Two runs started by the one call that then throws each keep a share. The permit comes back
     * when the last of them leaves, not when the first one does.
     */
    @Test
    public void aThrowAfterTwoRunsKeepsThePermitUntilTheLastOneLeaves() throws Exception
    {
        CountDownLatch enteredFirst = new CountDownLatch(1);
        CountDownLatch enteredSecond = new CountDownLatch(1);
        CountDownLatch holdFirst = new CountDownLatch(1);
        CountDownLatch holdSecond = new CountDownLatch(1);
        AtomicBoolean leftFirst = new AtomicBoolean();
        AtomicBoolean leftSecond = new AtomicBoolean();
        String name = "road_throw_two_" + System.nanoTime(); //$NON-NLS-1$
        TwoRunsThenThrowProbe probe = new TwoRunsThenThrowProbe(name, enteredFirst, enteredSecond,
            holdFirst, holdSecond, leftFirst, leftSecond);
        registerHeavy(probe);
        waitFor(name);

        try
        {
            ToolRoadOutcome outcome = road.call(name, Map.of(), "throw-two-runs"); //$NON-NLS-1$
            assertTrue("the call failed because the tool threw", outcome.failed()); //$NON-NLS-1$
            assertTrue("both suppliers are inside", //$NON-NLS-1$
                enteredFirst.await(10, TimeUnit.SECONDS) && enteredSecond.await(0, TimeUnit.SECONDS));
            assertEquals("both runs hold the one permit", 0, permits.availablePermits()); //$NON-NLS-1$
            holdFirst.countDown();
            assertTrue("the first run, the one a single hand-off would have kept, has left", //$NON-NLS-1$
                eventually(() -> leftFirst.get() && probe.entryFirst.isDone()));
            assertFalse("the second run has not left", leftSecond.get()); //$NON-NLS-1$
            assertEquals("the permit stays until the second run leaves", //$NON-NLS-1$
                0, permits.availablePermits());
        }
        finally
        {
            holdFirst.countDown();
            holdSecond.countDown();
        }
        assertTrue("the permit comes back when the last run leaves", //$NON-NLS-1$
            eventually(() -> permits.availablePermits() == 1 && leftFirst.get() && leftSecond.get()));
        if (probe.entryFirst != null)
        {
            PendingWorkRegistry.REFERENCES.remove(probe.runKeyFirst, probe.entryFirst);
        }
        if (probe.entrySecond != null)
        {
            PendingWorkRegistry.EXPORT.remove(probe.runKeySecond, probe.entrySecond);
        }
    }

    private void registerHeavy(IMcpTool probe)
    {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put(ToolWhiteboard.HEAVY, Boolean.TRUE);
        properties.put(ToolWhiteboard.WRITES, Boolean.FALSE);
        registrations.add(context.registerService(IMcpTool.class, probe, properties));
    }

    /**
     * A {@code tools/call} document for {@code name}, in the legacy shape the router serves
     * without a handshake.
     */
    private static String toolsCall(String name)
    {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + McpServerMeta.METHOD_TOOLS_CALL //$NON-NLS-1$
            + "\",\"params\":{\"name\":\"" + name + "\",\"arguments\":{}}}"; //$NON-NLS-1$ //$NON-NLS-2$
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

    /**
     * Starts a run, waits until its body has claimed the start, cancels the tracking, then throws.
     * The future is done; the supplier is still blocked on {@code hold}.
     */
    private static final class CancelThenThrowProbe implements IMcpTool
    {
        private final String name;

        private final CountDownLatch entered;

        private final CountDownLatch hold;

        private final AtomicBoolean left;

        private PendingWorkRegistry.PendingEntry entry;

        CancelThenThrowProbe(String name, CountDownLatch entered, CountDownLatch hold,
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
            String runKey = PendingWorkRegistry.computeRunKey(name, "throw-after-cancel"); //$NON-NLS-1$
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
            try
            {
                if (!entered.await(10, TimeUnit.SECONDS))
                {
                    throw new IllegalStateException("the supplier never entered"); //$NON-NLS-1$
                }
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while the supplier entered", interrupted); //$NON-NLS-1$
            }
            PendingWorkRegistry.REFERENCES.cancel(runKey);
            throw new IllegalStateException("cancelled then threw"); //$NON-NLS-1$
        }
    }

    /**
     * Starts two runs on two domains, waits until both bodies have entered, then throws. The first
     * one started is the one a hand-off that keeps a single entry would retain.
     */
    private static final class TwoRunsThenThrowProbe implements IMcpTool
    {
        private final String name;

        private final CountDownLatch enteredFirst;

        private final CountDownLatch enteredSecond;

        private final CountDownLatch holdFirst;

        private final CountDownLatch holdSecond;

        private final AtomicBoolean leftFirst;

        private final AtomicBoolean leftSecond;

        private String runKeyFirst;

        private String runKeySecond;

        private PendingWorkRegistry.PendingEntry entryFirst;

        private PendingWorkRegistry.PendingEntry entrySecond;

        TwoRunsThenThrowProbe(String name, CountDownLatch enteredFirst, CountDownLatch enteredSecond,
            CountDownLatch holdFirst, CountDownLatch holdSecond, AtomicBoolean leftFirst,
            AtomicBoolean leftSecond)
        {
            this.name = name;
            this.enteredFirst = enteredFirst;
            this.enteredSecond = enteredSecond;
            this.holdFirst = holdFirst;
            this.holdSecond = holdSecond;
            this.leftFirst = leftFirst;
            this.leftSecond = leftSecond;
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
            runKeyFirst = PendingWorkRegistry.computeRunKey(name, "two-runs-first"); //$NON-NLS-1$
            entryFirst = PendingWorkRegistry.REFERENCES.getOrStart(runKeyFirst, () -> {
                enteredFirst.countDown();
                try
                {
                    holdFirst.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                }
                leftFirst.set(true);
                return "first"; //$NON-NLS-1$
            });
            runKeySecond = PendingWorkRegistry.computeRunKey(name, "two-runs-second"); //$NON-NLS-1$
            entrySecond = PendingWorkRegistry.EXPORT.getOrStart(runKeySecond, () -> {
                enteredSecond.countDown();
                try
                {
                    holdSecond.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                }
                leftSecond.set(true);
                return "second"; //$NON-NLS-1$
            });
            try
            {
                if (!enteredFirst.await(10, TimeUnit.SECONDS)
                    || !enteredSecond.await(10, TimeUnit.SECONDS))
                {
                    throw new IllegalStateException("a supplier never entered"); //$NON-NLS-1$
                }
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while the suppliers entered", interrupted); //$NON-NLS-1$
            }
            throw new IllegalStateException("two runs then threw"); //$NON-NLS-1$
        }
    }
}
