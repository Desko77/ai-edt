/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Which folder an owner FQN in Russian addresses on disk.
 * <p>
 * The folder name is English in an EDT project ({@code src/Catalogs/...}) while the FQN a caller
 * holds is in the configuration's own language ({@code Справочник.Товары}), so the type prefix has
 * to be translated. It used to be pluralized by appending an {@code s}, which turned an owner
 * nobody recognized into a folder named after the caller's word - {@code src/Справочникs/} - and
 * answered success while writing where no project keeps anything.
 * </p>
 */
public class ARussianOwnerFqnNamesTheEnglishFolderTest
{
    private static final String PROJECT = "AiEdtTemplateOwnerProbe"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    @BeforeClass
    public static void aProjectOutsideAnyRepository() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-template-owner"); //$NON-NLS-1$
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
    public void aRussianTypePrefixNamesTheEnglishCollection()
    {
        assertEquals("Catalogs", BmTemplateHelper.englishTypePlural("Справочник")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalogs", BmTemplateHelper.englishTypePlural("Справочники")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Documents", BmTemplateHelper.englishTypePlural("Документ")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Catalogs", BmTemplateHelper.englishTypePlural("catalog")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aTypePrefixNobodyKnowsIsRefusedRatherThanPluralized()
    {
        assertNull(BmTemplateHelper.englishTypePlural("Бананы")); //$NON-NLS-1$
        assertNull(BmTemplateHelper.englishTypePlural("Bananas")); //$NON-NLS-1$
        assertNull(BmTemplateHelper.englishTypePlural(null));

        assertNull(BmTemplateHelper.templateDirRelativePath("Бананы.Товары", "Макет")); //$NON-NLS-1$ //$NON-NLS-2$
        String refusal = BmTemplateHelper.templateDirRefusal("Бананы.Товары", "Макет"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull("an owner nobody recognizes has to be refused with a reason", refusal); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("names no metadata type")); //$NON-NLS-1$
    }

    @Test
    public void theOwnerFqnBuildsTheFolderInEnglish()
    {
        assertEquals(Path.of("src", "Catalogs", "Товары", "Templates", "Макет"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            BmTemplateHelper.templateDirRelativePath("Справочник.Товары", "Макет")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Path.of("src", "Documents", "Заказ", "Templates", "Печать"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            BmTemplateHelper.templateDirRelativePath("Документ.Заказ", "Печать")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Path.of("src", "Catalogs", "Товары", "Templates", "Макет"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            BmTemplateHelper.templateDirRelativePath("Catalog.Товары", "Макет")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aCommonTemplateIsTheWholeObjectWithoutAnOwnerFolder()
    {
        assertEquals(Path.of("src", "CommonTemplates", "Общий"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            BmTemplateHelper.templateDirRelativePath("ОбщийМакет.Общий", "Общий")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aWriteThroughARussianOwnerFqnLandsUnderTheEnglishFolder() throws Exception
    {
        String error = BmTemplateHelper.writeTextTemplateContent(project, "Справочник.Товары", //$NON-NLS-1$ //$NON-NLS-2$
            "Макет", "TextDocument", "текст"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertNull(error);
        Path written = projectDir.resolve("src").resolve("Catalogs").resolve("Товары") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .resolve("Templates").resolve("Макет").resolve("Template.txt"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(written.toString(), Files.exists(written));
        assertEquals("текст", //$NON-NLS-1$
            new String(Files.readAllBytes(written), StandardCharsets.UTF_8));
        assertFalse("the caller's own word is not a folder an EDT project has", //$NON-NLS-1$
            Files.exists(projectDir.resolve("src").resolve("Справочникs"))); //$NON-NLS-1$ //$NON-NLS-2$
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
