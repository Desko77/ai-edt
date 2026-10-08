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
import java.util.concurrent.atomic.AtomicInteger;

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
 * A reader's markers and its refusal come from one read of the marker file.
 * <p>
 * The marker readers asked the service twice - once for the refusal and once for the markers - and
 * the two calls read the file apart. A read that failed is not a state, only the answer to the last
 * attempt, so the second call reads again; when the file went away in that window the first answer
 * was a refusal the second read contradicted, or, in the order below, the file was read and the
 * answer was an absence that no read had found.
 * </p>
 */
public class AReadAnswersItsMarkersAndItsRefusalTogetherTest
{
    private static final String PROJECT = "AiEdtMarkerReadOnce"; //$NON-NLS-1$

    private static final String MARKERS = "tags:" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "- color: '#112233'" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "  name: Kept" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
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
        root = Files.createTempDirectory("aiedt-marker-read-once"); //$NON-NLS-1$
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
        MarkerManager.markerReadProbeForTests = null;
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
     * A fresh service, no probe and no marker file, so one test cannot leave state for the next.
     *
     * @throws Exception when the file cannot be removed
     */
    @Before
    public void aFreshMarkerFile() throws Exception
    {
        MarkerManager.dispose();
        MarkerManager.markerReadProbeForTests = null;
        Files.deleteIfExists(markerFile());
        Files.createDirectories(markerFile().getParent());
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Drops the service and the probe the test used.
     */
    @After
    public void theServiceStops()
    {
        MarkerManager.markerReadProbeForTests = null;
        MarkerManager.dispose();
    }

    /**
     * The tag list is answered from the read that judged it, so a file that stops being readable
     * after that read does not turn the markers into an absence.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void theTagListIsNotTurnedIntoAnAbsenceByALaterRead() throws Exception
    {
        Files.write(markerFile(), MARKERS.getBytes(StandardCharsets.UTF_8));
        theFileStopsBeingReadableFromTheSecondRead();

        String answer = new TagsReader().getMarkers(project);

        assertTrue("the markers come from the read that was judged: " + answer, //$NON-NLS-1$
            answer.contains("Kept")); //$NON-NLS-1$
        assertFalse("a file that was read is not reported as an absence", //$NON-NLS-1$
            answer.contains("no markers defined")); //$NON-NLS-1$
    }

    /**
     * The same for the objects of a named marker: the marker is reported from the read that was
     * judged instead of being called an unmatched name.
     *
     * @throws Exception when the file cannot be written
     */
    @Test
    public void theObjectsOfAMarkerAreNotTurnedIntoAnUnmatchedName() throws Exception
    {
        Files.write(markerFile(), MARKERS.getBytes(StandardCharsets.UTF_8));
        theFileStopsBeingReadableFromTheSecondRead();

        String answer = new TaggedObjectsReader().getObjectsByMarkers(project, List.of("Kept"), 20); //$NON-NLS-1$

        assertTrue("the marker comes from the read that was judged: " + answer, //$NON-NLS-1$
            answer.contains("## Marker - Kept")); //$NON-NLS-1$
        assertFalse("a marker that was read is not called unmatched", //$NON-NLS-1$
            answer.contains("Unmatched marker names")); //$NON-NLS-1$
    }

    /**
     * Makes the marker file stop being readable on every read after the first: the path becomes a
     * directory, which a read of the bytes fails on.
     * <p>
     * Counted through the manager's read probe, so the file is intact for the read that judges the
     * answer and broken for any read after it - the window a second read of the same answer falls
     * into.
     * </p>
     *
     * @throws Exception when the file cannot be replaced by a directory
     */
    private static void theFileStopsBeingReadableFromTheSecondRead() throws Exception
    {
        AtomicInteger reads = new AtomicInteger();
        MarkerManager.markerReadProbeForTests = ignored -> {
            if (reads.incrementAndGet() > 1)
            {
                try
                {
                    Path yaml = markerFile();
                    Files.deleteIfExists(yaml);
                    Files.createDirectories(yaml);
                }
                catch (Exception e)
                {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    /**
     * The marker file of the temporary project.
     *
     * @return its path on disk
     */
    private static Path markerFile()
    {
        return root.resolve(PROJECT).resolve(".settings").resolve("aiedt-markers.yaml"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
