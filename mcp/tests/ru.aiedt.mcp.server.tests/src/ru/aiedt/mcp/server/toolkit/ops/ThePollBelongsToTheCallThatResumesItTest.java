/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.support.ToolRoad;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ToolRoadOutcome;

/**
 * A poll is free only when the call that arrived will actually resume the run.
 * <p>
 * The heavy gates used to treat a matching route as that proof. A batch poll and an
 * {@code object_references} poll are real resumptions and must pass at a limit of one. A direct
 * rename, a text search, and {@code insights} {@code project_metrics} are not, so they take a
 * permit. A finished entry that nobody has collected yet is not a live run. A batch that is
 * handed an update's key refuses it and leaves the update's entry where it was.
 * </p>
 */
public class ThePollBelongsToTheCallThatResumesItTest
{
    private final List<String> added = new ArrayList<>();

    private final List<IMcpTool> externals = new ArrayList<>();

    private final List<CountDownLatch> openHolds = new ArrayList<>();

    private CountDownLatch holderRelease;

    private Thread holderThread;

    private CountDownLatch batchRelease;

    private Thread batchThread;

    @After
    public void theHoldsGo()
    {
        EditMetadataTool.beforeBatchApply = null;
        if (batchRelease != null)
        {
            batchRelease.countDown();
        }
        if (holderRelease != null)
        {
            holderRelease.countDown();
        }
        for (CountDownLatch hold : openHolds)
        {
            hold.countDown();
        }
        if (holderThread != null)
        {
            try
            {
                holderThread.join(15_000L);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }
        if (batchThread != null)
        {
            try
            {
                batchThread.join(15_000L);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        for (IMcpTool external : externals)
        {
            catalog.unregisterExternal(external);
        }
        for (String name : added)
        {
            catalog.unregister(name);
        }
    }

    /**
     * A batch that is still applying holds the only permit, and polling it does not take another.
     */
    @Test
    public void aBatchPollPassesWhileTheBatchHoldsTheOnlyPermit() throws Exception
    {
        try (RunningBatch batch = openBatch())
        {
            Map<String, String> poll = new LinkedHashMap<>();
            poll.put("batch", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            poll.put("runKey", batch.runKey); //$NON-NLS-1$
            poll.put("operations", batch.operations); //$NON-NLS-1$
            ToolRoad.Admission admission = batch.road.admit(EditMetadataTool.NAME, poll);
            assertNull("a batch poll is refused while its own run holds the only permit: " //$NON-NLS-1$
                + admission.refusal(), admission.refusal());
            assertFalse("a batch poll takes a permit of its own", admission.ticket().holdsPermit()); //$NON-NLS-1$
            assertEquals(0, batch.permits.availablePermits());
        }
    }

    /**
     * The same key handed to {@code rename_metadata_object} is a new heavy call. That tool does
     * not read the key, so the batch's permit does not cover it.
     */
    @Test
    public void aDirectRenameWithTheBatchKeyGoesThroughTheGate() throws Exception
    {
        try (RunningBatch batch = openBatch())
        {
            PendingWorkRegistry.PendingEntry entry = PendingWorkRegistry.UPDATE.get(batch.runKey);
            assertEquals(EditMetadataTool.NAME, entry.startedBy);
            ToolRoad.Admission direct = batch.road.admit(MetadataObjectRenamer.NAME,
                Map.of("runKey", batch.runKey)); //$NON-NLS-1$
            assertEquals("a direct rename with the batch key cleared the heavy gate", //$NON-NLS-1$
                ToolRoad.MSG_HEAVY_BUSY, direct.refusal());
        }
    }

    /**
     * {@code object_references} polls the reference search. {@code find_references} itself does
     * too. Either passes while the only permit is held.
     */
    @Test
    public void anObjectReferencesPollPassesWhileOnePermitIsHeld() throws Exception
    {
        PermitHold hold = holdTheOnlyPermit();
        String key = live(PendingWorkRegistry.REFERENCES, ReferenceLocator.NAME);
        ensure(new CodeSearchTool());
        ensure(new ReferenceLocator());
        ToolRoad.Admission throughTheFacade = hold.road.admit(CodeSearchTool.NAME,
            Map.of("operation", "object_references", "runKey", key)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("an object_references poll is refused at the limit: " //$NON-NLS-1$
            + throughTheFacade.refusal(), throughTheFacade.refusal());
        assertFalse(throughTheFacade.ticket().holdsPermit());
        ToolRoad.Admission direct = hold.road.admit(ReferenceLocator.NAME, Map.of("runKey", key)); //$NON-NLS-1$
        assertNull("a find_references poll is refused at the limit: " + direct.refusal(), //$NON-NLS-1$
            direct.refusal());
        assertFalse(direct.ticket().holdsPermit());
        assertEquals(0, hold.permits.availablePermits());
    }

    /**
     * {@code text_search} does not resume a reference search. The same key is a new heavy call.
     */
    @Test
    public void aTextSearchWithThatKeyGoesThroughTheGate() throws Exception
    {
        PermitHold hold = holdTheOnlyPermit();
        String key = live(PendingWorkRegistry.REFERENCES, ReferenceLocator.NAME);
        ensure(new CodeSearchTool());
        ToolRoad.Admission search = hold.road.admit(CodeSearchTool.NAME,
            Map.of("operation", "text_search", "runKey", key)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("a text search with a reference key cleared the heavy gate", //$NON-NLS-1$
            ToolRoad.MSG_HEAVY_BUSY, search.refusal());
    }

    /**
     * {@code insights} {@code project_metrics} routes at a heavy tool and then calls {@code execute},
     * which starts a new traversal. A live key of that traversal does not exempt the call, and the
     * body is not run once the gate has refused it.
     */
    @Test
    public void projectMetricsThroughInsightsGoesThroughTheGateAndDoesNotRun() throws Exception
    {
        PermitHold hold = holdTheOnlyPermit();
        String key = live(PendingWorkRegistry.GENERIC, "project_metrics"); //$NON-NLS-1$
        ensure(new InsightsFacadeTool());
        Map<String, String> arguments = Map.of("operation", "project_metrics", "runKey", key); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        ToolRoad.Admission admission = hold.road.admit(InsightsFacadeTool.NAME, arguments);
        assertEquals(ToolRoad.MSG_HEAVY_BUSY, admission.refusal());
        ToolRoadOutcome called = hold.road.call(InsightsFacadeTool.NAME,
            Map.of("operation", "project_metrics", "runKey", key), "poll-test"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("insights ran a second project_metrics traversal without a permit: " //$NON-NLS-1$
            + called.text(), ToolRoad.MSG_HEAVY_BUSY, called.refusal());
        assertEquals(0, hold.permits.availablePermits());
    }

    /**
     * A finished entry that has not been collected is still in the registry. Handed to a heavy
     * call that does not resume it, the key goes through the gate and takes a permit.
     */
    @Test
    public void aCompletedKeyOnAToolThatDoesNotResumeGoesThroughTheGate() throws Exception
    {
        Semaphore permits = new Semaphore(1);
        ToolRoad road = new ToolRoad(permits);
        String key = "poll-done-" + System.nanoTime(); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry entry = PendingWorkRegistry.GENERIC.getOrStart(key,
            job -> "done"); //$NON-NLS-1$
        entry.startedBy = "project_metrics"; //$NON-NLS-1$
        assertEquals("done", entry.await(10_000L)); //$NON-NLS-1$
        assertTrue("the entry never finished", eventually(() -> entry.completedAt > 0)); //$NON-NLS-1$
        try
        {
            ensure(new InsightsFacadeTool());
            ToolRoad.Admission admission = road.admit(InsightsFacadeTool.NAME,
                Map.of("operation", "project_metrics", "runKey", key)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertNull(admission.refusal());
            assertTrue("a finished key exempted a call that does not resume it", //$NON-NLS-1$
                admission.ticket().holdsPermit());
            assertEquals(0, permits.availablePermits());
            admission.ticket().release();
        }
        finally
        {
            PendingWorkRegistry.GENERIC.remove(key);
        }
    }

    /**
     * A batch handed an update's key refuses it. The update's entry stays, including after the
     * update has already finished and its result is sitting there to be collected.
     */
    @Test
    public void aBatchPollOfAnUpdateKeyRefusesAndLeavesTheUpdate() throws Exception
    {
        String key = "poll-update-" + System.nanoTime(); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry entry = PendingWorkRegistry.UPDATE.getOrStart(key,
            job -> "updated"); //$NON-NLS-1$
        entry.workKind = DatabaseUpdater.WORK_KIND;
        entry.startedBy = DatabaseUpdater.NAME;
        assertEquals("updated", entry.await(10_000L)); //$NON-NLS-1$
        try
        {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("batch", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("runKey", key); //$NON-NLS-1$
            params.put("operations", "[{\"operation\":\"not_a_real_op\"}]"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("timeoutSeconds", "5"); //$NON-NLS-1$ //$NON-NLS-2$
            String answer = new EditMetadataTool().execute(params);
            assertTrue("the batch did not refuse the update's key: " + answer, //$NON-NLS-1$
                answer.contains("update_database") && answer.contains("not to edit_metadata")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull("the batch removed the update's entry", //$NON-NLS-1$
                PendingWorkRegistry.UPDATE.get(key));
            assertEquals("updated", PendingWorkRegistry.UPDATE.get(key).cachedResult); //$NON-NLS-1$
        }
        finally
        {
            PendingWorkRegistry.UPDATE.remove(key);
        }
    }

    /**
     * An update polled through {@code infobase_admin} is a real resumption and stays free. The
     * facade calls {@code DatabaseUpdater}, which reads the key.
     */
    @Test
    public void anUpdatePollThroughTheFacadeStaysFree() throws Exception
    {
        PermitHold hold = holdTheOnlyPermit();
        String key = live(PendingWorkRegistry.UPDATE, DatabaseUpdater.NAME);
        PendingWorkRegistry.UPDATE.get(key).workKind = DatabaseUpdater.WORK_KIND;
        ensure(new InfobaseAdminFacadeTool());
        ensure(new DatabaseUpdater());
        ToolRoad.Admission admission = hold.road.admit(InfobaseAdminFacadeTool.NAME,
            Map.of("operation", "update_database", "runKey", key)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("an update poll through the facade is refused at the limit: " //$NON-NLS-1$
            + admission.refusal(), admission.refusal());
        assertFalse(admission.ticket().holdsPermit());
    }

    private RunningBatch openBatch() throws Exception
    {
        ensure(new EditMetadataTool());
        ensure(new MetadataObjectRenamer());
        String operations = "[{\"operation\":\"not_a_real_op_" + System.nanoTime() + "\"}]"; //$NON-NLS-1$ //$NON-NLS-2$
        String runKey = PendingWorkRegistry.computeRunKey("batch", null, null, "false", "false", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            operations);
        Semaphore permits = new Semaphore(1);
        ToolRoad road = new ToolRoad(permits);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        batchRelease = release;
        EditMetadataTool.beforeBatchApply = () -> {
            entered.countDown();
            try
            {
                if (!release.await(30, TimeUnit.SECONDS))
                {
                    throw new IllegalStateException("the batch hold was never released"); //$NON-NLS-1$
                }
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
        };
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("batch", Boolean.TRUE); //$NON-NLS-1$
        arguments.put("operations", operations); //$NON-NLS-1$
        arguments.put("timeoutSeconds", Integer.valueOf(5)); //$NON-NLS-1$
        AtomicReference<ToolRoadOutcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread call = new Thread(() -> {
            try
            {
                outcome.set(road.call(EditMetadataTool.NAME, arguments, "poll-test")); //$NON-NLS-1$
            }
            catch (Throwable thrown)
            {
                failure.set(thrown);
            }
        });
        call.setDaemon(true);
        batchThread = call;
        call.start();
        assertTrue("the batch never reached its body; outcome=" + outcome.get() //$NON-NLS-1$
            + " failure=" + failure.get(), entered.await(15, TimeUnit.SECONDS)); //$NON-NLS-1$
        PendingWorkRegistry.PendingEntry entry = PendingWorkRegistry.UPDATE.get(runKey);
        assertNotNull("no batch entry under the computed key", entry); //$NON-NLS-1$
        assertEquals(EditMetadataTool.NAME, entry.startedBy);
        assertEquals(0, permits.availablePermits());
        return new RunningBatch(road, permits, runKey, operations, call, release, outcome);
    }

    private PermitHold holdTheOnlyPermit() throws Exception
    {
        Semaphore permits = new Semaphore(1);
        ToolRoad road = new ToolRoad(permits);
        holderRelease = new CountDownLatch(1);
        IMcpTool holder = new BlockingProbe("poll_holder_" + System.nanoTime(), holderRelease); //$NON-NLS-1$
        McpToolCatalog.getInstance().registerExternal(holder, false, true);
        externals.add(holder);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        holderThread = new Thread(() -> {
            try
            {
                road.call(holder.getName(), Map.of(), "poll-test"); //$NON-NLS-1$
            }
            catch (Throwable thrown)
            {
                failure.set(thrown);
            }
        });
        holderThread.setDaemon(true);
        holderThread.start();
        assertTrue("the holder never took the permit: " + failure.get(), //$NON-NLS-1$
            eventually(() -> permits.availablePermits() == 0));
        return new PermitHold(road, permits);
    }

    private String live(PendingWorkRegistry domain, String startedBy) throws Exception
    {
        String key = "poll-live-" + System.nanoTime(); //$NON-NLS-1$
        CountDownLatch hold = new CountDownLatch(1);
        openHolds.add(hold);
        PendingWorkRegistry.PendingEntry entry = domain.getOrStart(key, job -> {
            try
            {
                hold.await(30, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
            return "live"; //$NON-NLS-1$
        });
        entry.startedBy = startedBy;
        return key;
    }

    private void ensure(IMcpTool tool)
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        if (catalog.getTool(tool.getName()) == null)
        {
            catalog.register(tool);
            added.add(tool.getName());
        }
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws Exception
    {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline)
        {
            if (condition.getAsBoolean())
            {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }

    private static final class PermitHold
    {
        final ToolRoad road;

        final Semaphore permits;

        PermitHold(ToolRoad road, Semaphore permits)
        {
            this.road = road;
            this.permits = permits;
        }
    }

    private static final class RunningBatch implements AutoCloseable
    {
        final ToolRoad road;

        final Semaphore permits;

        final String runKey;

        final String operations;

        private final Thread call;

        private final CountDownLatch release;

        private final AtomicReference<ToolRoadOutcome> outcome;

        RunningBatch(ToolRoad road, Semaphore permits, String runKey, String operations, Thread call,
            CountDownLatch release, AtomicReference<ToolRoadOutcome> outcome)
        {
            this.road = road;
            this.permits = permits;
            this.runKey = runKey;
            this.operations = operations;
            this.call = call;
            this.release = release;
            this.outcome = outcome;
        }

        @Override
        public void close() throws Exception
        {
            release.countDown();
            EditMetadataTool.beforeBatchApply = null;
            call.join(20_000L);
            PendingWorkRegistry.UPDATE.remove(runKey);
            if (outcome.get() != null && outcome.get().refused())
            {
                throw new AssertionError("the batch call was refused: " + outcome.get().refusal()); //$NON-NLS-1$
            }
        }
    }

    /** A heavy tool that blocks in {@code execute}, so its call keeps the permit. */
    private static final class BlockingProbe implements IMcpTool
    {
        private final String name;

        private final CountDownLatch hold;

        BlockingProbe(String name, CountDownLatch hold)
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
            try
            {
                hold.await(30, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
            return "held"; //$NON-NLS-1$
        }
    }
}
