/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IImportAsNewProjectFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateInfobaseFlow;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IUpdateProjectFlow;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ComponentExecutorInfo;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.ILaunchableRuntimeComponent;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IThickClientLauncher;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationArtifact;
import com.e1c.g5.dt.applications.IApplicationType;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.ops.DatabaseUpdater.SyncFlowReading;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * An update that finds a synchronization flow already running against its infobase is refused
 * before it starts, and the refusal is a measurement rather than a reading of the source: the
 * gate takes what follows it as a supplier, so a test hands in its own recording stand-in and
 * asks whether it was called.
 *
 * <p>The production call site passes a supplier answering {@code null}, because there the code
 * after the gate IS the path that frees clients and hands the update to the platform. The gate
 * stands under the claim the update took, so a refusal returns from inside the same try whose
 * finally releases that claim - the mechanism every mid-flight refusal on this path already
 * uses.</p>
 *
 * <p>The probe is measured the same way: a stand-in state manager records the infobase it was
 * asked about, and answers what the test told it to. A manager that is missing, or one whose
 * call throws, produces a reading that says "not checked" - and the update runs, with the
 * answer saying the guard did not run rather than staying silent.</p>
 */
public class ASyncFlowRefusalStartsNothingTest
{
    /** A stand-in for what follows the gate, which records whether it ran. */
    private static final class Launch implements Supplier<String>
    {
        private final AtomicInteger runs = new AtomicInteger();

        @Override
        public String get()
        {
            this.runs.incrementAndGet();
            return "started"; //$NON-NLS-1$
        }

        boolean ran()
        {
            return this.runs.get() > 0;
        }
    }

    /** One JSON member of an answer. */
    private static JsonObject parse(String answer)
    {
        return JsonParser.parseString(answer).getAsJsonObject();
    }

