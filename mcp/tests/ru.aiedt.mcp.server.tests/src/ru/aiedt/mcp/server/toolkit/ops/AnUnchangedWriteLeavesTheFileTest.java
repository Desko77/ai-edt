/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * A write whose result is the text the module file already holds leaves the file untouched and
 * says so with {@code unchanged: true}; a write that changes the text writes the file as before.
 */
public class AnUnchangedWriteLeavesTheFileTest
{
    private static final String PROJECT = "AiEdtUnchangedProbe"; //$NON-NLS-1$

    private static final String MODULE_PATH = "Catalogs/Товары/ObjectModule.bsl"; //$NON-NLS-1$

    private static final String METHOD = "Процедура ПередЗаписью(Отказ)\r\n" //$NON-NLS-1$
        + "\tВозврат;\r\n" //$NON-NLS-1$
        + "КонецПроцедуры"; //$NON-NLS-1$

    private static final String MODULE_TEXT = "// Шапка модуля.\r\n\r\n" + METHOD + "\r\n"; //$NON-NLS-1$ //$NON-NLS-2$

    private static Path root;

    private static IProject project;

    private static Path moduleFile;

    /**
     * Creates a project holding one catalog with its object module.
     *
     * @throws Exception when the project or its files cannot be created
     */
    @BeforeClass
    public static void aProjectWithOneModule() throws Exception
    {
        root = Files.createTempDirectory("aiedt-unchanged-probe"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        moduleFile = projectDir.resolve("src/" + MODULE_PATH); //$NON-NLS-1$
        Files.createDirectories(moduleFile.getParent());
        Files.write(moduleFile, MODULE_TEXT.getBytes(StandardCharsets.UTF_8));
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Removes the project and its directory.
     *
     * @throws Exception when the project cannot be removed
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (project != null && project.exists())
        {
            project.delete(false, true, new NullProgressMonitor());
        }
        if (root != null)
        {
            try (var walk = Files.walk(root))
            {
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile)
                    .forEach(java.io.File::delete);
            }
        }
    }

    /**
     * Puts the module back to its first text before each case.
     *
     * @throws Exception when the file cannot be written
     */
    @Before
    public void theModuleHoldsItsFirstText() throws Exception
    {
        Files.write(moduleFile, MODULE_TEXT.getBytes(StandardCharsets.UTF_8));
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /** replaceMethod with the method's own text leaves the file and says it is unchanged. */
    @Test
    public void theSameMethodTextDoesNotRewriteTheFile()
    {
        long stamp = module().getModificationStamp();

        String answer = write("replaceMethod", "ПередЗаписью", null, METHOD); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("unchanged: true")); //$NON-NLS-1$
        assertEquals("the file was not written", stamp, module().getModificationStamp()); //$NON-NLS-1$
    }

    /** searchReplace that puts back the very fragment it found leaves the file as well. */
    @Test
    public void aFragmentSwappedForItselfDoesNotRewriteTheFile()
    {
        long stamp = module().getModificationStamp();

        String answer = write("searchReplace", null, "\tВозврат;", "\tВозврат;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(answer, answer.contains("unchanged: true")); //$NON-NLS-1$
        assertEquals("the file was not written", stamp, module().getModificationStamp()); //$NON-NLS-1$
    }

    /** A write that changes the text writes the file and carries no unchanged mark. */
    @Test
    public void aChangedTextIsWritten() throws Exception
    {
        long stamp = module().getModificationStamp();

        String answer = write("replaceMethod", "ПередЗаписью", null, //$NON-NLS-1$ //$NON-NLS-2$
            "Процедура ПередЗаписью(Отказ)\r\n\tОтказ = Ложь;\r\nКонецПроцедуры"); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("unchanged")); //$NON-NLS-1$
        assertNotEquals("the file was written", stamp, module().getModificationStamp()); //$NON-NLS-1$
        assertTrue(Files.readString(moduleFile, StandardCharsets.UTF_8).contains("Отказ = Ложь;")); //$NON-NLS-1$
    }

    /**
     * @return the module file as the workspace holds it
     */
    private static IFile module()
    {
        return project.getFile("src/" + MODULE_PATH); //$NON-NLS-1$
    }

    /**
     * Writes into the module.
     *
     * @param mode the write mode
     * @param methodName the method for replaceMethod, or {@code null}
     * @param oldSource the fragment for searchReplace, or {@code null}
     * @param source the text to write
     * @return the answer
     */
    private static String write(String mode, String methodName, String oldSource, String source)
    {
        Map<String, String> args = new HashMap<>();
        args.put("projectName", PROJECT); //$NON-NLS-1$
        args.put("modulePath", MODULE_PATH); //$NON-NLS-1$
        args.put("mode", mode); //$NON-NLS-1$
        args.put("source", source); //$NON-NLS-1$
        args.put("validateAfterWrite", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        if (methodName != null)
        {
            args.put("methodName", methodName); //$NON-NLS-1$
        }
        if (oldSource != null)
        {
            args.put("oldSource", oldSource); //$NON-NLS-1$
        }
        return new ModuleSourceWriter().execute(args);
    }
}
