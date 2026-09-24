/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.core.infobases.sync.v2.IInfobaseSynchronizationStateManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.SyncBaseline;

/**
 * A project bound to two infobases: one it has a baseline for and one it was just registered
 * against. {@code status} answers for each binding separately and does not promise an incremental
 * update while one of them carries no baseline, nor while the bindings could not be read at all;
 * {@code mark_synchronized} accepts the fresh binding, creates its baseline and stamps the
 * project's configuration id on it - and refuses the call when that stamp fails, because the
 * baseline it leaves behind records no configuration and the next update would be a full reload.
 *
 * <p>The two platform readings the tool makes - the applications of the project and the
 * synchronization delegate it calls - are answered by this test through the tool's package-private
 * seams, because neither exists outside a running EDT. The delegate writes the baseline file the
 * way EDT's own writer does, to the path the production code resolves, so what these tests assert
 * about the state on disk is read back through the production reader. The store, the baseline
 * layout, the prediction and the refusal wording are the production code.</p>
 */
public class TheFreshBindingIsReportedAndMarkedTest
{
    private static final String PROJECT = "AiEdtFreshBindingProbe"; //$NON-NLS-1$

    // Fresh ids: the machine may hold a roaming store with real baselines, and a configuration id
    // shared with one of them would make the prediction incremental before our store is consulted.
    private static final String CONFIGURATION = UUID.randomUUID().toString();

    private static final String WITH_BASELINE = UUID.randomUUID().toString();

    private static final String FRESH = UUID.randomUUID().toString();

    // One infobase per mark test: each of them ends up carrying a baseline that matches this
    // project, and a shared id would let the tests change each other's starting state.
    private static final String FRESH_MARK = UUID.randomUUID().toString();

    private static final String FRESH_STAMP_FAIL = UUID.randomUUID().toString();

    private static final String NOT_OURS = UUID.randomUUID().toString();

    private static Path root;

    private static IProject project;

