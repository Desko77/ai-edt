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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationCheckUnknownStateTreatment;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationArtifact;
import com.e1c.g5.dt.applications.IApplicationListener;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.IApplicationType;
import com.e1c.g5.dt.applications.IUrlAccess;
import com.e1c.g5.dt.applications.LifecycleState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.PendingWorkRegistry;

/**
 * A dry run reads what cannot start an update, and the record of what it read is the test.
 *
 * <p>The probe used to answer with the readiness check an update performs, and that check reaches
 * the infobase synchronization cycle: a thick client process, {@code config generation-id},
 * {@code config dump-files}, a load into the infobase. Behind an open thick-client session the load
 * waits for a monopoly it cannot get, so the probe did not return and the EDT it was asked about
 * hung with it.</p>
 *
 * <p>The application manager below is a stand-in that records every call it receives, and the
 * refresh is one that counts how often it was asked. Both are read as data: a probe that reaches
 * for anything beyond the update state, or a probe that refreshes when the caller turned the
 * refresh off, is a wrong count rather than a matter of opinion.</p>
 */
public class ADryRunReadsOnlyWhatCannotStartAnUpdateTest
{
    /**
     * The one read a probe is allowed: the state the environment holds. Not the readiness check,
     * which synchronizes with the infobase, and not the update.
     */
    @Test
    public void aProbeAsksTheEnvironmentForItsStateAndNothingElse()
    {
        RecordingApplications manager =
            new RecordingApplications(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        IApplication application = new StubApplication("app-1"); //$NON-NLS-1$

        String answer = DatabaseUpdater.whatAnUpdateWouldFace(manager, application, null,
            "app-1", "proj", false, "proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("a probe reads exactly one thing off the environment", //$NON-NLS-1$
            List.of("getUpdateState"), manager.calls); //$NON-NLS-1$
        assertSame("and reads it for the application the call named", application, //$NON-NLS-1$
            manager.lastApplication);

        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertEquals("INCREMENTAL_UPDATE_REQUIRED", json.get("updateState").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("wouldUpdate").getAsBoolean()); //$NON-NLS-1$
        assertFalse("the readiness field is gone, not answered with a blank", //$NON-NLS-1$
            json.has("readiness")); //$NON-NLS-1$
        assertFalse(json.has("readinessProblems")); //$NON-NLS-1$

        List<String> notChecked = new ArrayList<>();
        for (com.google.gson.JsonElement each : json.getAsJsonArray("notCheckedInDryRun")) //$NON-NLS-1$
        {
            notChecked.add(each.getAsString());
        }
        assertEquals(List.of("readiness", "exportValidation"), notChecked); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the answer says why readiness was not asked for", //$NON-NLS-1$
            json.get("notCheckedInDryRunNote").getAsString().contains("thick client")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The refresh is performed when the caller asked for it - once - and not at all when the caller
     * turned it off, and the answer carries the report only in the first case.
     */
    @Test
    public void theWorkspaceIsRefreshedOnceAndOnlyWhenAsked()
    {
        RecordingApplications manager = new RecordingApplications(ApplicationUpdateState.UPDATED);
        IApplication application = new StubApplication("app-1"); //$NON-NLS-1$
        CountingRefresh refresh = new CountingRefresh();

        String refreshed = DatabaseUpdater.whatAnUpdateWouldFace(manager, application, refresh,
            "app-1", "proj", false, "proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("asked for, the workspace is refreshed exactly once", 1, refresh.calls.get()); //$NON-NLS-1$
        JsonObject refreshedJson = JsonParser.parseString(refreshed).getAsJsonObject();
        assertTrue(refreshedJson.has("workspaceRefresh")); //$NON-NLS-1$
        assertEquals(0,
            refreshedJson.getAsJsonObject("workspaceRefresh").get("changedResources").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$

        String quiet = DatabaseUpdater.whatAnUpdateWouldFace(manager, application, null,
            "app-1", "proj", false, "proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("turned off, no refresh was performed at all", 1, refresh.calls.get()); //$NON-NLS-1$
        assertFalse("and the answer claims nothing about one", //$NON-NLS-1$
            JsonParser.parseString(quiet).getAsJsonObject().has("workspaceRefresh")); //$NON-NLS-1$
    }

    /**
     * The flag is read off the call: off means no refresh to run, and no argument means on, which is
     * the default the schema advertises. The refresh handed back is the one that reports the count.
     */
    @Test
    public void theRefreshIsReadOffTheCall()
    {
        assertNull("refreshWorkspace=false is no refresh, not an empty one", //$NON-NLS-1$
            DatabaseUpdater.refreshForProbe(Map.of("refreshWorkspace", "false"), null, null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("no argument means on", //$NON-NLS-1$
            DatabaseUpdater.refreshForProbe(Map.of(), null, null));
        assertNotNull("refreshWorkspace=true means on", //$NON-NLS-1$
            DatabaseUpdater.refreshForProbe(Map.of("refreshWorkspace", "true"), null, null)); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject report = DatabaseUpdater.refreshForProbe(Map.of(), null, null).refresh();
        assertTrue("the report the answer carries counts what was picked up", //$NON-NLS-1$
            report.has("changedResources")); //$NON-NLS-1$
    }

    /**
     * A probe is not a run: arriving while an update with the same arguments is in flight, it is
     * answered in place, leaves the running entry alone, and is never served the update's answer.
     */
    @Test
    public void aProbeDoesNotJoinTheUpdateItSharesArgumentsWith()
        throws Exception
    {
        String runKey = PendingWorkRegistry.computeRunKey("proj", "app-1", "false", "true", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "false", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PendingWorkRegistry.PendingEntry running = PendingWorkRegistry.UPDATE.getOrStart(runKey, () ->
        {
            inFlight.countDown();
            try
            {
                release.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
            return "{\"updateComplete\":true}"; //$NON-NLS-1$
        });
        int trackedBefore = PendingWorkRegistry.UPDATE.size();
        try
        {
            assertTrue("the update is running before the probe arrives", //$NON-NLS-1$
                inFlight.await(20, TimeUnit.SECONDS));
            AtomicBoolean probeAnswered = new AtomicBoolean();

            String answer = DatabaseUpdater.runOrAnswer(true, runKey, PendingWorkRegistry.UPDATE,
                "proj", 2000L, () -> //$NON-NLS-1$
                {
                    probeAnswered.set(true);
                    return "{\"dryRun\":true}"; //$NON-NLS-1$
                });

            assertEquals("{\"dryRun\":true}", answer); //$NON-NLS-1$
            assertTrue(probeAnswered.get());
            assertSame("the running update is still the entry under this key", running, //$NON-NLS-1$
                PendingWorkRegistry.UPDATE.get(runKey));
            assertEquals("and the probe left no entry of its own", trackedBefore, //$NON-NLS-1$
                PendingWorkRegistry.UPDATE.size());
        }
        finally
        {
            release.countDown();
            PendingWorkRegistry.UPDATE.remove(runKey);
        }
    }

    /**
     * A probe leaves nothing behind, so an update arriving after one has no entry to join and runs
     * its own body - the failure it guards against is an update that joins a probe, reports the
     * probe's answer and never updates anything.
     */
    @Test
    public void anUpdateAfterAProbeRunsItsOwnBody()
    {
        String runKey = PendingWorkRegistry.computeRunKey("proj2", "app-2", "false", "true", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "false", "false"); //$NON-NLS-1$ //$NON-NLS-2$

        String probe = DatabaseUpdater.runOrAnswer(true, runKey, PendingWorkRegistry.UPDATE,
            "proj2", 2000L, () -> "{\"dryRun\":true}"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("{\"dryRun\":true}", probe); //$NON-NLS-1$
        assertNull("a probe leaves no run to join", PendingWorkRegistry.UPDATE.get(runKey)); //$NON-NLS-1$

        AtomicBoolean updateRan = new AtomicBoolean();
        String answer = DatabaseUpdater.runOrAnswer(false, runKey, PendingWorkRegistry.UPDATE,
            "proj2", 30_000L, () -> //$NON-NLS-1$
            {
                updateRan.set(true);
                return "{\"updateComplete\":true}"; //$NON-NLS-1$
            });

        assertTrue("the update ran rather than joining a probe", updateRan.get()); //$NON-NLS-1$
        assertEquals("{\"updateComplete\":true}", answer); //$NON-NLS-1$
        assertNull("a collected run leaves nothing tracked", PendingWorkRegistry.UPDATE.get(runKey)); //$NON-NLS-1$
    }

    /**
     * An application manager that records every call it receives.
     * <p>
     * The recording is the point: the assertion in each test is over the whole list, so a probe that
     * reaches for one more read of the environment fails on the list rather than passing quietly.
     * {@link #check} is recorded like any other call and must never appear in it - it is the call
     * that synchronizes with the infobase.
     * </p>
     */
    private static final class RecordingApplications implements IApplicationManager
    {
        private final ApplicationUpdateState state;

        final List<String> calls = new ArrayList<>();

        IApplication lastApplication;

        RecordingApplications(ApplicationUpdateState state)
        {
            this.state = state;
        }

        @Override
        public ApplicationUpdateState getUpdateState(IApplication application)
        {
            calls.add("getUpdateState"); //$NON-NLS-1$
            lastApplication = application;
            return state;
        }

        @Override
        public IStatus check(IApplication application, ApplicationCheckUnknownStateTreatment treatment,
            ExecutionContext context, IProgressMonitor monitor)
        {
            calls.add("check"); //$NON-NLS-1$
            return Status.OK_STATUS;
        }

        @Override
        public ApplicationUpdateState update(IApplication application, ApplicationUpdateType type,
            ExecutionContext context, IProgressMonitor monitor)
        {
            calls.add("update"); //$NON-NLS-1$
            return state;
        }

        @Override
        public Optional<IApplication> getApplication(IProject project, String applicationId)
        {
            calls.add("getApplication"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public List<IApplication> getApplications(IProject project)
        {
            calls.add("getApplications"); //$NON-NLS-1$
            return List.of();
        }

        @Override
        public Optional<IProject> getDefaultProject()
        {
            calls.add("getDefaultProject"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public Optional<IApplication> getDefaultApplication(IProject project)
        {
            calls.add("getDefaultApplication"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public Optional<IUrlAccess> getDefaultUrlAccess(IApplication application)
        {
            calls.add("getDefaultUrlAccess"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public LifecycleState getLifecycleState(com.e1c.g5.dt.applications.ILifecycleAware lifecycleAware)
        {
            calls.add("getLifecycleState"); //$NON-NLS-1$
            return null;
        }

        @Override
        public List<IApplicationType> getApplicationTypes()
        {
            calls.add("getApplicationTypes"); //$NON-NLS-1$
            return List.of();
        }

        @Override
        public List<IApplicationArtifact> getApplicationArtifacts(Object owner)
        {
            calls.add("getApplicationArtifacts"); //$NON-NLS-1$
            return List.of();
        }

        @Override
        public List<IUrlAccess> getUrlAccesses(IApplication application)
        {
            calls.add("getUrlAccesses"); //$NON-NLS-1$
            return List.of();
        }

        @Override
        public void setDefaultApplication(IProject project, IApplication application)
        {
            calls.add("setDefaultApplication"); //$NON-NLS-1$
        }

        @Override
        public void setDefaultUrlAccess(IApplication application, IUrlAccess urlAccess)
        {
            calls.add("setDefaultUrlAccess"); //$NON-NLS-1$
        }

        @Override
        public void prepare(IApplication application, String mode, ExecutionContext context,
            IProgressMonitor monitor)
        {
            calls.add("prepare"); //$NON-NLS-1$
        }

        @Override
        public void cleanup(IApplication application, ExecutionContext context, IProgressMonitor monitor)
        {
            calls.add("cleanup"); //$NON-NLS-1$
        }

        @Override
        public Optional<Process> start(IApplication application, ExecutionContext context,
            IProgressMonitor monitor)
        {
            calls.add("start"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public void addAppllicationListener(IApplicationListener listener)
        {
            calls.add("addAppllicationListener"); //$NON-NLS-1$
        }

        @Override
        public void removeAppllicationListener(IApplicationListener listener)
        {
            calls.add("removeAppllicationListener"); //$NON-NLS-1$
        }

        @Override
        public void delete(IApplication application, boolean deleteFiles)
        {
            calls.add("delete"); //$NON-NLS-1$
        }

        @Override
        public Optional<IApplication> findApplicationByInfobase(
            com._1c.g5.v8.dt.platform.services.model.InfobaseReference infobase)
        {
            calls.add("findApplicationByInfobase"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public Optional<IApplication> findApplicationByInfobaseAndProject(
            com._1c.g5.v8.dt.platform.services.model.InfobaseReference infobase, IProject project)
        {
            calls.add("findApplicationByInfobaseAndProject"); //$NON-NLS-1$
            return Optional.empty();
        }

        @Override
        public String suggestNewApplicationName(String projectName, String applicationType)
        {
            calls.add("suggestNewApplicationName"); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * A refresh that counts how often it was asked, and reports the count the answer promises.
     */
    private static final class CountingRefresh implements DatabaseUpdater.WorkspaceRefresh
    {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public JsonObject refresh()
        {
            calls.incrementAndGet();
            JsonObject report = new JsonObject();
            report.addProperty("changedResources", Integer.valueOf(0)); //$NON-NLS-1$
            return report;
        }
    }

    /**
     * An application with an id and nothing else: the probe hands it to the manager and reads nothing
     * off it, and the tests assert the manager saw the instance they passed.
     */
    private static final class StubApplication implements IApplication
    {
        private final String id;

        StubApplication(String id)
        {
            this.id = id;
        }

        @Override
        public String getId()
        {
            return id;
        }

        @Override
        public String getName()
        {
            return id;
        }

        @Override
        public IProject getProject()
        {
            return null;
        }

        @Override
        public IApplicationType getType()
        {
            return null;
        }

        @Override
        public List<IApplicationArtifact> getArtifacts()
        {
            return List.of();
        }

        @Override
        public Optional<String> getRequiredVersion()
        {
            return Optional.empty();
        }

        @Override
        public IApplication getApplication()
        {
            return this;
        }

        @Override
        public <T> T getAdapter(Class<T> adapter)
        {
            return null;
        }
    }

    /**
     * A dry run runs no export scan and an update still runs it first: the scan walks the whole
     * project, and a dry run answers from what the environment already holds.
     */
    @Test
    public void aProbeDoesNotScanTheProjectForExport()
    {
        int[] scans = {0};
        java.util.function.BiFunction<String, Boolean, String> scan = (name, skip) -> {
            scans[0]++;
            return null;
        };
        assertNull(DatabaseUpdater.exportScanBefore("P", false, true, scan)); //$NON-NLS-1$
        assertEquals("a dry run runs no export scan", 0, scans[0]); //$NON-NLS-1$
        DatabaseUpdater.exportScanBefore("P", false, false, scan); //$NON-NLS-1$
        assertEquals("an update still scans first", 1, scans[0]); //$NON-NLS-1$
    }
}
