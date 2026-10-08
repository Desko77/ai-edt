/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.YamlFrontMatter;

/**
 * A module path that resolves to no file is reported, not swallowed.
 * <p>
 * A missing path and an object without a single module used to print the same "No BSL modules
 * found." - a caller with a mistyped path read that their object has no code. The requested path
 * that found no file is now named in the answer.
 * </p>
 */
public class AMissingModulePathIsSaidNotSwallowedTest
{
    private static final String PROJECT = "AiEdtMissingModulePath"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    /** A temporary plain project the module paths resolve against. */
    @BeforeClass
    public static void aTemporaryProject() throws Exception
    {
        root = Files.createTempDirectory("aiedt-missing-path"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src/CommonModules/Real")); //$NON-NLS-1$
        Files.write(projectDir.resolve("src/CommonModules/Real/Module.bsl"), //$NON-NLS-1$
            "// a line\n// another line\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject existing = workspace.getRoot().getProject(PROJECT);
        if (existing.exists())
        {
            existing.delete(true, true, new NullProgressMonitor());
        }
        project = workspace.getRoot().getProject(PROJECT);
        org.eclipse.core.resources.IProjectDescription description =
            workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    /** Removes the project and the directory it was created in. */
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
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /** A path no file sits under is recorded as missing, and nothing pretends a module was found. */
    @Test
    public void aMissingPathIsRecorded()
    {
        List<AiContextTool.ModuleInfo> modules = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        new AiContextTool().discoverSingleModule(project, modules, "CommonModules/Nope/Module.bsl", //$NON-NLS-1$
            missing);
        assertTrue("no module is invented for a missing path", modules.isEmpty()); //$NON-NLS-1$
        assertEquals("the missing path is named", 1, missing.size()); //$NON-NLS-1$
        assertEquals("CommonModules/Nope/Module.bsl", missing.get(0)); //$NON-NLS-1$
    }

    /** A path that does sit on a file finds the module and records nothing missing. */
    @Test
    public void anExistingPathFindsTheModule() throws Exception
    {
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        List<AiContextTool.ModuleInfo> modules = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        new AiContextTool().discoverSingleModule(project, modules, "CommonModules/Real/Module.bsl", //$NON-NLS-1$
            missing);
        assertEquals(1, modules.size());
        assertTrue(missing.isEmpty());
    }

    /** The answer names the missing path instead of claiming the object has no modules. */
    @Test
    public void aMissingPathIsSaidInPlaceOfNoModules()
    {
        StringBuilder md = new StringBuilder();
        new AiContextTool().appendModulesList(md, new ArrayList<>(), YamlFrontMatter.create(),
            List.of("CommonModules/Nope/Module.bsl")); //$NON-NLS-1$
        assertTrue("the answer names the missing path", //$NON-NLS-1$
            md.indexOf("CommonModules/Nope/Module.bsl") >= 0); //$NON-NLS-1$
        assertFalse("it does not read as an object without modules", //$NON-NLS-1$
            md.indexOf("No BSL modules found.") >= 0); //$NON-NLS-1$
    }

    /** An object with genuinely no modules still says so, without any missing path. */
    @Test
    public void anEmptyObjectStillSaysNoModules()
    {
        StringBuilder md = new StringBuilder();
        new AiContextTool().appendModulesList(md, new ArrayList<>(), YamlFrontMatter.create(),
            new ArrayList<>());
        assertTrue(md.indexOf("No BSL modules found.") >= 0); //$NON-NLS-1$
        assertFalse("no path is named missing", md.indexOf("not found") >= 0); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
