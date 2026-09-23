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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

import ru.aiedt.mcp.server.support.DumpInfoProbe;

/**
 * A stored ConfigDumpInfo.xml of a foreign format stops an update before anything is asked of the
 * infobase - measured 22.09: EDT 2026.2 writes format {@code 2.20}, the 8.3.27 platform reads and
 * writes {@code 2.7} and answers {@code FullDump}, so the update silently becomes a full load.
 *
 * <p>The application manager below records every call it receives. The gate being tested decides
 * BEFORE the manager's first call, so a foreign format answers with a refusal and an empty record,
 * and the override ({@code ignoreDumpInfoFormat=true}) passes and the manager is asked - which is
 * the whole difference between "stopped" and "went ahead knowingly".</p>
 */
public class ADumpInfoFormatStopsTheUpdateTest
{
    /** The reading the whole class is about: a 2.20 file where the platform writes 2.7. */
    private static DumpInfoProbe.Reading foreignFile()
    {
        return DumpInfoProbe.reading("E:/ws/.metadata/ib-sync/ss/<uuid>/ConfigDumpInfo.xml", //$NON-NLS-1$
            "2.20", "2.7", "8.3.27.2214"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A foreign format stops the update with both formats named, and NOTHING is asked of the
     * application manager on the way - not the update state, not the readiness check, not the
     * update itself.
     */
    @Test
    public void aForeignFormatStopsTheUpdateBeforeTheManagerIsAskedAnything()
    {
        RecordingApplications manager =
            new RecordingApplications(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);

        String stop = DatabaseUpdater.stopOnForeignDumpInfoFormat(foreignFile(), false);

        assertNotNull(stop);
        JsonObject refusal = JsonParser.parseString(stop).getAsJsonObject();
        assertEquals("2.20", refusal.get("dumpInfoFormat").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("2.7", refusal.get("expectedDumpInfoFormat").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("8.3.27.2214", refusal.get("platformVersion").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("dumpInfoFormat", refusal.get("tag").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("sync_control syncOperation=rebuild_dump_info confirm=true", //$NON-NLS-1$
            refusal.get("nextStep").getAsString()); //$NON-NLS-1$
        assertTrue(refusal.get("dumpInfoFile").getAsString().endsWith("ConfigDumpInfo.xml")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal says what the update would silently become", //$NON-NLS-1$
            refusal.get("error").getAsString().contains("FULL")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the refusal names the way out of the check", //$NON-NLS-1$
            refusal.get("error").getAsString().contains("ignoreDumpInfoFormat")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("nothing was asked of the application manager while the format was foreign", //$NON-NLS-1$
            manager.calls.isEmpty());
    }

    /**
     * With the override the gate passes, and the first thing asked after it is the update state -
     * the update goes ahead, and its answer says the check was overridden.
     */
    @Test
    public void withTheOverrideTheGatePassesAndTheManagerIsAsked()
    {
        RecordingApplications manager =
            new RecordingApplications(ApplicationUpdateState.UPDATED);
        IApplication application = new StubApplication("app-1"); //$NON-NLS-1$

        assertNull(DatabaseUpdater.stopOnForeignDumpInfoFormat(foreignFile(), true));

        // The first thing update_database asks of the manager once the gate has passed:
        manager.getUpdateState(application);
        assertEquals("past the gate, the manager is asked - the update goes ahead", //$NON-NLS-1$
            List.of("getUpdateState"), manager.calls); //$NON-NLS-1$

        String described = DatabaseUpdater.describeDumpInfoFormatCheck(foreignFile(), true);
        assertNotNull(described);
        assertTrue("the answer says the check was overridden, not that it passed", //$NON-NLS-1$
            described.contains("OVERRIDDEN")); //$NON-NLS-1$
        assertTrue(described.contains("2.20")); //$NON-NLS-1$
        assertTrue(described.contains("2.7")); //$NON-NLS-1$
    }

    /**
     * A probe REPORTS the mismatch and stops nothing: exactly one read of the environment, the
     * mismatch in the answer, no check, no update.
     */
    @Test
    public void aProbeReportsTheMismatchAndStopsNothing()
    {
        RecordingApplications manager =
            new RecordingApplications(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        IApplication application = new StubApplication("app-1"); //$NON-NLS-1$

        String answer = DatabaseUpdater.whatAnUpdateWouldFace(manager, application, null,
            "app-1", "proj", false, "proj", foreignFile(), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("a probe reads exactly one thing off the environment", //$NON-NLS-1$
            List.of("getUpdateState"), manager.calls); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(json.get("dumpInfoFormatCheck").getAsString().contains("2.20")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.get("dumpInfoFormatCheck").getAsString().contains("2.7")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Nothing to compare means nothing to stop: no expectation for the platform, or no stored file
     * at all, passes the gate - and the answer says the check was not made rather than claiming it.
     */
    @Test
    public void noExpectationOrNoFileMeansNoGate()
    {
        assertNull("no expectation -> no gate", DatabaseUpdater.stopOnForeignDumpInfoFormat( //$NON-NLS-1$
            DumpInfoProbe.reading("file", "2.20", null, "9.9.9.1"), false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("no file -> no gate", DatabaseUpdater.stopOnForeignDumpInfoFormat( //$NON-NLS-1$
            DumpInfoProbe.reading("file", null, "2.7", "8.3.27.1"), false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("matching formats -> no gate", DatabaseUpdater.stopOnForeignDumpInfoFormat( //$NON-NLS-1$
            DumpInfoProbe.reading("file", "2.7", "2.7", "8.3.27.1"), false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull("no reading at all -> no gate", //$NON-NLS-1$
            DatabaseUpdater.stopOnForeignDumpInfoFormat(null, false)); //$NON-NLS-1$

        String notCompared = DatabaseUpdater.describeDumpInfoFormatCheck(
            DumpInfoProbe.reading("file", "2.20", null, "9.9.9.1"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("the answer says the check was not made", notCompared.contains("not compared")); //$NON-NLS-1$ //$NON-NLS-2$
        String matched = DatabaseUpdater.describeDumpInfoFormatCheck(
            DumpInfoProbe.reading("file", "2.7", "2.7", "8.3.27.1"), false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(matched.contains("matched")); //$NON-NLS-1$
    }

    // ---- the format probe itself -------------------------------------------------------------

    /**
     * The version attribute comes off the file head, either side of the comparison: the EDT-written
     * 2.20 and the platform's 2.7 both read, and a file without the root attribute answers null
     * rather than a guess.
     */
    @Test
    public void theFormatComesOffTheFileHead()
        throws IOException
    {
        Path file = Files.createTempFile("dump-info", ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            Files.write(file, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
                + "<ConfigDumpInfo version=\"2.20\"><Metadata name=\"A\"/></ConfigDumpInfo>") //$NON-NLS-1$
                .getBytes(StandardCharsets.UTF_8));
            assertEquals("2.20", DumpInfoProbe.formatOf(file)); //$NON-NLS-1$
            assertEquals(1, DumpInfoProbe.recordsIn(file));

            Files.write(file, "<ConfigDumpInfo version=\"2.7\"/>" //$NON-NLS-1$
                .getBytes(StandardCharsets.UTF_8));
            assertEquals("2.7", DumpInfoProbe.formatOf(file)); //$NON-NLS-1$
            assertEquals(0, DumpInfoProbe.recordsIn(file));

            Files.write(file, "<Configuration/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            assertNull(DumpInfoProbe.formatOf(file));
        }
        finally
        {
            Files.deleteIfExists(file);
        }
        assertNull("a missing file is no format at all", DumpInfoProbe.formatOf( //$NON-NLS-1$
            Files.createTempFile("gone", "x").resolveSibling("not-there.xml"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The expectation is measured table first and remembered after: 8.3.27 is known to write 2.7,
     * a rebuild's record makes an unknown platform known, and the build number is not part of the
     * key.
     */
    @Test
    public void theExpectationIsMeasuredThenRemembered()
        throws IOException
    {
        assertEquals("8.3.27", DumpInfoProbe.withoutBuild("8.3.27.2214")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("8.3.27", DumpInfoProbe.withoutBuild("8.3.27")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("2.7", DumpInfoProbe.expectedFormat("8.3.27.2214", null)); //$NON-NLS-1$ //$NON-NLS-2$

        Path pairs = Files.createTempFile("dump-info-formats", ".properties"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            assertNull("an unknown platform has no expectation", //$NON-NLS-1$
                DumpInfoProbe.expectedFormat("8.3.24.100", pairs)); //$NON-NLS-1$
            DumpInfoProbe.rememberPair("8.3.24.100", "2.5", pairs); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("a rebuild's record is the expectation afterwards", "2.5", //$NON-NLS-1$
                DumpInfoProbe.expectedFormat("8.3.24", pairs)); //$NON-NLS-1$
        }
        finally
        {
            Files.deleteIfExists(pairs);
        }
    }

    /**
     * An application manager that records every call it receives. The recording is the point: the
     * assertions are over the whole list, so a gate that reaches for one more read of the
     * environment fails on the list rather than passing quietly.
     */
    private static final class RecordingApplications implements IApplicationManager
    {
        final List<String> calls = new ArrayList<>();

        private final ApplicationUpdateState state;

        RecordingApplications(ApplicationUpdateState state)
        {
            this.state = state;
        }

        @Override
        public ApplicationUpdateState getUpdateState(IApplication application)
        {
            calls.add("getUpdateState"); //$NON-NLS-1$
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
     * An application with an id and nothing else, as in the probe tests.
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
}
