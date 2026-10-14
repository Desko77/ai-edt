/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

import ru.aiedt.mcp.server.labels.MarkerManager.PreviewRead;
import ru.aiedt.mcp.server.labels.model.MarkerStore;

/**
 * A preview of a project waits for the read of its marker file the service is in the middle of.
 * <p>
 * An ordinary read of a project's markers takes the service's lock and works on the file inside
 * it; a save does the same. A preview that read the file without that lock judges whatever the
 * file holds at that instant, which is a file another holder may be halfway through - an edit
 * judged against half a document, or against nothing at all, is refused or promised for the wrong
 * reason. The read under test is held inside the lock until the test lets it go, so the time the
 * preview takes is the whole signal here.
 * </p>
 */
public class ATagPreviewWaitsForAReadInProgressTest
{
    private static final String PROJECT = "AiEdtTagPreviewWaits"; //$NON-NLS-1$

    private static final String TWO_TAGS = "tags:" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "- color: '#112233'" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "  description: written outside" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "  name: external" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "- name: second" + "\n" //$NON-NLS-1$ //$NON-NLS-2$
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
        root = Files.createTempDirectory("aiedt-tag-preview-waits"); //$NON-NLS-1$
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
     * A fresh service over a project with no marker file, so one test cannot leave a cached
     * storage or a remembered failure for the next.
     *
     * @throws Exception when the file cannot be removed
     */
    @Before
    public void aFreshServiceAndNoMarkerFile() throws Exception
    {
        MarkerManager.dispose();
        Files.deleteIfExists(markerFile());
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Takes the probe out and drops the service the test used.
     */
    @After
    public void theProbeGoes()
    {
        MarkerManager.markerReadProbeForTests = null;
        MarkerManager.dispose();
    }

    /**
     * A preview asked while an ordinary read holds the service answers only after that read has
     * finished, and it answers the markers of the whole file.
     *
     * @throws Exception when the marker file cannot be written
     */
    @Test(timeout = 120_000)
    public void aPreviewWaitsForTheReadTheServiceIsInTheMiddleOf() throws Exception
    {
        Files.createDirectories(markerFile().getParent());
        Files.write(markerFile(), TWO_TAGS.getBytes(StandardCharsets.UTF_8));
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        CountDownLatch insideTheRead = new CountDownLatch(1);
        CountDownLatch letTheReadGo = new CountDownLatch(1);
        AtomicBoolean firstAttempt = new AtomicBoolean(true);
        MarkerManager.markerReadProbeForTests = ignored ->
        {
            // Only the read of the first thread is held: the preview reaches the probe as well,
            // and blocking there would make the test measure itself.
            if (firstAttempt.compareAndSet(true, false))
            {
                insideTheRead.countDown();
                try
                {
                    letTheReadGo.await(60, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }
        };

        MarkerManager manager = MarkerManager.getInstance();
        MarkerStore[] read = new MarkerStore[1];
        Thread reading = new Thread(() -> read[0] = manager.getMarkerStorage(project), "the-read"); //$NON-NLS-1$
        PreviewRead[] previewed = new PreviewRead[1];
        Thread previewing = new Thread(() -> previewed[0] = manager.readMarkersForPreview(project),
            "the-preview"); //$NON-NLS-1$
        boolean stillWaiting;
        try
        {
            reading.start();
            assertTrue("the read reached the marker file", //$NON-NLS-1$
                insideTheRead.await(60, TimeUnit.SECONDS));
            previewing.start();
            previewing.join(1000);
            stillWaiting = previewing.isAlive();
        }
        finally
        {
            letTheReadGo.countDown();
            reading.join(60_000);
            previewing.join(60_000);
        }

        assertTrue("the preview waits for the read in progress", stillWaiting); //$NON-NLS-1$
        assertNotNull("the read answered", read[0]); //$NON-NLS-1$
        assertEquals("the read found both tags", 2, read[0].getTags().size()); //$NON-NLS-1$
        assertNotNull("the preview answered", previewed[0]); //$NON-NLS-1$
        assertFalse("the preview refused the file the read had just judged", //$NON-NLS-1$
            previewed[0].unreadable() || previewed[0].readFailed());
        assertEquals("the preview judged the whole file", 2, previewed[0].storage().getTags().size()); //$NON-NLS-1$
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
