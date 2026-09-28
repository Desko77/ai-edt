/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * A right is written only when the configuration can be read and has the role, and a second call
 * that spells the same object or right in another case updates the block already there.
 */
public class ARightIsOnlyWrittenForARoleThatExistsTest
{
    private static final String PROJECT = "AiEdtRoleRightsProbe"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    /**
     * Opens a plain workspace project that is not a configuration, so the model answers that the
     * role is not there.
     *
     * @throws Exception when the workspace cannot create the project
     */
    @BeforeClass
    public static void aPlainProject() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-role-rights"); //$NON-NLS-1$
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
     * A plain project has no readable configuration. That is not a missing role: the role may
     * exist, and the answer must not say it was looked up and found absent.
     */
    @Test
    public void anUnreadableConfigurationIsNotAMissingRole()
    {
        BmRightsHelper.FileRightResult written = BmRightsHelper.applyRightToFile(
            project, "ПолныеПраваИ", "Catalog.Товары", "Read", true, false); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse(written.ok);
        assertFalse(written.fileCreated);
        assertNotNull(written.error);
        assertTrue(written.error.contains("configuration not readable")); //$NON-NLS-1$
        assertEquals(null, written.failureKind);
        assertFalse(Files.exists(rightsFile("ПолныеПраваИ"))); //$NON-NLS-1$
    }

    /**
     * A role name that contains a dot is not a simple name, so it does not become a directory.
     */
    @Test
    public void aRoleNameWithADotIsRefused()
    {
        BmRightsHelper.FileRightResult written = BmRightsHelper.applyRightToFile(
            project, "Роль.ПолныеПрава", "Catalog.Товары", "Read", true, false, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            BmRightsHelper.RightsGate.allowAll());

        assertFalse(written.ok);
        assertNotNull(written.error);
        assertTrue(written.error.contains("simple role name")); //$NON-NLS-1$
        assertFalse(Files.exists(projectDir.resolve("src").resolve("Roles").resolve("Роль.ПолныеПрава"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * A call that only changes the case of an object or a right updates the block already stored.
     */
    @Test
    public void aRecasedNameUpdatesTheBlockAlreadyThere() throws Exception
    {
        BmRightsHelper.RightsGate allow = BmRightsHelper.RightsGate.allowAll();
        BmRightsHelper.FileRightResult first = BmRightsHelper.applyRightToFile(
            project, "Роль1", "Catalog.Товары", "Read", true, false, allow); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(first.error == null ? "written" : first.error, first.ok); //$NON-NLS-1$

        BmRightsHelper.FileRightResult second = BmRightsHelper.applyRightToFile(
            project, "Роль1", "catalog.товары", "read", false, false, allow); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(second.error == null ? "updated" : second.error, second.ok); //$NON-NLS-1$
        assertFalse(second.objectCreated);
        assertFalse(second.rightCreated);
        assertEquals("true", second.previousValue); //$NON-NLS-1$
        String text = Files.readString(rightsFile("Роль1"), StandardCharsets.UTF_8); //$NON-NLS-1$
        assertEquals(1, count(text, "<object>")); //$NON-NLS-1$
        assertEquals(1, count(text, "<right>")); //$NON-NLS-1$
        assertTrue(text.contains("Catalog.Товары")); //$NON-NLS-1$
        assertTrue(text.contains(">false<")); //$NON-NLS-1$
    }

    /**
     * Counts non-overlapping occurrences of {@code needle}.
     *
     * @param text the text
     * @param needle the fragment
     * @return how many times it occurs
     */
    private static int count(String text, String needle)
    {
        int found = 0;
        int at = 0;
        while (at >= 0)
        {
            at = text.indexOf(needle, at);
            if (at < 0)
            {
                break;
            }
            found++;
            at += needle.length();
        }
        return found;
    }

    /**
     * The rights file of one role.
     *
     * @param roleName the simple role name
     * @return the path
     */
    private static Path rightsFile(String roleName)
    {
        return projectDir.resolve("src").resolve("Roles").resolve(roleName).resolve("Rights.rights"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
