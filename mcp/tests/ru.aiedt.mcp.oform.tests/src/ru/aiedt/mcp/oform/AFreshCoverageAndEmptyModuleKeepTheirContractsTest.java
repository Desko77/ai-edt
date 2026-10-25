/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

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
 * The two contracts around a project's ordinary forms that a change to the files must not break:
 * a module that is empty is no change to write, and the coverage of a project says what it holds
 * now, not what it held half a minute ago.
 * <p>
 * The project is a workspace project over a synthetic temporary directory that starts with an
 * empty {@code src}. Each test builds the forms it needs on disk, refreshes the project, and
 * takes them away again in a finally, so neither contract sees the other's files and no real
 * project is touched. The coverage test never resets the cache and never waits: the counts it
 * asserts are the fresh facts of the moment, whatever holds them.
 * </p>
 */
public class AFreshCoverageAndEmptyModuleKeepTheirContractsTest
{
    private static final String PROJECT = "AiEdtOformContractsProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String PICKER = "CommonForms/Picker/Form.oform"; //$NON-NLS-1$

    private static final String PICKER_MODULE = "CommonForms/Picker/Module.bsl"; //$NON-NLS-1$

    private static final String EXTRA = "CommonForms/Extra/Form.oform"; //$NON-NLS-1$

    private static final String LAYOUT = "{27," + CRLF //$NON-NLS-1$
        + "{1}" + CRLF //$NON-NLS-1$
        + "}"; //$NON-NLS-1$

    private static final String MODULE = "Процедура Выбрать()" + CRLF //$NON-NLS-1$
        + "КонецПроцедуры" + CRLF; //$NON-NLS-1$

    private static Path root;

    private static Path src;

    private static IProject project;

    /**
     * Opens a workspace project over a temporary directory with an empty {@code src}, so both
     * contracts start from a project that holds no ordinary form.
     */
    @BeforeClass
    public static void aProjectWithAnEmptySrc() throws Exception
    {
        root = Files.createTempDirectory("aiedt-oform-contracts"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        src = projectDir.resolve("src"); //$NON-NLS-1$
        Files.createDirectories(src);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    /**
     * Closes the project without deleting its content and removes the temporary tree.
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
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /**
     * A module that is empty, written back as the lines it was read as, is no change: the write
     * answers false, the module stays empty, and the container keeps its bytes and its timestamp.
     */
    @Test
    public void anEmptyModuleWrittenBackLeavesTheContainerAlone() throws Exception
    {
        try
        {
            writeForm(PICKER, ""); //$NON-NLS-1$

            OrdinaryFormModule module = OrdinaryFormModule.locate(project, PICKER_MODULE);
            assertNotNull("a container with an empty module is an ordinary form module", module); //$NON-NLS-1$
            List<String> lines = module.lines();
            assertTrue("an empty module reads as no lines", lines.isEmpty()); //$NON-NLS-1$

            byte[] bytes = Files.readAllBytes(src.resolve(PICKER));
            long stamp = Files.getLastModifiedTime(src.resolve(PICKER)).toMillis();
            assertFalse("an empty module written back unchanged is no change to write", //$NON-NLS-1$
                module.write(lines));

            assertEquals("the module stays empty", //$NON-NLS-1$
                "", OrdinaryFormModule.locate(project, PICKER_MODULE).text()); //$NON-NLS-1$
            assertArrayEquals("the container keeps its bytes", bytes, Files.readAllBytes(src.resolve(PICKER))); //$NON-NLS-1$
            assertEquals("the container keeps its timestamp", stamp, //$NON-NLS-1$
                Files.getLastModifiedTime(src.resolve(PICKER)).toMillis());
        }
        finally
        {
            removeForm(PICKER);
        }
    }

    /**
     * The coverage is asked once with nothing there and then again as forms arrive and go: every
     * answer is the fact of that moment - the count of the forms the locator walks and a
     * statement once there is one - with no reset in between and no waiting.
     */
    @Test
    public void theCoverageSaysWhatTheProjectHoldsAsFormsComeAndGo() throws Exception
    {
        try
        {
            removeForm(PICKER);
            removeForm(EXTRA);
            assertEquals("an empty src counts no forms", 0, OrdinaryFormCoverage.count(project)); //$NON-NLS-1$
            assertNull("an empty src has no coverage statement", //$NON-NLS-1$
                OrdinaryFormCoverage.statement(project, "references")); //$NON-NLS-1$

            writeForm(PICKER, MODULE);
            assertEquals("the locator sees the first form the moment it is there", 1, //$NON-NLS-1$
                OrdinaryFormLocator.locate(project).size());
            assertEquals("the count is fresh the moment the first form is there", 1, //$NON-NLS-1$
                OrdinaryFormCoverage.count(project));
            String statement = OrdinaryFormCoverage.statement(project, "references"); //$NON-NLS-1$
            assertNotNull("one form is one module the index does not see", statement); //$NON-NLS-1$

            writeForm(EXTRA, MODULE);
            assertEquals("the count follows a second form arriving", 2, OrdinaryFormCoverage.count(project)); //$NON-NLS-1$

            removeForm(EXTRA);
            assertEquals("the count follows the last form going away", 1, OrdinaryFormCoverage.count(project)); //$NON-NLS-1$

            removeForm(PICKER);
            assertEquals("removing every form leaves an empty count", 0, OrdinaryFormCoverage.count(project)); //$NON-NLS-1$
            assertNull("removing every form removes the coverage statement", //$NON-NLS-1$
                OrdinaryFormCoverage.statement(project, "references")); //$NON-NLS-1$
        }
        finally
        {
            removeForm(PICKER);
            removeForm(EXTRA);
        }
    }

    /**
     * Writes a form container with the given module text and tells the workspace about it.
     *
     * @param container the src-relative path of the {@code Form.oform}, forward slashes
     * @param module the module text; empty for a form without a line of code
     */
    private static void writeForm(String container, String module) throws Exception
    {
        Path file = src.resolve(container);
        Files.createDirectories(file.getParent());
        Files.write(file, container(LAYOUT, module));
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Takes a form's directory away and tells the workspace about it, so the next look at the
     * project starts from what is really on disk.
     *
     * @param container the src-relative path of the {@code Form.oform}, forward slashes
     */
    private static void removeForm(String container) throws Exception
    {
        Path file = src.resolve(container);
        if (Files.exists(file))
        {
            Files.delete(file);
        }
        if (file.getParent() != null && Files.isDirectory(file.getParent()))
        {
            try (var walk = Files.walk(file.getParent()))
            {
                if (walk.filter(p -> !p.equals(file.getParent())).count() == 0)
                {
                    Files.delete(file.getParent());
                }
            }
        }
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /**
     * Builds the bytes of a two-entry form container: the layout and the module text, each with
     * the UTF-8 byte-order mark the platform writes.
     *
     * @param form the layout text
     * @param module the module text
     * @return the container bytes
     */
    private static byte[] container(String form, String module)
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom(form))); //$NON-NLS-1$
        container.add(V8Container.Entry.of("module", withBom(module))); //$NON-NLS-1$
        return container.write();
    }

    /**
     * Prefixes the UTF-8 bytes of a text with the byte-order mark.
     *
     * @param text the text
     * @return its bytes with the mark in front
     */
    private static byte[] withBom(String text)
    {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte)0xEF;
        out[1] = (byte)0xBB;
        out[2] = (byte)0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }
}
