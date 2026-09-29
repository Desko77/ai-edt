/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.PendingEnvelope;
import ru.aiedt.mcp.server.support.PendingWorkRegistry;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * The pull from the infobase answers Pending when it outlasts its budget, a later call with the
 * same runKey collects the result, and cancelling says the platform call is still running once
 * the launch was claimed.
 */
public class ThePullFromTheInfobaseIsPendingTest
{
    /**
     * A pull that is still inside its launch after the wait budget answers Pending, and the same
     * key then returns what the pull produced.
     */
    @Test
    public void aPullThatOutlastsItsBudgetAnswersPendingAndResumes() throws Exception
    {
        String key = PendingWorkRegistry.computeRunKey("retrieve-pending", UUID.randomUUID().toString()); //$NON-NLS-1$
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            PendingWorkRegistry.RETRIEVE.getOrStart(key, entry ->
            {
                assertTrue(entry.claimTheLaunch());
                holding.countDown();
                await(release);
                return "{\"pulled\":true}"; //$NON-NLS-1$
            });
            assertTrue("the pull did not reach its launch", holding.await(5, TimeUnit.SECONDS)); //$NON-NLS-1$

            String pending = SyncControlTool.trackRetrieve(key, "Probe", 200L, entry -> "not-this-call"); //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject body = JsonParser.parseString(pending).getAsJsonObject();
            assertEquals("Pending", body.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(key, body.get("runKey").getAsString()); //$NON-NLS-1$
            assertTrue(body.get(PendingEnvelope.MARK).getAsBoolean());
            assertEquals("retrieve_database_changes", //$NON-NLS-1$
                PendingWorkRegistry.RETRIEVE.get(key).startedBy);

            release.countDown();
            String done = SyncControlTool.resumeRetrieve(key, 5_000L);
            assertTrue(done, done.contains("\"pulled\":true")); //$NON-NLS-1$
            assertNull("a collected result leaves the registry", PendingWorkRegistry.RETRIEVE.get(key)); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
            PendingWorkRegistry.RETRIEVE.remove(key);
        }
    }

