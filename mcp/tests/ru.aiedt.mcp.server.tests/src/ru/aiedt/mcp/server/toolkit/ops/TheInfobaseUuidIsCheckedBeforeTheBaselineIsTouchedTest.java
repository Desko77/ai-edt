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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.SyncBaseline;

/**
 * The infobase id a caller hands to {@code sync_control} is checked before the baseline store is
 * touched by it. An id that is not a canonical UUID - a path, a {@code ..} segment, a
 * non-canonical or differently-cased spelling - is refused before any path is built from it, in
 * {@code diagnose_delta} (which only reads) as much as in the writers. And the writers
 * ({@code reseed_baseline}, {@code mark_synchronized}) refuse an infobase the project is not
 * bound to even when a baseline exists for it and records the configuration id of this project: a
 * matching id does not make a foreign infobase belong to this project, and rewriting its baseline
 * would rewrite the synchronization state of another workspace.
 *
 * <p>The store layout, the baselines and the refusal wording are the production code. The two
 * platform readings - the applications of the project and the sync delegate - are answered
 * through the package-private seams of the tool, because neither exists outside a running EDT.</p>
 */
public class TheInfobaseUuidIsCheckedBeforeTheBaselineIsTouchedTest
{
    private static final String PROJECT = "AiEdtUuidGuardProbe"; //$NON-NLS-1$

    // Fresh ids: the machine running the test may hold a roaming store with real baselines, and an
    // id shared with one of them would let a lookup land outside the store of this test.
    private static final String CONFIGURATION = UUID.randomUUID().toString();

    /** Not an application of the project; its baseline records the configuration id of the project. */
    private static final String FOREIGN_MATCH = UUID.randomUUID().toString();

    /** Not an application of the project; its baseline records a drifted configuration id. */
    private static final String FOREIGN_DRIFTED = UUID.randomUUID().toString();

    private static final String DRIFTED = UUID.randomUUID().toString();

    /** The one binding of the project, with no baseline at all. */
    private static final String BOUND = UUID.randomUUID().toString();

    private static Path root;

    private static IProject project;

