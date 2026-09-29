/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A sweep writes the whole file through the workspace, so a later read of the same {@link IFile}
 * sees the objects that were removed, and no temporary file is left behind.
 */
public class ASweepReachesTheWorkspaceAndWritesItWholeTest
{
    private static final String PROJECT = "AiEdtRightsSweepProbe"; //$NON-NLS-1$

    private static final String ROLE = "Роль1"; //$NON-NLS-1$

    private static final String KEPT = "Catalog.Есть"; //$NON-NLS-1$

    private static final String GONE = "Catalog.Нет"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    /**
     * Opens an empty project. Each test creates its own rights file through {@link IFile}.
     *
     * @throws Exception when the workspace cannot create the project
     */
    @BeforeClass
    public static void aPlainProject() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-rights-sweep"); //$NON-NLS-1$
        project = openProject(PROJECT, projectDir);
    }

    /**
     * Removes the project and the directory under it.
     *
     * @throws Exception when the workspace cannot delete the project
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
        deleteTree(projectDir);
    }

    /**
     * Removing one object leaves the other, and {@link IFile#getContents()} sees that without a
     * refresh in the test.
     *
     * @throws Exception when the fixture or the read fails
     */
    @Test
    public void aRemovedObjectIsGoneOnTheNextRead() throws Exception
    {
        IFile file = createRights(rightsXml(KEPT, GONE));

        BmRightsHelper.OrphanSweep sweep = BmRightsHelper.sweepOrphanedRights(
            project, ROLE, fqn -> GONE.equals(fqn) ? Boolean.FALSE : Boolean.TRUE, true);

        assertTrue(sweep.error == null ? "swept" : sweep.error, sweep.ok); //$NON-NLS-1$
        assertTrue(sweep.changed);
        assertTrue(sweep.orphaned.contains(GONE));
        String text = read(file);
        assertTrue(text.contains(KEPT));
        assertFalse(text.contains(GONE));
        assertFalse(Files.exists(roleDir().resolve("Rights.rights.tmp"))); //$NON-NLS-1$
    }

    /**
     * Removing the last object still writes the file. The empty-document guard is for a writer
     * that lost its data, not for a sweep that deleted the last block on purpose.
     *
     * @throws Exception when the fixture or the read fails
     */
    @Test
    public void removingTheLastObjectStillWrites() throws Exception
    {
        IFile file = createRights(rightsXml(GONE));

        BmRightsHelper.OrphanSweep sweep = BmRightsHelper.sweepOrphanedRights(
            project, ROLE, fqn -> Boolean.FALSE, true);

        assertTrue(sweep.error == null ? "swept" : sweep.error, sweep.ok); //$NON-NLS-1$
        assertTrue(sweep.changed);
        String text = read(file);
        assertFalse(text.contains(GONE));
        assertFalse(Files.exists(roleDir().resolve("Rights.rights.tmp"))); //$NON-NLS-1$
    }

    /**
     * A resolver that throws is a failed sweep, and the failure does not name the rights file.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void aSweepThatThrowsDoesNotNameTheFile() throws Exception
    {
        createRights(rightsXml(KEPT));
        String file = roleDir().resolve("Rights.rights").toString(); //$NON-NLS-1$

        BmRightsHelper.OrphanSweep sweep = BmRightsHelper.sweepOrphanedRights(project, ROLE, fqn -> {
            throw new IllegalStateException("resolver failed"); //$NON-NLS-1$
        }, false);

        assertFalse(sweep.ok);
        assertTrue(sweep.error.contains("could not sweep the role")); //$NON-NLS-1$
        assertFalse(sweep.error.contains(file));
        assertFalse(sweep.error.contains(projectDir.toString()));
    }

    /**
     * A rights document holding one block per name.
     *
     * @param names object names
     * @return the document bytes
     */
    private static byte[] rightsXml(String... names)
    {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Rights>\n"); //$NON-NLS-1$
        for (String name : names)
        {
            xml.append("  <object><name>").append(name).append("</name>") //$NON-NLS-1$ //$NON-NLS-2$
                .append("<right><name>Read</name><value>true</value></right></object>\n"); //$NON-NLS-1$
        }
        xml.append("</Rights>\n"); //$NON-NLS-1$
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates the rights file through the workspace, replacing one a previous test left.
     *
     * @param xml the document bytes
     * @return the file
     * @throws CoreException when a folder or the file cannot be created
     */
    private static IFile createRights(byte[] xml) throws CoreException
    {
        IFolder src = project.getFolder("src"); //$NON-NLS-1$
        if (!src.exists())
        {
            src.create(true, true, new NullProgressMonitor());
        }
        IFolder roles = src.getFolder("Roles"); //$NON-NLS-1$
        if (!roles.exists())
        {
            roles.create(true, true, new NullProgressMonitor());
        }
        IFolder role = roles.getFolder(ROLE);
        if (!role.exists())
        {
            role.create(true, true, new NullProgressMonitor());
        }
        IFile file = role.getFile("Rights.rights"); //$NON-NLS-1$
        if (file.exists())
        {
            file.setContents(new ByteArrayInputStream(xml), true, false, new NullProgressMonitor());
        }
        else
        {
            file.create(new ByteArrayInputStream(xml), true, new NullProgressMonitor());
        }
        return file;
    }

    /**
     * Reads the file the workspace has, without refreshing it first.
     *
     * @param file the rights file
     * @return the text
     * @throws Exception when the workspace cannot open the file
     */
    private static String read(IFile file) throws Exception
    {
        try (InputStream in = file.getContents())
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * @return the role directory on disk
     */
    private static Path roleDir()
    {
        return projectDir.resolve("src").resolve("Roles").resolve(ROLE); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Creates and opens a project at {@code location}.
     *
     * @param name the project name
     * @param location the directory
     * @return the opened project
     * @throws Exception when the workspace refuses the project
     */
    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject opened = workspace.getRoot().getProject(name);
        if (opened.exists())
        {
            opened.delete(true, true, new NullProgressMonitor());
        }
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        opened.create(description, new NullProgressMonitor());
        opened.open(new NullProgressMonitor());
        opened.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        return opened;
    }

    /**
     * Deletes a directory tree.
     *
     * @param root the directory
     * @throws IOException when a walk cannot be opened
     */
    private static void deleteTree(Path root) throws IOException
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (var walk = Files.walk(root))
        {
            walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