    /**
     * Cancelling after the launch was claimed stops tracking and says the platform call is still
     * running. A key that was never started says it was not found.
     */
    @Test
    public void cancellingAClaimedPullSaysThePlatformCallIsStillRunning() throws Exception
    {
        String key = PendingWorkRegistry.computeRunKey("retrieve-cancel", UUID.randomUUID().toString()); //$NON-NLS-1$
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            PendingWorkRegistry.RETRIEVE.getOrStart(key, entry ->
            {
                assertTrue(entry.claimTheLaunch());
                holding.countDown();
                await(release);
                return "finished"; //$NON-NLS-1$
            });
            assertTrue(holding.await(5, TimeUnit.SECONDS));

            String cancelled = SyncControlTool.cancelRetrieve(key);
            JsonObject body = JsonParser.parseString(cancelled).getAsJsonObject();
            assertTrue(body.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(body.get("cancelled").getAsBoolean()); //$NON-NLS-1$
            assertTrue(body.get("platformCallStillRunning").getAsBoolean()); //$NON-NLS-1$
            assertTrue(body.get("note").getAsString().contains("still running")); //$NON-NLS-1$ //$NON-NLS-2$

            String missing = SyncControlTool.cancelRetrieve(key + "none"); //$NON-NLS-1$
            JsonObject gone = JsonParser.parseString(missing).getAsJsonObject();
            assertFalse(gone.get("cancelled").getAsBoolean()); //$NON-NLS-1$
            assertFalse(gone.has("platformCallStillRunning")); //$NON-NLS-1$
            assertTrue(gone.get("note").getAsString().contains("not found")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            release.countDown();
            PendingWorkRegistry.RETRIEVE.remove(key);
        }
    }

    /**
     * A runKey the registry does not hold is a missing pull, not a start.
     */
    @Test
    public void anUnknownRunKeyIsNotFound()
    {
        String missing = SyncControlTool.resumeRetrieve("no-such-pull", 1_000L); //$NON-NLS-1$
        assertTrue(missing, missing.contains("not found")); //$NON-NLS-1$
        assertTrue(missing.contains("\"success\":false")); //$NON-NLS-1$
    }

    /**
     * No thick-client launch is not a refusal. A named one is, and the sentence carries the name.
     */
    @Test
    public void aVisibleThickClientIsNamedAndAnEmptyListIsNot()
    {
        assertNull(SyncControlTool.refusalForThickClients(List.of()));
        assertNull(SyncControlTool.refusalForThickClients(null));
        String refusal = SyncControlTool.refusalForThickClients(List.of("Ordinary forms")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Ordinary forms")); //$NON-NLS-1$
        assertTrue(refusal.contains("was not started")); //$NON-NLS-1$
    }

    /**
     * A failed baseline rewrite is a field of the pull's own answer. A successful one is not an
     * error.
     */
    @Test
    public void aFailedBaselineMarkIsVisibleOnTheAnswer()
    {
        ToolResult failed = ToolResult.success();
        SyncControlTool.surfaceTheBaselineMark(failed, ToolResult.error("stamp failed").toJson()); //$NON-NLS-1$
        JsonObject failure = JsonParser.parseString(failed.toJson()).getAsJsonObject();
        assertFalse(failure.get("baselineMarked").getAsBoolean()); //$NON-NLS-1$
        assertEquals("stamp failed", failure.get("baselineMarkError").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult marked = ToolResult.success();
        SyncControlTool.surfaceTheBaselineMark(marked, ToolResult.success().toJson());
        JsonObject success = JsonParser.parseString(marked.toJson()).getAsJsonObject();
        assertTrue(success.get("baselineMarked").getAsBoolean()); //$NON-NLS-1$
        assertFalse(success.has("baselineMarkError")); //$NON-NLS-1$
    }

    /**
     * The schema says what {@code timeoutSeconds} actually limits, and the facade names the pull
     * in the three places a caller reads the operation from.
     */
    @Test
    public void theSchemaAndTheFacadeNameThePull()
    {
        String schema = new SyncControlTool().getInputSchema();
        assertTrue(schema.contains("waitForBuildAndDerivedData")); //$NON-NLS-1$
        assertTrue(schema.contains("runKey")); //$NON-NLS-1$
        assertTrue(schema.contains("cancel")); //$NON-NLS-1$
        assertTrue(schema.contains("baselineMarked")); //$NON-NLS-1$

        InfobaseAdminFacadeTool facade = new InfobaseAdminFacadeTool();
        String facadeSchema = facade.getInputSchema();
        assertTrue(facadeSchema.contains("retrieve_database_changes")); //$NON-NLS-1$
        assertTrue(facadeSchema.contains("replaceLocal")); //$NON-NLS-1$
        assertTrue(facadeSchema.contains("markSynchronized")); //$NON-NLS-1$
        assertTrue(facadeSchema.contains("waitForBuildAndDerivedData")); //$NON-NLS-1$

        Map<String, String> missing = new LinkedHashMap<>();
        missing.put("operation", "sync_control"); //$NON-NLS-1$ //$NON-NLS-2$
        String rejected = facade.execute(missing);
        assertTrue(rejected, rejected.contains("retrieve_database_changes")); //$NON-NLS-1$

        Map<String, String> help = new LinkedHashMap<>();
        help.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
        String bulletin = facade.execute(help);
        assertTrue(bulletin, bulletin.contains("retrieve_database_changes")); //$NON-NLS-1$
        assertTrue(bulletin.contains("replaceLocal")); //$NON-NLS-1$
        assertTrue(bulletin.contains("markSynchronized")); //$NON-NLS-1$

        assertEquals("retrieve_database_changes", //$NON-NLS-1$
            new SyncControlTool().routesTo(call("retrieve_database_changes"))); //$NON-NLS-1$
        assertEquals("retrieve_database_changes", facade.routesTo(syncControl())); //$NON-NLS-1$
        assertEquals("retrieve_database_changes", //$NON-NLS-1$
            new SyncControlTool().resumes(PendingWorkRegistry.RETRIEVE.domain(),
                "retrieve_database_changes")); //$NON-NLS-1$
        assertEquals("retrieve_database_changes", //$NON-NLS-1$
            facade.resumes(PendingWorkRegistry.RETRIEVE.domain(), "sync_control")); //$NON-NLS-1$
        assertNull(facade.resumes(PendingWorkRegistry.UPDATE.domain(), "sync_control")); //$NON-NLS-1$
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            latch.await(15, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, String> call(String operation)
    {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("operation", operation); //$NON-NLS-1$
        return arguments;
    }

    private static Map<String, String> syncControl()
    {
        Map<String, String> arguments = call("sync_control"); //$NON-NLS-1$
        arguments.put("syncOperation", "retrieve_database_changes"); //$NON-NLS-1$ //$NON-NLS-2$
        return arguments;
    }
}
