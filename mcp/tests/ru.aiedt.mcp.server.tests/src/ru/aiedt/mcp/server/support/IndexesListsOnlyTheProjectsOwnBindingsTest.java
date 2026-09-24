/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.ModelFactory;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * {@link SyncBaseline#indexes} lists the baselines that belong to a project: every index in the
 * workspace store, and from the per-user store only an infobase the project is bound to.
 *
 * <p>Two indexes in the per-user store record the same configuration id. One is an application of
 * the project, the other is not. Selecting by that id would list both. Without an application
 * manager the per-user store is not read, and {@link SyncBaseline#perUserStoreOmission()} names
 * why.</p>
 */
public class IndexesListsOnlyTheProjectsOwnBindingsTest
{
    private static final String PROJECT = "AiEdtOwnBindingsProbe"; //$NON-NLS-1$

    private static final String CONFIGURATION = UUID.randomUUID().toString();

    private static final String WORKSPACE = UUID.randomUUID().toString();

    private static final String BOUND = UUID.randomUUID().toString();

    private static final String UNBOUND = UUID.randomUUID().toString();

    private static final String UNAVAILABLE =
        "the per-user sync store was not read: the application manager is unavailable"; //$NON-NLS-1$

    private static Path root;

    private static Path sharedStore;

    private static Path boundIndex;

    private static Path unboundIndex;

    private static IProject project;

    @BeforeClass
    public static void aProjectAWorkspaceIndexAndTwoPerUserIndexesOfOneConfiguration() throws Exception
    {
        root = Files.createTempDirectory("aiedt-own-bindings"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path configuration = projectDir.resolve("src/Configuration"); //$NON-NLS-1$
        Files.createDirectories(configuration);
        Files.writeString(configuration.resolve("Configuration.mdo"), //$NON-NLS-1$
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mdclass:Configuration uuid=\"" + CONFIGURATION //$NON-NLS-1$
                + "\">\n</mdclass:Configuration>\n", //$NON-NLS-1$
            StandardCharsets.UTF_8);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject existing = workspace.getRoot().getProject(PROJECT);
        if (existing.exists())
        {
            existing.delete(true, true, new NullProgressMonitor());
        }
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());

        writeIndex(SyncBaseline.workspaceStore(project).resolve(WORKSPACE).resolve(SyncBaseline.INDEX_FILE),
            CONFIGURATION);
        sharedStore = root.resolve("ss"); //$NON-NLS-1$
        boundIndex = sharedStore.resolve(BOUND).resolve(SyncBaseline.INDEX_FILE);
        unboundIndex = sharedStore.resolve(UNBOUND).resolve(SyncBaseline.INDEX_FILE);
        writeIndex(boundIndex, CONFIGURATION);
        writeIndex(unboundIndex, CONFIGURATION);
    }

    @AfterClass
    public static void theProjectAndTheStoreGo() throws Exception
    {
        SyncBaseline.sharedStoreForTests = null;
        SyncBaseline.applicationsForTests = null;
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

    @Before
    public void thePerUserStoreIsTheTemporaryOne()
    {
        SyncBaseline.sharedStoreForTests = sharedStore;
        SyncBaseline.applicationsForTests = null;
    }

    @After
    public void theSeamsGoBack()
    {
        SyncBaseline.sharedStoreForTests = null;
        SyncBaseline.applicationsForTests = null;
    }

    /**
     * The bound infobase is listed and the unbound one, recorded for the same configuration, is not.
     */
    @Test
    public void theBoundInfobaseIsListedAndTheUnboundOneOfTheSameConfigurationIsNot() throws Exception
    {
        assertEquals(CONFIGURATION, SyncBaseline.configurationUuid(project));
        assertEquals(CONFIGURATION, SyncBaseline.read(boundIndex).configurationUuid);
        assertEquals(CONFIGURATION, SyncBaseline.read(unboundIndex).configurationUuid);

        SyncBaseline.applicationsForTests = () -> managerBoundTo(BOUND);
        List<Path> found = SyncBaseline.indexes(project);

        assertEquals(found.toString(), Set.of(WORKSPACE, BOUND), infobaseIds(found));
        assertNull(found.toString(), SyncBaseline.perUserStoreOmission());
    }

    /**
     * No application manager: the per-user store is left unread and the omission names that.
     */
    @Test
    public void withoutAnApplicationManagerOnlyTheWorkspaceStoreIsListed()
    {
        SyncBaseline.applicationsForTests = () -> null;
        List<Path> found = SyncBaseline.indexes(project);

        assertEquals(found.toString(), Set.of(WORKSPACE), infobaseIds(found));
        assertEquals(found.toString(), UNAVAILABLE, SyncBaseline.perUserStoreOmission());
    }

    /**
     * An application manager that throws: the per-user store is left unread and the failure is named.
     */
    @Test
    public void whenTheApplicationManagerThrowsThePerUserStoreIsNotRead()
    {
        SyncBaseline.applicationsForTests = () -> {
            throw new IllegalStateException("no applications"); //$NON-NLS-1$
        };
        List<Path> found = SyncBaseline.indexes(project);

        assertEquals(found.toString(), Set.of(WORKSPACE), infobaseIds(found));
        assertEquals(found.toString(),
            "the per-user sync store was not read: the application manager failed (no applications)", //$NON-NLS-1$
            SyncBaseline.perUserStoreOmission());
    }

    private static Set<String> infobaseIds(List<Path> indexes)
    {
        Set<String> ids = new HashSet<>();
        for (Path index : indexes)
        {
            ids.add(index.getParent().getFileName().toString().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    private static IApplicationManager managerBoundTo(String infobaseUuid)
    {
        InfobaseReference infobase = ModelFactory.eINSTANCE.createInfobaseReference();
        infobase.setUuid(UUID.fromString(infobaseUuid));
        IInfobaseApplication application = (IInfobaseApplication)Proxy.newProxyInstance(
            IInfobaseApplication.class.getClassLoader(),
            new Class<?>[] { IInfobaseApplication.class },
            (proxy, method, args) -> "getInfobase".equals(method.getName()) //$NON-NLS-1$
                ? infobase : FakeLaunchConfigurations.defaultValue(method.getReturnType()));
        return (IApplicationManager)Proxy.newProxyInstance(
            IApplicationManager.class.getClassLoader(),
            new Class<?>[] { IApplicationManager.class },
            (proxy, method, args) -> "getApplications".equals(method.getName()) //$NON-NLS-1$
                ? List.of(application) : FakeLaunchConfigurations.defaultValue(method.getReturnType()));
    }

    private static void writeIndex(Path file, String configurationUuid) throws Exception
    {
        Files.createDirectories(file.getParent());
        SyncBaseline.Index index = new SyncBaseline.Index();
        index.versioned = true;
        index.version = "1.0"; //$NON-NLS-1$
        index.timestamp = 1L;
        index.keys.add("src/Catalogs/Banks/Banks.mdo"); //$NON-NLS-1$
        index.signatures.add(new byte[] { 9, 9 });
        index.resourceUuids.add(null);
        index.generationId = "generation"; //$NON-NLS-1$
        index.configurationUuid = configurationUuid;
        SyncBaseline.write(index, file);
    }
}
