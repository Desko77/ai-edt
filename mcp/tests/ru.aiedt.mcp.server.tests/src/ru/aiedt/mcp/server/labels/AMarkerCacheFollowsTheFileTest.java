/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;

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
 * The marker cache follows the file: an edit made outside the workspace is read on the next
 * request instead of being answered from the cache, a mutation after such an edit is refused
 * rather than written over it, and a file the workspace does not know about - one that arrived
 * on disk, or one that disappeared while the tree still lists it - is neither replaced nor
 * recreated from an empty read.
 */
public class AMarkerCacheFollowsTheFileTest
{
    private static final String PROJECT = "AiEdtMarkerFileFollows"; //$NON-NLS-1$

    private static final String OUTSIDE = "tags:" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "- color: '#112233'" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "  description: written outside" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "  name: external" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "assignments: {}" + "\n"; //$NON-NLS-1$ //$NON-NLS-2$

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
        root = Files.createTempDirectory("aiedt-marker-follows"); //$NON-NLS-1$
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
     * A fresh service and no marker file, so one test cannot leave a cache for the next.
     *
     * @throws Exception when the file cannot be removed
     */
    @Before
    public void aFreshMarkerFile() throws Exception
    {
        MarkerManager.dispose();
        Path yaml = markerFile();
        if (Files.exists(yaml))
        {
            yaml.toFile().setWritable(true);
            Files.deleteIfExists(yaml);
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
     * A warm cache that no longer describes the file is reloaded from what the file holds now.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aWarmCacheAfterAnExternalEditIsReloaded() throws Exception
    {
        MarkerManager manager = MarkerManager.getInstance();
        assertNotNull(manager.createMarker(project, "bug", null, null)); //$NON-NLS-1$
        assertTrue("the warm path served the marker this instance wrote", //$NON-NLS-1$
            manager.getMarkers(project).stream().anyMatch(marker -> "bug".equals(marker.getName()))); //$NON-NLS-1$

        Files.write(markerFile(), OUTSIDE.getBytes(StandardCharsets.UTF_8));

        assertTrue("the warm cache must not answer after the file changed", //$NON-NLS-1$
            manager.getMarkers(project).stream().anyMatch(marker -> "external".equals(marker.getName()))); //$NON-NLS-1$
        assertNull("the marker that only the stale cache knew is gone", //$NON-NLS-1$
            manager.getMarkerStorage(project).getMarkerByName("bug")); //$NON-NLS-1$
    }

    /**
     * A mutation after an external edit builds on the external content: the marker the stale
     * cache knew is not written over it, and the assignment lands next to the external marker.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void anExternalEditIsCarriedByTheNextSave() throws Exception
    {
        MarkerManager manager = MarkerManager.getInstance();
        assertNotNull(manager.createMarker(project, "bug", null, null)); //$NON-NLS-1$
        Files.write(markerFile(), OUTSIDE.getBytes(StandardCharsets.UTF_8));

        assertTrue("the mutation must read the external content first and apply to it", //$NON-NLS-1$
            manager.assignMarker(project, "Catalog.X", "external")); //$NON-NLS-1$ //$NON-NLS-2$

        String stored = Files.readString(markerFile());
        assertTrue("the external marker is still defined", stored.contains("external")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the new assignment stands next to it", stored.contains("Catalog.X")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the marker only the stale cache knew is not written back", //$NON-NLS-1$
            stored.contains("name: bug")); //$NON-NLS-1$
    }

    /**
     * A file that arrived on disk behind the workspace's back is read from the disk, and a
     * mutation adds to it instead of writing an empty storage over it.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void aFileThatArrivedOnDiskIsReadAndKept() throws Exception
    {
        Files.createDirectories(markerFile().getParent());
        Files.write(markerFile(), OUTSIDE.getBytes(StandardCharsets.UTF_8));

        MarkerManager manager = MarkerManager.getInstance();
        assertTrue("a file the tree does not know is still the project's markers", //$NON-NLS-1$
            manager.getMarkers(project).stream().anyMatch(marker -> "external".equals(marker.getName()))); //$NON-NLS-1$

        assertNotNull(manager.createMarker(project, "added", null, null)); //$NON-NLS-1$
        String stored = Files.readString(markerFile());
        assertTrue("the markers that were on disk survive the save", stored.contains("external")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stored.contains("added")); //$NON-NLS-1$
    }

    /**
     * A file that disappeared from the disk while the tree still lists it counts as no file: the
     * next read answers no markers, and no mutation writes an empty store's file back into being.
     *
     * @throws Exception when the file cannot be deleted
     */
    @Test
    public void aFileDeletedBehindTheTreeIsNotCachedAsEmpty() throws Exception
    {
        MarkerManager manager = MarkerManager.getInstance();
        assertNotNull(manager.createMarker(project, "bug", null, null)); //$NON-NLS-1$
        assertTrue("the tree knows the file for this test to mean anything", //$NON-NLS-1$
            project.getFile(new org.eclipse.core.runtime.Path(".settings/" + MarkerKeys.MARKERS_FILE)) //$NON-NLS-1$ //$NON-NLS-2$
                .exists());
        Files.delete(markerFile());

        assertTrue("the disk decided the file is gone, so no markers are answered", //$NON-NLS-1$
            manager.getMarkers(project).isEmpty());
        assertFalse("the markers of a state nobody holds must not be written back", //$NON-NLS-1$
            manager.assignMarker(project, "Catalog.X", "bug")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("the deleted file is not recreated from the vanished read", //$NON-NLS-1$
            Files.exists(markerFile()));
    }

    /**
     * A read that the workspace refuses is not cached as an empty store either: the mutation
     * refuses, and what stands in the file's place is left as it is.
     *
     * @throws Exception when the file cannot be replaced with a directory
     */
    @Test
    public void aDirectoryInPlaceOfTheFileIsNotCachedAsEmpty() throws Exception
    {
        MarkerManager manager = MarkerManager.getInstance();
        assertNotNull(manager.createMarker(project, "bug", null, null)); //$NON-NLS-1$
        Files.delete(markerFile());
        Files.createDirectories(markerFile());

        assertTrue("a read the workspace refuses answers no markers", manager.getMarkers(project).isEmpty()); //$NON-NLS-1$
        assertFalse(manager.assignMarker(project, "Catalog.X", "bug")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the directory is left standing", Files.isDirectory(markerFile())); //$NON-NLS-1$
        assertNull(manager.createMarker(project, "later", null, null)); //$NON-NLS-1$
    }

    /**
     * Where this project's marker file is.
     *
     * @return the path, which may not exist yet
     */
    private static Path markerFile()
    {
        return project.getLocation().toFile().toPath().resolve(MarkerKeys.SETTINGS_FOLDER)
            .resolve(MarkerKeys.MARKERS_FILE);
    }
}
