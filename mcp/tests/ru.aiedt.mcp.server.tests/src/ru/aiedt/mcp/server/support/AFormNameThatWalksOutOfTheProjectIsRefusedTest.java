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
 * What a form name that is not one path element does.
 * <p>
 * The owner name and the form name are joined onto the project root to address the form's folder,
 * so a name standing for a parent directory or carrying a separator places {@code Form.form} and
 * {@code Module.bsl} outside the object they belong to - measured on a live project, a
 * {@code formName} of {@code ../TraversalProbe} wrote the files beside {@code Forms/} and the object
 * stopped resolving. Both names are single path elements or the write is refused before anything
 * reaches the disk.
 * </p>
 */
public class AFormNameThatWalksOutOfTheProjectIsRefusedTest
{
    private static final String PROJECT = "AiEdtFormEscapeProbe"; //$NON-NLS-1$

    private static final String OWNER = "Catalog.Товары"; //$NON-NLS-1$

    private static final String FORM = "ФормаЭлемента"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    @BeforeClass
    public static void aProjectOutsideAnyRepository() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-form-escape"); //$NON-NLS-1$
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
    public void aFormNameThatIsNotOnePathElementIsRefused()
    {
        for (String name : new String[] { "..", ".", "../Побег", "..\\Побег", "Под/Побег", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "\\Побег", "Форма/../..", "" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertNull("'" + name + "' is not a form name", //$NON-NLS-1$ //$NON-NLS-2$
                BmFormResourceHelper.formDirRelativePath(OWNER, name));
            assertNotNull("'" + name + "' has to be refused with a reason", //$NON-NLS-1$ //$NON-NLS-2$
                BmFormResourceHelper.formDirRefusal(OWNER, name));
        }
    }

    @Test
    public void anOwnerNameThatIsNotOnePathElementIsRefused()
    {
        for (String owner : new String[] { "Catalog.Товары/../..", "Catalog.Товары\\..", "Catalog.", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ".Товары", "Товары", "" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertNull("'" + owner + "' is not an owner FQN with a name", //$NON-NLS-1$ //$NON-NLS-2$
                BmFormResourceHelper.formDirRelativePath(owner, FORM));
            assertNotNull("'" + owner + "' has to be refused with a reason", //$NON-NLS-1$ //$NON-NLS-2$
                BmFormResourceHelper.formDirRefusal(owner, FORM));
        }
    }

    /**
     * The positive control: a name with no separator still resolves where the form belongs, in both
     * spellings of the owner type. A rule that refused everything would pass the checks above.
     */
    @Test
    public void aPlainNameResolvesToTheFormsFolder()
    {
        assertEquals(Path.of("src", "Catalogs", "Товары", "Forms", FORM), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            BmFormResourceHelper.formDirRelativePath("Catalog.Товары", FORM)); //$NON-NLS-1$
        assertEquals(Path.of("src", "Catalogs", "Товары", "Forms", FORM), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            BmFormResourceHelper.formDirRelativePath("Справочник.Товары", FORM)); //$NON-NLS-1$
        assertNull(BmFormResourceHelper.formDirRefusal("Catalog.Товары", FORM)); //$NON-NLS-1$
    }

    /** A common form is the whole object: no owner folder and no Forms level above it. */
    @Test
    public void aCommonFormResolvesToItsOwnFolder()
    {
        assertEquals(Path.of("src", "CommonForms", "МояФорма"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            BmFormResourceHelper.formDirRelativePath("CommonForm.МояФорма", "МояФорма")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** An owner type nobody recognizes names no folder, and says so rather than pointing at a guess. */
    @Test
    public void anUnknownOwnerTypeIsRefused()
    {
        assertNull(BmFormResourceHelper.formDirRelativePath("Бананы.Товары", FORM)); //$NON-NLS-1$
        String refusal = BmFormResourceHelper.formDirRefusal("Бананы.Товары", FORM); //$NON-NLS-1$
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("no metadata type")); //$NON-NLS-1$
    }

    @Test
    public void aWriteThroughAnEscapingNameIsRefusedAndWritesNothing() throws Exception
    {
        String error = BmFormResourceHelper.writeEmptyFormResources(project, OWNER, //$NON-NLS-1$
            "../../../../TraversalProbe"); //$NON-NLS-1$

        assertNotNull("a write outside the form folder cannot answer success", error); //$NON-NLS-1$
        assertTrue(error, error.contains("plain name")); //$NON-NLS-1$
        for (Path level : new Path[] { projectDir, projectDir.getParent() })
        {
            assertFalse("nothing was written at " + level, //$NON-NLS-1$
                Files.exists(level.resolve("TraversalProbe"))); //$NON-NLS-1$
        }
    }

    /** The same for the module-only writer the generator path uses. */
    @Test
    public void aModuleWriteThroughAnEscapingNameIsRefused() throws Exception
    {
        String error = BmFormResourceHelper.writeModuleResourceOnly(project, OWNER, //$NON-NLS-1$
            "../TraversalProbeModule"); //$NON-NLS-1$

        assertNotNull(error);
        assertTrue(error, error.contains("plain name")); //$NON-NLS-1$
        assertFalse(Files.exists(projectDir.resolve("src").resolve("Catalogs") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .resolve("Товары").resolve("TraversalProbeModule"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A plain name still writes both files, and inside the project. */
    @Test
    public void aPlainNameWritesTheFilesWhereTheFormLives() throws Exception
    {
        assertNull(BmFormResourceHelper.writeEmptyFormResources(project, OWNER, FORM));

        Path formDir = projectDir.resolve("src").resolve("Catalogs").resolve("Товары") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .resolve("Forms").resolve(FORM); //$NON-NLS-1$
        assertTrue("Form.form has to be created", Files.exists(formDir.resolve("Form.form"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("Module.bsl has to be created", Files.exists(formDir.resolve("Module.bsl"))); //$NON-NLS-1$ //$NON-NLS-2$
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
