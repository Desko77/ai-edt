/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.labels.MarkerKeys;
import ru.aiedt.mcp.server.labels.MarkerManager;

/**
 * A marker file that does not parse is reported as a failure of the read by the reading operations,
 * and not as a project that defines no markers.
 * <p>
 * An unreadable file loads as an empty storage, so {@code get_tags} answered "this project has no
 * markers defined" and {@code get_objects_by_tags} answered every requested name as unmatched -
 * both read as facts about the project, and the party reading them has no reason to look at the
 * file. The writing operations refuse on the same sign.
 * </p>
 */
public class AReadingOfAnUnreadableMarkerFileRefusesTest
{
    private static final String PROJECT = "AiEdtMarkerReadProbe"; //$NON-NLS-1$

    /** Git conflict markers where a YAML mapping belongs, which is how a merge leaves the file. */
    private static final String CONFLICT = "<<<<<<< HEAD" + "\n" + "markers: []" + "\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        + "=======" + "\n" + "markers: []" + "\n" + ">>>>>>> other" + "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    private static final String NO_MARKERS = "no markers defined"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    /**
     * A scratch project with a directory of its own for the marker file.
     *
     * @throws Exception when the workspace refuses the project
     */
    @BeforeClass
    public static void aScratchProject() throws Exception
    {
        root = Files.createTempDirectory("aiedt-marker-read"); //$NON-NLS-1$
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
        Files.deleteIfExists(markerFile());
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /** Drops the service the test left behind. */
    @After
    public void theServiceStops()
    {
        MarkerManager.dispose();
    }

    /**
     * Writes the marker file and makes the workspace see it.
     *
     * @param text what the file is to hold
     * @throws Exception when the file cannot be written
     */
    private static void theMarkerFileHolds(String text) throws Exception
    {
        Path yaml = markerFile();
        Files.createDirectories(yaml.getParent());
        Files.write(yaml, text.getBytes(StandardCharsets.UTF_8));
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Where this project's marker file is.
     *
     * @return the path, which may not exist
     */
    private static Path markerFile()
    {
        return project.getLocation().toFile().toPath()
            .resolve(MarkerKeys.SETTINGS_FOLDER).resolve(MarkerKeys.MARKERS_FILE);
    }

    /** {@code get_tags} refuses a file it cannot parse instead of listing nothing. */
    @Test
    public void getTagsRefusesAnUnreadableFile() throws Exception
    {
        theMarkerFileHolds(CONFLICT);

        String answer = new TagsReader().getMarkers(project);
        JsonObject refusal = JsonParser.parseString(answer).getAsJsonObject();

        assertFalse(answer, refusal.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer, refusal.get("error").getAsString().contains("unreadable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an unread file is not a project without markers: " + answer, //$NON-NLS-1$
            answer.contains(NO_MARKERS));
    }

    /** {@code get_objects_by_tags} refuses the same file instead of calling every name unmatched. */
    @Test
    public void getObjectsByTagsRefusesAnUnreadableFile() throws Exception
    {
        theMarkerFileHolds(CONFLICT);

        String answer = new TaggedObjectsReader().getObjectsByMarkers(project,
            List.of("Important"), 100); //$NON-NLS-1$
        JsonObject refusal = JsonParser.parseString(answer).getAsJsonObject();

        assertFalse(answer, refusal.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer, refusal.get("error").getAsString().contains("unreadable")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an unread file is not an unmatched name: " + answer, //$NON-NLS-1$
            answer.contains("Unmatched")); //$NON-NLS-1$
    }

    /** A project with no marker file keeps the answer it had: it defines none. */
    @Test
    public void getTagsWithoutAFileStillSaysThereAreNone()
    {
        String answer = new TagsReader().getMarkers(project);

        assertTrue(answer, answer.contains(NO_MARKERS));
        assertTrue(answer, answer.contains(PROJECT));
    }

    /** A project with no marker file keeps the answer it had: nothing matches the names. */
    @Test
    public void getObjectsByTagsWithoutAFileStillReportsTheNamesUnmatched()
    {
        String answer = new TaggedObjectsReader().getObjectsByMarkers(project,
            List.of("Important"), 100); //$NON-NLS-1$

        assertTrue(answer, answer.contains("Unmatched marker names")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Important")); //$NON-NLS-1$
    }
}
