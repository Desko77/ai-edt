/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.eclipse.core.resources.IFile;
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

/**
 * A first marker write into a project whose tree holds no settings folder still leaves the file a
 * workspace resource, so listeners and team providers see it without an outside refresh.
 */
public class AFirstMarkerWriteIsAWorkspaceResourceTest
{
    private static final String PROJECT = "AiEdtMarkerFirstWrite"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    /**
     * A temporary project the marker file can live in.
     *
     * @throws Exception when the workspace refuses the project
     */
    @BeforeClass
    public static void aTemporaryProject() throws Exception
    {
        root = Files.createTempDirectory("aiedt-marker-first"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir);
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
     * A tree and a disk with no settings folder, so the write under test is a first one.
     *
     * @throws Exception when the folder cannot be removed
     */
    @Before
    public void noSettingsFolderAnywhere() throws Exception
    {
        MarkerManager.dispose();
        Path settings = project.getLocation().toFile().toPath().resolve(MarkerKeys.SETTINGS_FOLDER);
        if (Files.exists(settings))
        {
            try (var walk = Files.walk(settings))
            {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
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

    /**
     * The first write makes the file a workspace resource on its own. The refresh the write ends
     * with attaches the file although the tree held neither the file nor its folder, so listeners
     * and team providers see the marker file without an outside refresh.
     */
    @Test
    public void theFirstWriteAttachesTheFileWithoutAnOutsideRefresh()
    {
        assertFalse("the tree must not hold the settings folder for this test to mean anything", //$NON-NLS-1$
            project.getFolder(MarkerKeys.SETTINGS_FOLDER).exists());
        MarkerManager manager = MarkerManager.getInstance();
        assertNotNull(manager.createMarker(project, "bug", null, null)); //$NON-NLS-1$
        IFile file = project.getFolder(MarkerKeys.SETTINGS_FOLDER).getFile(MarkerKeys.MARKERS_FILE);
        assertTrue("the first write must make the file a workspace resource on its own", file.exists()); //$NON-NLS-1$
    }
}
