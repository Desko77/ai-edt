/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A Rights.rights that declares a DOCTYPE is refused. The entity is not expanded into the answer,
 * and the file bytes stay as they were.
 */
public class ARightsFileWithADoctypeIsRefusedNotReadTest
{
    private static final String PROJECT = "AiEdtRightsDoctypeProbe"; //$NON-NLS-1$

    private static final String ROLE = "Роль1"; //$NON-NLS-1$

    /** The entity text. It is in the file on purpose; the answer must not adopt it. */
    private static final String LEAKED = "LEAKED"; //$NON-NLS-1$

    private static final byte[] DOCTYPE = (""
        + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE Rights [<!ENTITY x \"" + LEAKED + "\">]>\n"
        + "<Rights>\n"
        + "  <object><name>&x;</name></object>\n"
        + "</Rights>\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static final byte[] EXTERNAL = (""
        + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE Rights SYSTEM \"rights.dtd\">\n"
        + "<Rights>\n"
        + "  <object><name>Catalog.Есть</name></object>\n"
        + "</Rights>\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static Path projectDir;

    private static IProject project;

    /**
     * Opens an empty project. Each test writes its own rights file.
     *
     * @throws Exception when the workspace cannot create the project
     */
    @BeforeClass
    public static void aPlainProject() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-rights-doctype"); //$NON-NLS-1$
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
     * A sweep of a file with an internal entity does not put the entity text in either list.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void aSweepRefusesADoctype() throws Exception
    {
        write(DOCTYPE);
        byte[] before = Files.readAllBytes(rightsFile());

        BmRightsHelper.OrphanSweep sweep = BmRightsHelper.sweepOrphanedRights(
            project, ROLE, fqn -> Boolean.FALSE, false);

        assertFalse(sweep.ok);
        assertNotNull(sweep.error);
        assertTrue(sweep.error.contains("could not be read")); //$NON-NLS-1$
        assertFalse(sweep.orphaned.contains(LEAKED));
        assertFalse(sweep.undecided.containsKey(LEAKED));
        assertArrayEquals(before, Files.readAllBytes(rightsFile()));
    }

    /**
     * An external DTD is a parse error, not a fetch.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void anExternalDtdIsRefused() throws Exception
    {
        write(EXTERNAL);
        byte[] before = Files.readAllBytes(rightsFile());

        BmRightsHelper.OrphanSweep sweep = BmRightsHelper.sweepOrphanedRights(
            project, ROLE, fqn -> Boolean.FALSE, false);

        assertFalse(sweep.ok);
        assertNotNull(sweep.error);
        assertTrue(sweep.error.contains("could not be read")); //$NON-NLS-1$
        assertTrue(sweep.orphaned.isEmpty());
        assertArrayEquals(before, Files.readAllBytes(rightsFile()));
    }

    /**
     * Granting a right parses the file before it looks the role up, so a DOCTYPE is still refused.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void aRightWriteRefusesADoctype() throws Exception
    {
        write(DOCTYPE);
        byte[] before = Files.readAllBytes(rightsFile());

        BmRightsHelper.FileRightResult written = BmRightsHelper.applyRightToFile(
            project, ROLE, "Catalog.Товары", "Read", true, false, //$NON-NLS-1$ //$NON-NLS-2$
            BmRightsHelper.RightsGate.allowAll());

        assertFalse(written.ok);
        assertNotNull(written.error);
        assertTrue(written.error.contains("could not be read")); //$NON-NLS-1$
        assertFalse(written.error.contains(LEAKED));
        assertArrayEquals(before, Files.readAllBytes(rightsFile()));
    }

    /**
     * A template write refuses the same document.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void aTemplateWriteRefusesADoctype() throws Exception
    {
        write(DOCTYPE);
        byte[] before = Files.readAllBytes(rightsFile());

        BmRightsHelper.FileTemplateResult written = BmRightsHelper.applyRestrictionTemplateToFile(
            project, ROLE, "ByOrg", "Org = &amp;Org", false, false, //$NON-NLS-1$ //$NON-NLS-2$
            BmRightsHelper.RightsGate.allowAll());

        assertFalse(written.ok);
        assertNotNull(written.error);
        assertTrue(written.error.contains("could not be read")); //$NON-NLS-1$
        assertArrayEquals(before, Files.readAllBytes(rightsFile()));
    }

    /**
     * A restriction write refuses the same document.
     *
     * @throws Exception when the fixture cannot be written
     */
    @Test
    public void aRestrictionWriteRefusesADoctype() throws Exception
    {
        write(DOCTYPE);
        byte[] before = Files.readAllBytes(rightsFile());

        BmRightsHelper.FileRestrictionResult written = BmRightsHelper.applyRoleRestrictionToFile(
            project, ROLE, "Catalog.Товары", "Read", "Org = &amp;Org", false, false, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            BmRightsHelper.RightsGate.allowAll());

        assertFalse(written.ok);
        assertNotNull(written.error);
        assertTrue(written.error.contains("could not be read")); //$NON-NLS-1$
        assertArrayEquals(before, Files.readAllBytes(rightsFile()));
    }

    /**
     * Replaces the role's rights file with {@code xml}.
     *
     * @param xml the bytes
     * @throws IOException when the file cannot be written
     */
    private static void write(byte[] xml) throws IOException
    {
        Path file = rightsFile();
        Files.createDirectories(file.getParent());
        Files.write(file, xml);
    }

    /**
     * @return the rights file of the probe role
     */
    private static Path rightsFile()
    {
        return projectDir.resolve("src").resolve("Roles").resolve(ROLE).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
