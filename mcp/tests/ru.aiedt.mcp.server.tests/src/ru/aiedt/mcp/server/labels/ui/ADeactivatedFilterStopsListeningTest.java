/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.labels.MarkerManager;

/**
 * A filter taken out of the Navigator stops listening to marker changes, and one put back starts
 * again.
 * <p>
 * The subscription made at construction used to outlive the filter's use: nothing took it out, so
 * an inactive filter kept receiving changes into a cache nobody read. The controller now
 * deactivates the filter on the way out and activates it on the way in, and this holds the filter
 * itself to that contract.
 * </p>
 */
public class ADeactivatedFilterStopsListeningTest
{
    private static final String PROJECT = "AiEdtFilterListening"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    /**
     * A temporary project a marker can be defined in.
     *
     * @throws Exception when the workspace refuses the project
     */
    @BeforeClass
    public static void aTemporaryProject() throws Exception
    {
        root = Files.createTempDirectory("aiedt-filter-listening"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject existing = workspace.getRoot().getProject(PROJECT);
        if (existing.exists())
        {
            existing.delete(true, true, new NullProgressMonitor());
        }
        IProject created = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        created.create(description, new NullProgressMonitor());
        created.open(new NullProgressMonitor());
        project = created;
    }

    /**
     * Removes the project and the directory it was created in.
     *
     * @throws Exception when the project cannot be deleted
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        MarkerManager.dispose();
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /**
     * A fresh service and a fresh marker file, so one test cannot read what another wrote.
     *
     * @throws Exception when the marker file cannot be removed
     */
    @Before
    public void aFreshServiceAndFile() throws Exception
    {
        MarkerManager.dispose();
        java.nio.file.Path settings = project.getLocation().toFile().toPath()
            .resolve(".settings").resolve("aiedt-markers.yaml"); //$NON-NLS-1$ //$NON-NLS-2$
        java.nio.file.Files.deleteIfExists(settings);
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Drops the service the test used.
     */
    @After
    public void theServiceStops()
    {
        MarkerManager.dispose();
    }

    /** A deactivated filter is not told about marker changes any more. */
    @Test
    public void aDeactivatedFilterIsNotToldAboutChanges()
    {
        MarkerQueryFilter filter = new MarkerQueryFilter();
        filter.setSelectedMarkersMode(java.util.Map.of(project, Set.of()));
        filter.matchCache().put(project, Set.of("Catalog.Products")); //$NON-NLS-1$

        filter.deactivate();

        assertTrue(MarkerManager.getInstance().createMarker(project, "bug", null, null) != null);
        assertTrue("the cached answer of an inactive filter is its own business", //$NON-NLS-1$
            filter.matchCache().contains(project));
    }

    /** An activated filter hears about changes again. */
    @Test
    public void anActivatedFilterHearsAboutChangesAgain()
    {
        MarkerQueryFilter filter = new MarkerQueryFilter();
        filter.setSelectedMarkersMode(java.util.Map.of(project, Set.of()));
        filter.matchCache().put(project, Set.of("Catalog.Products")); //$NON-NLS-1$
        filter.deactivate();
        filter.activate();

        assertTrue(MarkerManager.getInstance().createMarker(project, "bug", null, null) != null);

        assertFalse("a change cleared the cache of a listening filter", //$NON-NLS-1$
            filter.matchCache().contains(project));
    }
}