    @BeforeClass
    public static void aProjectBoundToTwoInfobasesOneOfThemWithoutABaseline() throws Exception
    {
        root = Files.createTempDirectory("aiedt-fresh-binding"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path configuration = projectDir.resolve("src/Configuration"); //$NON-NLS-1$
        Files.createDirectories(configuration);
        Files.write(configuration.resolve("Configuration.mdo"), //$NON-NLS-1$
            ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mdclass:Configuration uuid=\"" + CONFIGURATION //$NON-NLS-1$
                + "\">\n</mdclass:Configuration>\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());

        Path store = project.getWorkingLocation("com._1c.g5.v8.dt.platform.services.core").toFile().toPath() //$NON-NLS-1$
            .resolve("ib-sync").resolve("ss").resolve(WITH_BASELINE); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(store);
        writeIndex(store.resolve("index.idx"), CONFIGURATION); //$NON-NLS-1$
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /**
     * One binding carries a baseline matching the project, the other has none: the per-binding
     * answers differ, and the shared prediction is the pessimistic one - a caller reading
     * INCREMENTAL would update the fresh binding and wait for a full configuration to load.
     */
    @Test
    public void statusAnswersForEachBindingAndKeepsThePessimisticPrediction() throws Exception
    {
        List<String> asked = new ArrayList<>();
        SyncControlTool tool = new ProbeTool(applicationsOf(asked, WITH_BASELINE, FRESH), new RecordingDelegate());
        JsonObject status = JsonParser.parseString(tool.execute(Map.of("operation", "status", "projectName", PROJECT))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .getAsJsonObject();

        assertEquals(status.toString(), List.of(PROJECT), asked);
        assertTrue(status.toString(), status.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(status.toString(), "FULL", status.get("prediction").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(status.toString(), status.get("willTriggerFullReload").getAsBoolean()); //$NON-NLS-1$
        assertEquals(WITH_BASELINE, status.getAsJsonObject("matchedBaseline").get("infobaseUuid").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject bound = bindingOf(status.getAsJsonArray("bindings"), WITH_BASELINE); //$NON-NLS-1$
        assertTrue(bound.toString(), bound.get("hasBaseline").getAsBoolean()); //$NON-NLS-1$
        assertTrue(bound.toString(), bound.get("matchesProject").getAsBoolean()); //$NON-NLS-1$
        assertEquals(bound.toString(), "INCREMENTAL", bound.get("prediction").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject fresh = bindingOf(status.getAsJsonArray("bindings"), FRESH); //$NON-NLS-1$
        assertFalse(fresh.toString(), fresh.get("hasBaseline").getAsBoolean()); //$NON-NLS-1$
        assertFalse(fresh.toString(), fresh.get("matchesProject").getAsBoolean()); //$NON-NLS-1$
        assertEquals(fresh.toString(), "FULL", fresh.get("prediction").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        String summary = status.get("summary").getAsString(); //$NON-NLS-1$
        assertTrue(summary, summary.contains(FRESH));
        assertTrue(summary, summary.contains(WITH_BASELINE));
    }

    /**
     * The operation the brief is about: the binding has no baseline, so it is not refused - the
     * delegate is called, and because the fresh store reads back an undefined state, the
     * project's configuration id is stamped afterwards, in that order. The stamp is checked where
     * it counts: in the baseline file the next update reads, not in the call having returned.
     */
    @Test
    public void aFreshBindingIsMarkedAndItsConfigurationIdStamped() throws Exception
    {
        RecordingDelegate delegate = new RecordingDelegate();
        SyncControlTool tool = new ProbeTool(applicationsOf(FRESH_MARK), delegate);
        JsonObject marked = JsonParser.parseString(tool.execute(Map.of("operation", "mark_synchronized", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "infobaseUuid", FRESH_MARK, "confirm", "true"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertTrue(marked.toString(), marked.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(marked.toString(), marked.get("baselineExisted").getAsBoolean()); //$NON-NLS-1$
        assertTrue(marked.toString(), marked.get("configurationUuidStamped").getAsBoolean()); //$NON-NLS-1$
        assertEquals(
            List.of("forceEdtSynchronization", "forceConfigurationUUID"), delegate.calls); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(FRESH_MARK, delegate.stampedInfobase.getUuid().toString());
        assertEquals(CONFIGURATION, delegate.stampedUuid.toString());
        assertEquals(CONFIGURATION, recordedConfigurationUuid(FRESH_MARK));
    }

    /**
     * A stamp that fails leaves a baseline with no configuration id - the state the stamp exists
     * to prevent, because the next update reads it as a different configuration and answers with a
     * full reload. The call is therefore refused with what the baseline now records, and the
     * refusal names the repair; a second mark_synchronized stamps that same baseline instead of
     * being turned down as a foreign configuration.
     */
    @Test
    public void aFailedStampIsRefusedAndTheSecondMarkStampsIt() throws Exception
    {
        RecordingDelegate delegate = new RecordingDelegate();
        delegate.failStamp = true;
        SyncControlTool tool = new ProbeTool(applicationsOf(FRESH_STAMP_FAIL), delegate);
        JsonObject refused = JsonParser.parseString(tool.execute(Map.of("operation", "mark_synchronized", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "infobaseUuid", FRESH_STAMP_FAIL, "confirm", "true"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertFalse(refused.toString(), refused.get("success").getAsBoolean()); //$NON-NLS-1$
        String refusal = refused.toString();
        assertTrue(refusal, refusal.contains("no configuration id")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("FULL reload")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("reseed_baseline")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains(FRESH_STAMP_FAIL));
        assertFalse(refusal, refused.get("configurationUuidStamped").getAsBoolean()); //$NON-NLS-1$
        assertEquals(refusal, 2, refused.get("newSignatureCount").getAsInt()); //$NON-NLS-1$
        assertEquals("", recordedConfigurationUuid(FRESH_STAMP_FAIL)); //$NON-NLS-1$

        delegate.failStamp = false;
        JsonObject marked = JsonParser.parseString(tool.execute(Map.of("operation", "mark_synchronized", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "infobaseUuid", FRESH_STAMP_FAIL, "confirm", "true"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertTrue(marked.toString(), marked.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(marked.toString(), marked.get("baselineExisted").getAsBoolean()); //$NON-NLS-1$
        assertTrue(marked.toString(), marked.get("configurationUuidStamped").getAsBoolean()); //$NON-NLS-1$
        assertEquals(marked.toString(), 2, marked.get("previousSignatureCount").getAsInt()); //$NON-NLS-1$
        assertEquals(CONFIGURATION, recordedConfigurationUuid(FRESH_STAMP_FAIL));
        assertEquals(List.of("forceEdtSynchronization", "forceConfigurationUUID", //$NON-NLS-1$ //$NON-NLS-2$
            "forceEdtSynchronization", "forceConfigurationUUID"), delegate.calls); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Without the application list nothing says which binding a matching baseline belongs to, so
     * the store scan alone cannot promise an incremental update: the answer is UNKNOWN and names
     * the reading that is missing, rather than reporting INCREMENTAL over a binding nobody looked
     * at.
     */
    @Test
    public void statusDoesNotPromiseIncrementalWhileTheApplicationsAreNotRead() throws Exception
    {
        SyncControlTool tool = new ProbeTool(null, new RecordingDelegate());
        JsonObject status = JsonParser.parseString(tool.execute(Map.of("operation", "status", "projectName", PROJECT))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .getAsJsonObject();

        assertTrue(status.toString(), status.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(status.toString(), status.has("bindingsUnavailable")); //$NON-NLS-1$
        assertFalse(status.toString(), status.has("bindings")); //$NON-NLS-1$
        assertEquals(status.toString(), WITH_BASELINE, //$NON-NLS-1$
            status.getAsJsonObject("matchedBaseline").get("infobaseUuid").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(status.toString(), "UNKNOWN", status.get("prediction").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(status.toString(), status.get("willTriggerFullReload").getAsBoolean()); //$NON-NLS-1$
        String summary = status.get("summary").getAsString(); //$NON-NLS-1$
        assertTrue(summary, summary.contains("were not read")); //$NON-NLS-1$
    }

    /**
     * An infobase this project is not bound to cannot be marked - there is no binding to stamp -
     * and the refusal names the bindings the project does have instead of sending the caller to
     * the operation that never lists it.
     */
    @Test
    public void anInfobaseThatIsNotAnApplicationIsRefusedWithTheListOfApplications() throws Exception
    {
        RecordingDelegate delegate = new RecordingDelegate();
        SyncControlTool tool = new ProbeTool(applicationsOf(WITH_BASELINE), delegate);
        JsonObject refused = JsonParser.parseString(tool.execute(Map.of("operation", "mark_synchronized", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "infobaseUuid", NOT_OURS, "confirm", "true"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        assertFalse(refused.toString(), refused.get("success").getAsBoolean()); //$NON-NLS-1$
        String message = refused.toString();
        assertTrue(message, message.contains("not one of the project's applications")); //$NON-NLS-1$
        assertTrue(message, message.contains(WITH_BASELINE));
        assertTrue(delegate.calls.toString(), delegate.calls.isEmpty());
    }

    private static JsonObject bindingOf(JsonArray bindings, String infobaseUuid)
    {
        for (int i = 0; i < bindings.size(); i++)
        {
            JsonObject entry = bindings.get(i).getAsJsonObject();
            if (infobaseUuid.equals(entry.get("infobaseUuid").getAsString())) //$NON-NLS-1$
            {
                return entry;
            }
        }
        throw new AssertionError("the binding is not listed: " + bindings); //$NON-NLS-1$
    }

    /**
     * The baseline path the production code resolves for an infobase - the same call the tool
     * makes, so the file this test reads back is the one the tool read.
     */
    private static Path indexOf(String infobaseUuid)
    {
        return SyncBaseline.indexOf(project, infobaseUuid);
    }

    /**
     * The configuration id recorded in a binding's baseline, read with the production reader.
     */
    private static String recordedConfigurationUuid(String infobaseUuid) throws Exception
    {
        return SyncBaseline.read(indexOf(infobaseUuid)).configurationUuid;
    }

    /**
     * Writes the index in the layout EDT 2026 writes, carrying the configuration id given - an
     * empty one is what EDT's own writer records for a store that held nothing.
     */
    private static void writeIndex(Path file, String configurationUuid) throws Exception
    {
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file.toFile())))
        {
            out.writeUTF("1.0"); //$NON-NLS-1$
            out.writeLong(System.currentTimeMillis());
            out.writeInt(2);
            out.writeUTF("src/CommonModules/Probe/Module.bsl"); //$NON-NLS-1$
            out.writeInt(2);
            out.write(new byte[] { 1, 2 });
            out.writeBoolean(false);
            out.writeUTF("src/Catalogs/Products/Forms/ItemForm/Form.oform"); //$NON-NLS-1$
            out.writeInt(2);
            out.write(new byte[] { 3, 4 });
            out.writeBoolean(false);
            out.writeUTF("generation-1"); //$NON-NLS-1$
            out.writeUTF(configurationUuid);
        }
    }

    /**
     * An application manager that answers per project: the infobases given are the applications of
     * {@link #PROJECT}, any other project has none. Which projects were asked is recorded, so a
     * regression that reads the applications of the wrong project - or that takes the extension
     * fallback for a configuration project - fails here instead of passing on the same list.
     */
    private static IApplicationManager applicationsOf(String... infobaseUuids)
    {
        return applicationsOf(new ArrayList<>(), infobaseUuids);
    }

    /**
     * The same manager, reporting the projects it was asked about into {@code asked}.
     *
     * @param asked collects the name of every project the tool resolved applications for
     * @param infobaseUuids the applications of {@link #PROJECT}
     * @return the manager
     */
    private static IApplicationManager applicationsOf(List<String> asked, String... infobaseUuids)
    {
        List<String> uuids = List.of(infobaseUuids);
        return (IApplicationManager)Proxy.newProxyInstance(loader(), new Class<?>[] { IApplicationManager.class },
            (proxy, method, args) ->
            {
                if ("getApplications".equals(method.getName())) //$NON-NLS-1$
                {
                    String askedProject = ((IProject)args[0]).getName();
                    asked.add(askedProject);
                    if (!PROJECT.equals(askedProject))
                    {
                        return new ArrayList<IApplication>();
                    }
                    List<IApplication> applications = new ArrayList<>();
                    for (String uuid : uuids)
                    {
                        applications.add(application(uuid));
                    }
                    return applications;
                }
                if ("getDefaultApplication".equals(method.getName())) //$NON-NLS-1$
                {
                    return Optional.empty();
                }
                return nothing(method, proxy, args);
            });
    }

    /**
     * An infobase application over a real {@code InfobaseReference}, so the id the tool reads is
     * the one this test named.
     */
    private static IApplication application(String infobaseUuid)
    {
        InfobaseReference infobase = ModelFactory.eINSTANCE.createInfobaseReference();
        infobase.setUuid(UUID.fromString(infobaseUuid));
        infobase.setName("Probe " + infobaseUuid.substring(0, 8)); //$NON-NLS-1$
        return (IApplication)Proxy.newProxyInstance(loader(), new Class<?>[] { IInfobaseApplication.class },
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getInfobase": //$NON-NLS-1$
                        return infobase;
                    case "getId": //$NON-NLS-1$
                        return "app-" + infobaseUuid.substring(0, 8); //$NON-NLS-1$
                    case "getName": //$NON-NLS-1$
                        return infobase.getName();
                    default:
                        return nothing(method, proxy, args);
                }
            });
    }

    /**
     * The manager the tool reaches its delegate through. This test answers the delegate itself, so
     * the proxy only has to exist.
     */
    private static IInfobaseSynchronizationStateManager stateManager()
    {
        return (IInfobaseSynchronizationStateManager)Proxy.newProxyInstance(loader(),
            new Class<?>[] { IInfobaseSynchronizationStateManager.class },
            (proxy, method, args) -> nothing(method, proxy, args));
    }

    private static Object nothing(Method method, Object proxy, Object[] args)
    {
        switch (method.getName())
        {
            case "toString": //$NON-NLS-1$
                return "probe"; //$NON-NLS-1$
            case "hashCode": //$NON-NLS-1$
                return Integer.valueOf(System.identityHashCode(proxy));
            case "equals": //$NON-NLS-1$
                return Boolean.valueOf(proxy == args[0]);
            default:
                break;
        }
        Class<?> type = method.getReturnType();
        if (type == boolean.class)
        {
            return Boolean.FALSE;
        }
        if (type == int.class)
        {
            return Integer.valueOf(0);
        }
        if (type == long.class)
        {
            return Long.valueOf(0L);
        }
        return null;
    }

    private static ClassLoader loader()
    {
        return TheFreshBindingIsReportedAndMarkedTest.class.getClassLoader();
    }

    /**
     * The delegate behind the sync state manager, recording what the tool calls and with what. It
     * writes the baseline the way EDT's writer does: {@code forceEdtSynchronization} writes one
     * for the current state, which carries no configuration id when the store held nothing, and
     * {@code forceConfigurationUUID} rewrites that id - or, with {@link #failStamp}, fails without
     * touching the file, which is the state the stamp-failure rule is about. The tool reads these
     * bytes back, so this is what makes the outcome assertable on disk.
     */
    public static final class RecordingDelegate
    {
        final List<String> calls = new ArrayList<>();

        UUID stampedUuid;

        InfobaseReference stampedInfobase;

        /** When true, the stamp call fails instead of writing the id. */
        boolean failStamp;

        public void forceEdtSynchronization(InfobaseReference infobase, IProject project) throws Exception
        {
            calls.add("forceEdtSynchronization"); //$NON-NLS-1$
            writeIndex(indexOf(infobase.getUuid().toString()), ""); //$NON-NLS-1$
        }

        public void forceConfigurationUUID(InfobaseReference infobase, IProject project, UUID uuid) throws Exception
        {
            calls.add("forceConfigurationUUID"); //$NON-NLS-1$
            if (failStamp)
            {
                throw new IllegalStateException("the stand-in refuses to stamp"); //$NON-NLS-1$
            }
            stampedInfobase = infobase;
            stampedUuid = uuid;
            writeIndex(indexOf(infobase.getUuid().toString()), uuid.toString());
        }
    }

    /**
     * The tool with its two platform readings replaced. Everything the test asserts on - the
     * prediction, the per-binding answers, the refusal, the order of the delegate calls - is the
     * production path.
     */
    private static final class ProbeTool extends SyncControlTool
    {
        private final IApplicationManager applications;

        private final IInfobaseSynchronizationStateManager states;

        private final RecordingDelegate delegate;

        ProbeTool(IApplicationManager applications, RecordingDelegate delegate)
        {
            this.applications = applications;
            this.delegate = delegate;
            this.states = stateManager();
        }

        @Override
        IApplicationManager applicationManager()
        {
            return applications;
        }

        @Override
        IInfobaseSynchronizationStateManager syncStateManager()
        {
            return states;
        }

        @Override
        Object syncDelegate(IInfobaseSynchronizationStateManager stateMgr)
        {
            return delegate;
        }
    }
}
