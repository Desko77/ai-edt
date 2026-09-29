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
import static org.junit.Assume.assumeFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Comparator;
import java.util.HashSet;
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

import ru.aiedt.mcp.server.labels.model.MarkerStore;
import ru.aiedt.mcp.server.labels.ui.MarkerMatchCache;
import ru.aiedt.mcp.server.labels.ui.MarkerQueryFilter;

/**
 * What the marker file does when it cannot be written, cannot be parsed, or is read while it changes.
 */
public class AMarkerThatCouldNotBeSavedIsNotAssignedTest
{
    private static final String PROJECT = "AiEdtMarkerFileProbe"; //$NON-NLS-1$

    private static final String CONFLICT = "<<<<<<< HEAD" + "\n" + "markers: []" + "\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        + "=======" + "\n" + "markers: []" + "\n" + ">>>>>>> other" + "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

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
        root = Files.createTempDirectory("aiedt-marker-file"); //$NON-NLS-1$
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
            readOnly(yaml, false);
            Files.deleteIfExists(yaml);
        }
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Drops the service the test registered listeners on.
     */
    @After
    public void theServiceStops()
    {
        MarkerManager.dispose();
    }

    @Test
    public void aReadOnlyFileDoesNotTakeTheAssignment() throws Exception
    {
        MarkerManager manager = MarkerManager.getInstance();
        assertNotNull(manager.createMarker(project, "bug", "#ff0000", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(manager.assignMarker(project, "Catalog.Keep", "bug")); //$NON-NLS-1$ //$NON-NLS-2$
        Path yaml = markerFile();
        byte[] before = Files.readAllBytes(yaml);
        readOnly(yaml, true);
        try
        {
            assumeFalse("the file system lets this user write a read-only file", Files.isWritable(yaml)); //$NON-NLS-1$
            assertFalse(manager.assignMarker(project, "Catalog.X", "bug")); //$NON-NLS-1$ //$NON-NLS-2$
            MarkerStore stored = manager.getMarkerStorage(project);
            assertTrue(stored.getMarkerNames("Catalog.X").isEmpty()); //$NON-NLS-1$
            assertTrue(stored.getMarkerNames("Catalog.Keep").contains("bug")); //$NON-NLS-1$ //$NON-NLS-2$
            assertArrayEquals(before, Files.readAllBytes(yaml));
        }
        finally
        {
            readOnly(yaml, false);
        }
    }

    @Test
    public void anUnreadableFileIsNotReplaced() throws Exception
    {
        Path yaml = markerFile();
        Files.createDirectories(yaml.getParent());
        byte[] original = CONFLICT.getBytes(StandardCharsets.UTF_8);
        Files.write(yaml, original);
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        MarkerManager manager = MarkerManager.getInstance();
        assertNull(manager.createMarker(project, "new", null, null)); //$NON-NLS-1$
        assertEquals(MarkerManager.UNREADABLE_MARKER_FILE, manager.markerFileRefusal(project));
        assertArrayEquals(original, Files.readAllBytes(yaml));
        assertFalse(manager.assignMarker(project, "Catalog.X", "bug")); //$NON-NLS-1$ //$NON-NLS-2$
        assertArrayEquals(original, Files.readAllBytes(yaml));
    }

    @Test
    public void aSnapshotDoesNotSeeALaterMarker()
    {
        MarkerManager manager = MarkerManager.getInstance();
        assertNotNull(manager.createMarker(project, "bug", "#112233", "kept")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        MarkerStore snapshot = manager.getMarkerStorage(project);

        assertNotNull(manager.createMarker(project, "later", null, null)); //$NON-NLS-1$
        snapshot.addMarker(new ru.aiedt.mcp.server.labels.model.Marker("ghost")); //$NON-NLS-1$

        assertNull(snapshot.getMarkerByName("later")); //$NON-NLS-1$
        assertNull(manager.getMarkerStorage(project).getMarkerByName("ghost")); //$NON-NLS-1$
        assertNotNull(manager.getMarkerStorage(project).getMarkerByName("later")); //$NON-NLS-1$
    }

    @Test
    public void aRenamedObjectCarriesAChildMarker()
    {
        MarkerManager manager = MarkerManager.getInstance();
        assertNotNull(manager.createMarker(project, "bug", null, null)); //$NON-NLS-1$
        assertNotNull(manager.createMarker(project, "note", null, null)); //$NON-NLS-1$
        assertTrue(manager.assignMarker(project, "Catalog.Products", "bug")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(manager.assignMarker(project, "Catalog.Products.CatalogAttribute.Description", "note")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(manager.holdsObjectOrDescendant(project, "Catalog.Products")); //$NON-NLS-1$
        assertFalse(manager.holdsObjectOrDescendant(project, "Catalog.ProductsExtra")); //$NON-NLS-1$
        assertTrue(manager.renameObject(project, "Catalog.Products", "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$

        MarkerStore stored = manager.getMarkerStorage(project);
        assertTrue(stored.getMarkerNames("Catalog.Goods").contains("bug")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stored.getMarkerNames("Catalog.Goods.CatalogAttribute.Description").contains("note")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(stored.getMarkerNames("Catalog.Products").isEmpty()); //$NON-NLS-1$
        assertTrue(stored.getMarkerNames("Catalog.Products.CatalogAttribute.Description").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void aMarkerChangeDropsTheCachedMatches()
    {
        MarkerManager manager = MarkerManager.getInstance();
        assertNotNull(manager.createMarker(project, "bug", null, null)); //$NON-NLS-1$
        MarkerQueryFilter filter = new MarkerQueryFilter();
        MarkerMatchCache<IProject> cache = filter.matchCache();
        cache.put(project, new HashSet<>(Set.of("Catalog.Keep"))); //$NON-NLS-1$

        assertTrue(manager.assignMarker(project, "Catalog.Fresh", "bug")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(cache.contains(project));
    }

    /**
     * Sets or clears the read-only state of a file. The DOS attribute exists only on Windows file
     * systems; elsewhere the permission bits carry it.
     *
     * @param file the file to change
     * @param readOnly true to make the file read-only
     * @throws Exception when the attribute cannot be set
     */
    private static void readOnly(Path file, boolean readOnly) throws Exception
    {
        file.toFile().setWritable(!readOnly);
        // Linux offers the DOS view through extended attributes, which a file that is no longer
        // writable refuses to change; the permission bit alone makes it read-only there.
        DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
        if (dos != null && System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            dos.setReadOnly(readOnly);
        }
    }

    /**
     * Where this project's marker file is.
     *
     * @return the path, which may not exist yet
     */
    private static Path markerFile()
    {
        return project.getLocation().toFile().toPath().resolve(MarkerKeys.SETTINGS_FOLDER).resolve(MarkerKeys.MARKERS_FILE);
    }
}
