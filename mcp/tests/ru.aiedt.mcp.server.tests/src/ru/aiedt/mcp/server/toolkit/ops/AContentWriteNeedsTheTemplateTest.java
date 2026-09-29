/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What set_template_content does with a template name that stands for no template.
 * <p>
 * With an explicit templateType the operation took the caller's word for the template's existence
 * and made the folder on the way - {@code src/<Type>/<Owner>/Templates/<Name>/Template.txt} - so a
 * mistyped name left a template file behind a name nothing declares, and the call answered success.
 * The template's folder is what a template that exists has (measured on a real configuration: the
 * declaration lives in the owner's {@code .mdo} and the folder holds the content), so the write is
 * refused when there is no folder, and refused again when the folder holds another format - a text
 * file beside a spreadsheet template is not a template EDT can open.
 * </p>
 */
public class AContentWriteNeedsTheTemplateTest
{
    private static final String PROJECT = "AiEdtTemplateContentProbe"; //$NON-NLS-1$

    private static final String OWNER = "Catalog.Товары"; //$NON-NLS-1$

    private static Path projectDir;

    private static IProject project;

    @BeforeClass
    public static void aProjectOutsideAnyRepository() throws Exception
    {
        projectDir = Files.createTempDirectory("aiedt-template-content"); //$NON-NLS-1$
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
    public void aWriteToATemplateThatIsNotThereIsRefusedAndCreatesNothing()
    {
        String answer = setContent("Missing", "TextDocument"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("a template that stands for no folder cannot be written into: " + answer, //$NON-NLS-1$
            succeeded(answer));
        assertTrue(answer, answer.contains("does not exist")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("add_template")); //$NON-NLS-1$
        assertFalse("nothing was made on the way to a template that is not there", //$NON-NLS-1$
            Files.exists(templateDir("Missing"))); //$NON-NLS-1$
    }

    @Test
    public void aWriteToATemplateThatIsThereLandsInItsFile() throws Exception
    {
        Path dir = templateDir("Present"); //$NON-NLS-1$
        Files.createDirectories(dir);
        Files.write(dir.resolve("Template.txt"), "старое".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        String answer = setContent("Present", "TextDocument"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, succeeded(answer));
        assertEquals("новое", //$NON-NLS-1$
            new String(Files.readAllBytes(dir.resolve("Template.txt")), StandardCharsets.UTF_8)); //$NON-NLS-1$
    }

    @Test
    public void aTextWriteIntoATemplateOfAnotherFormatIsRefused() throws Exception
    {
        Path dir = templateDir("Spreadsheet"); //$NON-NLS-1$
        Files.createDirectories(dir);
        Files.write(dir.resolve("Template.mxlx"), new byte[0]); //$NON-NLS-1$
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        String answer = setContent("Spreadsheet", "TextDocument"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse("a spreadsheet template is not made to hold text as well: " + answer, //$NON-NLS-1$
            succeeded(answer));
        assertTrue(answer, answer.contains("Template.mxlx")); //$NON-NLS-1$
        assertFalse("no text file was added beside the spreadsheet content", //$NON-NLS-1$
            Files.exists(dir.resolve("Template.txt"))); //$NON-NLS-1$
    }

    @Test
    public void aTemplateNameThatIsNotAPlainNameIsRefused()
    {
        String answer = setContent("../Побег", "TextDocument"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(answer, succeeded(answer));
        assertTrue(answer, answer.contains("plain name")); //$NON-NLS-1$
        assertFalse("nothing was written for a name that is not a name", //$NON-NLS-1$
            Files.exists(projectDir.getParent().resolve("Побег"))); //$NON-NLS-1$
    }

    /** Runs set_template_content over the fixture project. */
    private static String setContent(String templateName, String templateType)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", PROJECT); //$NON-NLS-1$
        params.put("ownerFqn", OWNER); //$NON-NLS-1$
        params.put("name", templateName); //$NON-NLS-1$
        params.put("templateType", templateType); //$NON-NLS-1$
        params.put("content", "новое"); //$NON-NLS-1$
        return new TemplateOps().opSetTemplateContent(params);
    }

    /** Where the fixture project keeps a template of its owner. */
    private static Path templateDir(String templateName)
    {
        return projectDir.resolve("src").resolve("Catalogs").resolve("Товары") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .resolve("Templates").resolve(templateName); //$NON-NLS-1$
    }

    private static boolean succeeded(String answer)
    {
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        return json.has("success") && json.get("success").getAsBoolean(); //$NON-NLS-1$
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
            walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
