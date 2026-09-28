/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

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
 * What a template name that is not one path element does.
 * <p>
 * The name and the owner name were joined onto the project root as they arrived, so
 * {@code ../../../../..} in either of them placed the write wherever the caller pointed, and the
 * call answered success. Both are single path elements or they are refused: a separator of either
 * kind, a parent directory or a bare dot is not a name a template has.
 * </p>
 */
public class ANameThatLeavesTheTemplateFolderIsRefusedTest
{
    private static final String PROJECT = "AiEdtTemplateEscapeProbe"; //$NON-NLS-1$

    private static final String OWNER = "Catalog.Товары"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    @BeforeClass
    public static void aProjectOutsideAnyRepository() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-template-escape"); //$NON-NLS-1$
        project = openProject(PROJECT, projectDir);
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        deleteTree(projectDir);
    }

    @Test
    public void aTemplateNameThatIsNotOnePathElementIsRefused()
    {
        for (String name : new String[] { "..", ".", "../Побег", "..\\Побег", "Под/Побег", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "\\Побег", "Товары/../..", "" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertNull("'" + name + "' is not a template name", //$NON-NLS-1$ //$NON-NLS-2$
                BmTemplateHelper.templateDirRelativePath(OWNER, name));
            assertNotNull("'" + name + "' has to be refused with a reason", //$NON-NLS-1$ //$NON-NLS-2$
                BmTemplateHelper.templateDirRefusal(OWNER, name));
        }
    }

    @Test
    public void anOwnerNameThatIsNotOnePathElementIsRefused()
    {
        for (String owner : new String[] { "Catalog.Товары/../..", "Catalog.Товары\\..", "Catalog.", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ".Товары", "Товары", "", "Catalog." }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertNull("'" + owner + "' is not an owner FQN with a name", //$NON-NLS-1$ //$NON-NLS-2$
                BmTemplateHelper.templateDirRelativePath(owner, "Макет")); //$NON-NLS-1$
            assertNotNull("'" + owner + "' has to be refused with a reason", //$NON-NLS-1$ //$NON-NLS-2$
                BmTemplateHelper.templateDirRefusal(owner, "Макет")); //$NON-NLS-1$
        }
    }

    @Test
    public void aWriteThroughAnEscapingNameIsRefusedAndWritesNothing() throws Exception
    {
        String error = BmTemplateHelper.writeTextTemplateContent(project, OWNER, //$NON-NLS-1$
            "../../../../../../Побег", "TextDocument", "текст"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNotNull("a write outside the template folder cannot answer success", error); //$NON-NLS-1$
        assertTrue(error, error.contains("plain name")); //$NON-NLS-1$
        for (Path level : new Path[] { projectDir, projectDir.getParent() }) //$NON-NLS-1$
        {
            assertFalse("nothing was written at " + level, //$NON-NLS-1$
                Files.exists(level.resolve("Побег"))); //$NON-NLS-1$
        }
    }

    private static IProject openProject(String name, Path location) throws Exception
    {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject opened = workspace.getRoot().getProject(name);
        IProjectDescription description = workspace.newProjectDescription(name);
        description.setLocation(new org.eclipse.core.runtime.Path(location.toString()));
        opened.create(description, new NullProgressMonitor());
        opened.open(new NullProgressMonitor());
        opened.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        return opened;
    }

    private static void deleteTree(Path root) throws IOException
    {
        if (root == null)
        {
            return;
        }
        try (var walk = Files.walk(root))
        {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