    /**
     * The refusal: the platform's flag says a flow is active, and the update was never started.
     * The launch stands in for the whole of the rest of the call - the freed clients, the
     * platform update itself - so "it was not called" is the statement that nothing happened.
     */
    @Test
    public void aFlowActiveBeforeTheCallRefusesAndStartsNothing()
    {
        Launch launch = new Launch();

        String answer = DatabaseUpdater.passTheSyncFlowGate(
            SyncFlowReading.checked(true, "УчебнаяБаза"), "app-1", launch); //$NON-NLS-1$

        assertFalse("nothing that follows the gate ran, so nothing was started", launch.ran()); //$NON-NLS-1$
        JsonObject body = parse(answer);
        assertFalse("the refusal is an error, not a success carrying a warning", //$NON-NLS-1$
            body.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("busy", body.get("tag").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the refusal names the infobase it was asked about", //$NON-NLS-1$
            "УчебнаяБаза", body.get("infobaseName").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("app-1", body.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the caller must be able to tell this from an update that ran and failed", //$NON-NLS-1$
            body.get("nothingStarted").getAsBoolean()); //$NON-NLS-1$
        assertTrue("the next step is a retry, not a reconfiguration", //$NON-NLS-1$
            body.get("nextStep").getAsString().contains("retry")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the sentence says what is running on the infobase", //$NON-NLS-1$
            body.get("error").getAsString().contains("synchronization flow is active")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A flag that says no flow is running stops nothing: the ordinary case, and a gate that
     * refused here would make every update unusable.
     */
    @Test
    public void noActiveFlowStartsTheUpdate()
    {
        Launch launch = new Launch();

        String answer = DatabaseUpdater.passTheSyncFlowGate(
            SyncFlowReading.checked(false, "УчебнаяБаза"), "app-1", launch); //$NON-NLS-1$

        assertTrue("no flow is running, so the update runs", launch.ran()); //$NON-NLS-1$
        assertEquals("started", answer); //$NON-NLS-1$
    }

    /**
     * A flag that could not be read stops nothing either, and the answer says so: "the guard did
     * not run" and "the guard found nothing" are different answers, and only the second one is a
     * reason to hold an update back.
     */
    @Test
    public void anUnreadableFlagStartsTheUpdateAndTheAnswerSaysSo()
    {
        Launch launch = new Launch();
        SyncFlowReading unreadable = SyncFlowReading
            .notChecked("the infobase synchronization state manager is unavailable on this EDT", //$NON-NLS-1$
                "УчебнаяБаза"); //$NON-NLS-1$

        String answer = DatabaseUpdater.passTheSyncFlowGate(unreadable, "app-1", launch); //$NON-NLS-1$

        assertTrue("a flag that could not be read refuses nothing", launch.ran()); //$NON-NLS-1$
        assertEquals("started", answer); //$NON-NLS-1$

        ToolResult ran = ToolResult.success();
        DatabaseUpdater.putSyncFlowCheck(ran, unreadable);
        JsonObject body = parse(ran.toJson());
        assertTrue("the answer says the guard did not run, naming why", //$NON-NLS-1$
            body.get("syncFlowCheck").getAsString().startsWith("not checked")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(body.get("syncFlowCheck").getAsString() //$NON-NLS-1$
            .contains("state manager is unavailable")); //$NON-NLS-1$
    }

    /** An answer of an update whose flag read clean carries no note about the guard. */
    @Test
    public void aReadFlagLeavesTheAnswerQuiet()
    {
        ToolResult ran = ToolResult.success();

        DatabaseUpdater.putSyncFlowCheck(ran, SyncFlowReading.checked(false, "УчебнаяБаза")); //$NON-NLS-1$

        assertFalse("a check that ran says nothing - there is nothing the caller must know", //$NON-NLS-1$
            parse(ran.toJson()).has("syncFlowCheck")); //$NON-NLS-1$
    }

    /**
     * The probe asks the platform's own manager, about the infobase of the application the update
     * targets, and answers what the manager answered. The manager records the reference it saw,
     * so "the right infobase" is a comparison of uuids rather than an opinion.
     */
    @Test
    public void theProbeAsksTheManagerAboutTheNamedInfobase()
    {
        UUID uuid = UUID.randomUUID();
        InfobaseReference ref = ModelFactory.eINSTANCE.createInfobaseReference();
        ref.setUuid(uuid);
        ref.setName("УчебнаяБаза"); //$NON-NLS-1$
        RecordingManager manager = new RecordingManager(true);
        IInfobaseApplication application = new StubInfobaseApplication("app-1", ref); //$NON-NLS-1$

        SyncFlowReading reading = DatabaseUpdater.probeSyncFlowActive(() -> manager, application);

        assertSame("the manager was asked exactly once", manager.lastReference, ref); //$NON-NLS-1$
        assertEquals(uuid, manager.lastReference.getUuid());
        assertEquals("the reading carries the flag the manager answered", //$NON-NLS-1$
            Boolean.TRUE, reading.active);
        assertNull(reading.notCheckedWhy);
        assertEquals("and the infobase's own name, which the refusal names the base by", //$NON-NLS-1$
            "УчебнаяБаза", reading.infobaseName); //$NON-NLS-1$
    }

    /** A manager that is not there means the flag was not read, and the update still runs. */
    @Test
    public void noManagerMeansNotChecked()
    {
        IInfobaseApplication application = new StubInfobaseApplication("app-1", //$NON-NLS-1$
            ModelFactory.eINSTANCE.createInfobaseReference());
        application.getInfobase().setUuid(UUID.randomUUID());
        application.getInfobase().setName("УчебнаяБаза"); //$NON-NLS-1$

        SyncFlowReading reading = DatabaseUpdater.probeSyncFlowActive(() -> null, application);

        assertNull("no manager, no answer", reading.active); //$NON-NLS-1$
        assertTrue("the reason names the missing manager", //$NON-NLS-1$
            reading.notCheckedWhy.contains("state manager is unavailable")); //$NON-NLS-1$
        Launch launch = new Launch();
        assertEquals("started", //$NON-NLS-1$
            DatabaseUpdater.passTheSyncFlowGate(reading, "app-1", launch)); //$NON-NLS-1$
        assertTrue(launch.ran());
    }

    /** A manager that cannot even be resolved means not checked too, and the update still runs. */
    @Test
    public void aManagerThatCannotBeResolvedMeansNotChecked()
    {
        IInfobaseApplication application = new StubInfobaseApplication("app-1", //$NON-NLS-1$
            ModelFactory.eINSTANCE.createInfobaseReference());
        application.getInfobase().setUuid(UUID.randomUUID());
        application.getInfobase().setName("УчебнаяБаза"); //$NON-NLS-1$

        SyncFlowReading reading = DatabaseUpdater.probeSyncFlowActive(() -> {
            throw new IllegalStateException("no service"); //$NON-NLS-1$
        }, application);

        assertNull("an unresolved manager gives no answer", reading.active); //$NON-NLS-1$
        assertTrue(reading.notCheckedWhy, reading.notCheckedWhy.contains("no service")); //$NON-NLS-1$
        Launch launch = new Launch();
        assertEquals("started", //$NON-NLS-1$
            DatabaseUpdater.passTheSyncFlowGate(reading, "app-1", launch)); //$NON-NLS-1$
        assertTrue(launch.ran());
    }

    /** A manager whose call throws means the same: not checked, and the update still runs. */
    @Test
    public void aManagerThatRefusesMeansNotChecked()
    {
        UUID uuid = UUID.randomUUID();
        InfobaseReference ref = ModelFactory.eINSTANCE.createInfobaseReference();
        ref.setUuid(uuid);
        RecordingManager manager = new RecordingManager(true);
        manager.throwOnIsFlowActive = new IllegalStateException("flag file unmapped"); //$NON-NLS-1$

        SyncFlowReading reading = DatabaseUpdater.probeSyncFlowActive(() -> manager,
            new StubInfobaseApplication("app-1", ref)); //$NON-NLS-1$

        assertNull(reading.active);
        assertTrue("the reason names the failure the manager raised", //$NON-NLS-1$
            reading.notCheckedWhy.contains("flag file unmapped")); //$NON-NLS-1$
        Launch launch = new Launch();
        DatabaseUpdater.passTheSyncFlowGate(reading, "app-1", launch); //$NON-NLS-1$
        assertTrue("a read that failed refuses nothing", launch.ran()); //$NON-NLS-1$
    }

    /**
     * An application with no infobase reference cannot be asked about, and the reading says so
     * rather than guessing: the guard is only ever an answer the platform gave.
     */
    @Test
    public void anApplicationWithoutAnInfobaseIsNotChecked()
    {
        SyncFlowReading reading = DatabaseUpdater.probeSyncFlowActive(() -> new RecordingManager(false),
            new StubApplication("app-1")); //$NON-NLS-1$

        assertNull(reading.active);
        assertTrue(reading.notCheckedWhy.contains("no infobase reference")); //$NON-NLS-1$
    }

    /** A reference with no uuid is as good as none: the manager keys its state by uuid. */
    @Test
    public void aReferenceWithoutAUuidIsNotChecked()
    {
        SyncFlowReading reading = DatabaseUpdater.probeSyncFlowActive(() -> new RecordingManager(false),
            new StubInfobaseApplication("app-1", //$NON-NLS-1$
                ModelFactory.eINSTANCE.createInfobaseReference()));

        assertNull(reading.active);
        assertTrue(reading.notCheckedWhy.contains("no infobase reference")); //$NON-NLS-1$
    }

    /** The state manager as the production code sees it: every call recorded, one answerable. */
    private static final class RecordingManager implements IInfobaseSynchronizationStateManager
    {
        final boolean flowActive;

        volatile RuntimeException throwOnIsFlowActive;

        InfobaseReference lastReference;

        RecordingManager(boolean flowActive)
        {
            this.flowActive = flowActive;
        }

        @Override
        public boolean isFlowActive(InfobaseReference reference)
        {
            this.lastReference = reference;
            if (this.throwOnIsFlowActive != null)
            {
                throw this.throwOnIsFlowActive;
            }
            return this.flowActive;
        }

        @Override
        public IUpdateInfobaseFlow startUpdateInfobaseFlow(IDtProject project, InfobaseReference reference)
        {
            throw new UnsupportedOperationException("starting a flow is not this stand-in's job"); //$NON-NLS-1$
        }

        @Override
        public IImportAsNewProjectFlow startImportAsNewProjectFlow(IProject project,
            InfobaseReference reference,
            ComponentExecutorInfo<ILaunchableRuntimeComponent, IThickClientLauncher> executor)
        {
            throw new UnsupportedOperationException("starting a flow is not this stand-in's job"); //$NON-NLS-1$
        }

        @Override
        public IUpdateProjectFlow startUpdateProjectFlow(IDtProject project, InfobaseReference reference)
        {
            throw new UnsupportedOperationException("starting a flow is not this stand-in's job"); //$NON-NLS-1$
        }

        @Override
        public boolean isProjectDirty(IDtProject project, InfobaseReference reference)
        {
            return false;
        }

        @Override
        public boolean isProjectDirty(IProject project, InfobaseReference reference)
        {
            return false;
        }

        @Override
        public boolean hasSynchronizationInfo(IProject project, InfobaseReference reference)
        {
            return false;
        }

        @Override
        public void generateIBSyncState(java.nio.file.Path first, java.nio.file.Path second,
            String name, UUID uuid, java.nio.file.Path third)
        {
            // no state to generate
        }

        @Override
        public void compareIBSyncStates(java.nio.file.Path first, java.nio.file.Path second)
        {
            // no state to compare
        }
    }

    /** An application bound to an infobase reference, with an id and nothing else. */
    private static final class StubInfobaseApplication implements IInfobaseApplication
    {
        private final String id;

        private final InfobaseReference infobase;

        StubInfobaseApplication(String id, InfobaseReference infobase)
        {
            this.id = id;
            this.infobase = infobase;
        }

        @Override
        public InfobaseReference getInfobase()
        {
            return this.infobase;
        }

        @Override
        public String getId()
        {
            return this.id;
        }

        @Override
        public String getName()
        {
            return this.id;
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
        public Optional<URL> getUrl() throws ApplicationException
        {
            return Optional.empty();
        }

        @Override
        public <T> T getAdapter(Class<T> adapter)
        {
            return null;
        }
    }

    /** An application with an id and no infobase: the kind a server application is. */
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
            return this.id;
        }

        @Override
        public String getName()
        {
            return this.id;
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
