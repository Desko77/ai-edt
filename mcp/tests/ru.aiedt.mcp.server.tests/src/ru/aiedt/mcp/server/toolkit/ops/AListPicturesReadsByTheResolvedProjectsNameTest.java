/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A picture listing that names a project reads by the project's own spelling of its name.
 * <p>
 * The resolve of {@code projectName} matches a name in another case, while the reads below it -
 * the version read for the stock list and the configuration read for the common pictures - look
 * the name up case-sensitively. A name handed on unresolved quietly answered the newest platform
 * version and no common pictures, the same silent substitution the resolve was added to prevent.
 * The reads here stand in for the platform and record the name each one received, so the handoff
 * itself is what the answer is judged by: an empty stock list in a test runtime says nothing
 * about which version was asked for.
 * </p>
 */
public class AListPicturesReadsByTheResolvedProjectsNameTest
{
    private static final String PROJECT = "AiEdtPictureCaseProbe"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    /**
     * Opens a project in the workspace, the way a caller's project stands in it.
     *
     * @throws Exception when the workspace refuses the project
     */
    @BeforeClass
    public static void aProjectStandsInTheWorkspace() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-picture-case"); //$NON-NLS-1$
        project = openProject(PROJECT, projectDir);
    }

    /**
     * Takes the project and its directory away.
     *
     * @throws Exception when the deletion fails
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (projectDir != null)
        {
            try (var walk = Files.walk(projectDir))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile)
                    .forEach(java.io.File::delete);
            }
        }
    }

    /**
     * A name that differs from the project's only in case reaches both reads as the project's own
     * spelling: the resolve answered the project, and the reads follow the project, not the string
     * the caller happened to send.
     */
    @Test
    public void aNameInAnotherCaseReachesTheReadsAsTheProjectsOwn()
    {
        RecordingReads reads = new RecordingReads();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", "aiedtpicturecaseprobe"); //$NON-NLS-1$ //$NON-NLS-2$

        FormItemsOps.opListPictures(params, reads);

        assertEquals("the version read follows the resolved project", //$NON-NLS-1$
            PROJECT, reads.stockReadFor);
        assertEquals("the common-picture read follows the resolved project", //$NON-NLS-1$
            PROJECT, reads.commonReadFor);
    }

    /**
     * The project's own spelling reaches the reads unchanged: the resolve is a guard in front of
     * the reads, not a renaming of what already matched.
     */
    @Test
    public void theProjectsOwnSpellingReachesTheReadsUnchanged()
    {
        RecordingReads reads = new RecordingReads();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$

        String answer = FormItemsOps.opListPictures(params, reads);

        assertEquals(PROJECT, reads.stockReadFor);
        assertEquals(PROJECT, reads.commonReadFor);
        assertFalse("a project that exists is not a refusal", answer.contains("projectNotFound")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A name that resolves to nothing is refused before any read happens, so a typo answers the
     * refusal and not a listing built from a name no project carries.
     */
    @Test
    public void aNameThatResolvesToNothingIsRefusedBeforeTheReads()
    {
        RecordingReads reads = new RecordingReads();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", "aiedt-tests-no-such-project"); //$NON-NLS-1$ //$NON-NLS-2$

        String answer = FormItemsOps.opListPictures(params, reads);

        assertTrue(answer, answer.contains("projectNotFound")); //$NON-NLS-1$
        assertFalse("a refused name reaches no read", reads.anythingRead()); //$NON-NLS-1$
    }

    /**
     * @param name the project name
     * @param location the directory the project lives in
     * @return the opened project
     * @throws Exception when the workspace refuses the project
     */
    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject opened = workspace.getRoot().getProject(name);
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        opened.create(description, new NullProgressMonitor());
        opened.open(new NullProgressMonitor());
        return opened;
    }

    /** Stands in for the platform reads and records the name each one received. */
    private static final class RecordingReads implements FormItemsOps.PictureReads
    {
        /** The name the stock read received; {@code null} until it runs. */
        String stockReadFor;

        /** The name the common read received; {@code null} until it runs. */
        String commonReadFor;

        @Override
        public List<ru.aiedt.mcp.server.support.StockPictures.Entry> stock(String projectName)
        {
            stockReadFor = projectName;
            return new ArrayList<>();
        }

        @Override
        public List<String> common(String projectName, String filter)
        {
            commonReadFor = projectName;
            return new ArrayList<>();
        }

        /**
         * @return whether either read ran
         */
        boolean anythingRead()
        {
            return stockReadFor != null || commonReadFor != null;
        }
    }
}