    @BeforeClass
    public static void aProjectWithBaselinesOfInfobasesItIsNotBoundTo() throws Exception
    {
        root = Files.createTempDirectory("aiedt-uuid-guard"); //$NON-NLS-1$
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

        writeIndex(indexPathOf(FOREIGN_MATCH), CONFIGURATION);
        writeIndex(indexPathOf(FOREIGN_DRIFTED), DRIFTED);
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
     * diagnose_delta reads the baseline at a path built from the supplied id, so the id is checked
     * first: anything that is not a canonical UUID is refused before a path exists, and a canonical
     * one passes that check (it may still fail later - never with the form refusal).
     */
    @Test
    public void diagnoseDeltaRefusesANonUuidBeforeAnythingIsRead()
    {
        SyncControlTool tool = new ProbeTool(new RecordingDelegate());
        for (String bad : Arrays.asList("..", "../" + FOREIGN_MATCH, "C:/", "1-1-1-1-1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                FOREIGN_MATCH.toUpperCase(Locale.ROOT)))
        {
            JsonObject refused = JsonParser.parseString(tool.execute(Map.of("operation", "diagnose_delta", //$NON-NLS-1$ //$NON-NLS-2$
                "projectName", PROJECT, "infobaseUuid", bad))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(bad + " -> " + refused, refused.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(bad + " -> " + refused, //$NON-NLS-1$
                refused.toString().contains("infobaseUuid is not a valid UUID")); //$NON-NLS-1$
        }

        JsonObject canonical = JsonParser.parseString(tool.execute(Map.of("operation", "diagnose_delta", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "infobaseUuid", BOUND))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(canonical.toString(), canonical.toString().contains("not a valid UUID")); //$NON-NLS-1$
    }

    /**
     * reseed_baseline writes, so the infobase must be one of the applications of the project even
     * when a populated baseline exists for it: without the check the reseed lands on a baseline of
     * a project of another workspace. The refusal names the bindings the project has, and the
     * baseline on disk keeps its recorded configuration id.
     */
    @Test
    public void reseedRefusesAnInfobaseTheProjectIsNotBoundToAndLeavesItsBaseline() throws Exception
    {
        SyncControlTool tool = new ProbeTool(new RecordingDelegate());
        JsonObject refused = JsonParser.parseString(tool.execute(Map.of("operation", "reseed_baseline", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "infobaseUuid", FOREIGN_DRIFTED, "confirm", "true"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertFalse(refused.toString(), refused.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(refused.toString(), refused.toString().contains("not one of the project's applications")); //$NON-NLS-1$
        assertTrue(refused.toString(), refused.toString().contains(BOUND));
        assertEquals("the foreign baseline was not re-stamped", //$NON-NLS-1$
            DRIFTED, SyncBaseline.read(indexPathOf(FOREIGN_DRIFTED)).configurationUuid);
    }

    /**
     * The form check of reseed_baseline is strict too: a spelling UUID.fromString accepts but that
     * is not the canonical string never reaches the baseline store.
     */
    @Test
    public void reseedRefusesANonCanonicalUuid()
    {
        SyncControlTool tool = new ProbeTool(new RecordingDelegate());
        JsonObject refused = JsonParser.parseString(tool.execute(Map.of("operation", "reseed_baseline", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "infobaseUuid", "1-1-1-1-1", "confirm", "true"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertFalse(refused.toString(), refused.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(refused.toString(), refused.toString().contains("infobaseUuid is not a valid UUID")); //$NON-NLS-1$
    }

    /**
     * mark_synchronized checked the binding only when the baseline was missing or recorded no
     * configuration id, so a foreign infobase whose baseline happened to record the configuration
     * id of this project was re-signed. The binding is now checked on every mark: the delegate is
     * never called and the baseline on disk is untouched.
     */
    @Test
    public void markSynchronizedRefusesAnUnboundInfobaseWhoseBaselineMatchesTheProject() throws Exception
    {
        RecordingDelegate delegate = new RecordingDelegate();
        SyncControlTool tool = new ProbeTool(delegate);
        JsonObject refused = JsonParser.parseString(tool.execute(Map.of("operation", "mark_synchronized", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "infobaseUuid", FOREIGN_MATCH, "confirm", "true"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertFalse(refused.toString(), refused.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(refused.toString(), refused.toString().contains("not one of the project's applications")); //$NON-NLS-1$
        assertTrue(refused.toString(), refused.toString().contains(BOUND));
        assertTrue("the sync delegate was never called: " + delegate.calls, delegate.calls.isEmpty()); //$NON-NLS-1$
        assertEquals("the foreign baseline was not re-signed", //$NON-NLS-1$
            CONFIGURATION, SyncBaseline.read(indexPathOf(FOREIGN_MATCH)).configurationUuid);
    }

    /**
     * The baseline path of an infobase in the workspace store of this project, where this test
     * writes the fixtures and reads them back through the production reader.
     */
    private static Path indexPathOf(String infobaseUuid)
    {
        return SyncBaseline.workspaceStore(project).resolve(infobaseUuid).resolve(SyncBaseline.INDEX_FILE);
    }

    /**
     * Writes the index in the layout EDT 2026 writes, with two signatures and the configuration
     * id given.
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
     * The delegate behind the sync state manager, recording what the tool calls. A refusal before
     * any write is observable as this list staying empty.
     */
    public static final class RecordingDelegate
    {
        final List<String> calls = new ArrayList<>();

        public void forceEdtSynchronization(InfobaseReference infobase, IProject project) throws Exception
        {
            calls.add("forceEdtSynchronization"); //$NON-NLS-1$
            writeIndex(indexPathOf(infobase.getUuid().toString()), ""); //$NON-NLS-1$
        }

        public void forceConfigurationUUID(InfobaseReference infobase, IProject project, UUID uuid)
            throws Exception
        {
            calls.add("forceConfigurationUUID"); //$NON-NLS-1$
            writeIndex(indexPathOf(infobase.getUuid().toString()), uuid.toString());
        }
    }

    /**
     * The tool with its platform readings replaced: the project is bound to {@link #BOUND} alone,
     * and the sync delegate is the recording stand-in. Everything asserted - the form check, the
     * binding refusal, the state on disk - is the production path.
     */
    private static final class ProbeTool extends SyncControlTool
    {
        private final RecordingDelegate delegate;

        ProbeTool(RecordingDelegate delegate)
        {
            this.delegate = delegate;
        }

        @Override
        IApplicationManager applicationManager()
        {
            InfobaseReference infobase = ModelFactory.eINSTANCE.createInfobaseReference();
            infobase.setUuid(UUID.fromString(BOUND));
            infobase.setName("Probe " + BOUND.substring(0, 8)); //$NON-NLS-1$
            IApplication application = (IApplication)Proxy.newProxyInstance(loader(),
                new Class<?>[] { IInfobaseApplication.class },
                (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                        case "getInfobase": //$NON-NLS-1$
                            return infobase;
                        case "getId": //$NON-NLS-1$
                            return "app-" + BOUND.substring(0, 8); //$NON-NLS-1$
                        case "getName": //$NON-NLS-1$
                            return infobase.getName();
                        default:
                            return nothing(method, proxy, args);
                    }
                });
            return (IApplicationManager)Proxy.newProxyInstance(loader(),
                new Class<?>[] { IApplicationManager.class },
                (proxy, method, args) ->
                {
                    if ("getApplications".equals(method.getName())) //$NON-NLS-1$
                    {
                        return new ArrayList<>(List.of(application));
                    }
                    if ("getDefaultApplication".equals(method.getName())) //$NON-NLS-1$
                    {
                        return Optional.empty();
                    }
                    return nothing(method, proxy, args);
                });
        }

        @Override
        IInfobaseSynchronizationStateManager syncStateManager()
        {
            return (IInfobaseSynchronizationStateManager)Proxy.newProxyInstance(loader(),
                new Class<?>[] { IInfobaseSynchronizationStateManager.class },
                (proxy, method, args) -> nothing(method, proxy, args));
        }

        @Override
        Object syncDelegate(IInfobaseSynchronizationStateManager stateMgr)
        {
            return delegate;
        }
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
        return TheInfobaseUuidIsCheckedBeforeTheBaselineIsTouchedTest.class.getClassLoader();
    }
}
