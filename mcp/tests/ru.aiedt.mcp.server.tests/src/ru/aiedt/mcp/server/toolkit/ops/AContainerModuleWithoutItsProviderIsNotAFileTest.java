/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

import ru.aiedt.mcp.server.support.modules.ModuleSources;

/**
 * A module of a form kept in a {@code Form.oform} container is an address a module source
 * provider owns, and with no provider registered the write of that module is refused instead of
 * leaving a {@code Module.bsl} file beside the container that no form reads.
 * <p>
 * The probe project holds one catalog with a form folder per probe, and every probe writes
 * through the public {@code write_module_source} entry of {@link ModuleSourceWriter}. The
 * {@code Form.oform} entry of a probe folder is a short unparsed sentinel record: this suite
 * proves the absence of a provider and the file route of the write, not the format of the form
 * container, so the sentinel only has to keep its exact bytes. No provider is registered for the
 * probe project, and every negative probe asks {@link ModuleSources#locate} first and expects
 * {@code null}, so a provider another test left in the registry is a setup error rather than a
 * passing probe.
 * </p>
 * <p>
 * The append, the replace and the dry run of such a module are refused with the container and the
 * absent provider named, and the refusal is proven on the disk beside the answer: the module file
 * never appears and the container keeps its bytes. A form folder without a {@code Form.oform}
 * container holds a real file module, and its write stays allowed.
 * </p>
 */
public class AContainerModuleWithoutItsProviderIsNotAFileTest
{
    private static final String PROJECT = "AiEdtAbsentProviderProbe"; //$NON-NLS-1$

    private static final String FORMS = "src/Catalogs/Products/Forms"; //$NON-NLS-1$

    private static final String SOURCE =
        "// Exercise the standalone-file write path.\n" //$NON-NLS-1$
            + "Procedure ProbeWrite()\n\tReturn;\nEndProcedure"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    private static Path projectDir;

    /**
     * Creates an open project of one catalog with its Forms folder and nothing in it: every probe
     * adds the form folder of its own, so no probe reads what another wrote.
     *
     * @throws Exception when the project or its folder cannot be created
     */
    @BeforeClass
    public static void aProjectOfOneCatalogWithItsFormsFolder() throws Exception
    {
        root = Files.createTempDirectory("aiedt-absent-provider"); //$NON-NLS-1$
        projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve(FORMS));
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
     * An append into a form kept in a container no provider holds is refused, and the disk keeps
     * what it held: no module file appears beside the container and the container keeps its exact
     * bytes.
     *
     * @throws Exception when the probe folder cannot be created or read back
     */
    @Test
    public void anAppendWithoutAProviderIsRefusedAndTheContainerKeepsItsBytes() throws Exception
    {
        byte[] sentinel = sentinel("append"); //$NON-NLS-1$
        Path form = aFormFolder("AppendProbe", sentinel); //$NON-NLS-1$
        String address = moduleAddressOf("AppendProbe"); //$NON-NLS-1$

        assertNull("a provider must hold nothing before the probe", //$NON-NLS-1$
            ModuleSources.locate(project, address));

        String answer = write("append", address, null); //$NON-NLS-1$

        assertHoldsExactly("the container kept its exact bytes", sentinel, //$NON-NLS-1$
            form.resolve("Form.oform")); //$NON-NLS-1$
        assertFalse("no standalone module file may appear: " + answer, //$NON-NLS-1$
            Files.exists(form.resolve("Module.bsl"))); //$NON-NLS-1$
        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Form.oform")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("provider")); //$NON-NLS-1$
    }

    /**
     * A replace of a form module no provider holds is refused on a form of its own, in the same
     * state the append probe started from, and the disk keeps what it held.
     *
     * @throws Exception when the probe folder cannot be created or read back
     */
    @Test
    public void aReplaceWithoutAProviderIsRefusedOnItsOwnForm() throws Exception
    {
        byte[] sentinel = sentinel("replace"); //$NON-NLS-1$
        Path form = aFormFolder("ReplaceProbe", sentinel); //$NON-NLS-1$
        String address = moduleAddressOf("ReplaceProbe"); //$NON-NLS-1$

        assertNull("a provider must hold nothing before the probe", //$NON-NLS-1$
            ModuleSources.locate(project, address));

        String answer = write("replace", address, null); //$NON-NLS-1$

        assertHoldsExactly("the container kept its exact bytes", sentinel, //$NON-NLS-1$
            form.resolve("Form.oform")); //$NON-NLS-1$
        assertFalse("no standalone module file may appear: " + answer, //$NON-NLS-1$
            Files.exists(form.resolve("Module.bsl"))); //$NON-NLS-1$
        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Form.oform")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("provider")); //$NON-NLS-1$
    }

    /**
     * A dry run of a form module no provider holds answers the refusal a real write would get,
     * not a preview of the file it would leave.
     *
     * @throws Exception when the probe folder cannot be created or read back
     */
    @Test
    public void aPreviewWithoutAProviderIsRefusedInsteadOfShown() throws Exception
    {
        byte[] sentinel = sentinel("preview"); //$NON-NLS-1$
        Path form = aFormFolder("PreviewProbe", sentinel); //$NON-NLS-1$
        String address = moduleAddressOf("PreviewProbe"); //$NON-NLS-1$

        assertNull("a provider must hold nothing before the probe", //$NON-NLS-1$
            ModuleSources.locate(project, address));

        String answer = write("append", address, "true"); //$NON-NLS-1$ //$NON-NLS-2$

        assertHoldsExactly("the container kept its exact bytes", sentinel, //$NON-NLS-1$
            form.resolve("Form.oform")); //$NON-NLS-1$
        assertFalse("a preview must not create a module file", //$NON-NLS-1$
            Files.exists(form.resolve("Module.bsl"))); //$NON-NLS-1$
        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertFalse(answer, answer.contains("status: preview")); //$NON-NLS-1$
    }

    /**
     * A form folder that holds no container takes a real file module: the write succeeds, and the
     * physical file carries the text the probe sent. This says nothing of the infobase - the
     * module file on disk is the whole of what is claimed here.
     *
     * @throws Exception when the probe folder cannot be created or read back
     */
    @Test
    public void aFormFolderWithoutAContainerStillTakesAModuleFile() throws Exception
    {
        Path form = aFormFolder("ManagedProbe", null); //$NON-NLS-1$
        String address = moduleAddressOf("ManagedProbe"); //$NON-NLS-1$
        Path module = form.resolve("Module.bsl"); //$NON-NLS-1$

        assertTrue("the probe starts with no module file", !module.toFile().exists()); //$NON-NLS-1$
        assertNull("a provider must hold nothing before the probe", //$NON-NLS-1$
            ModuleSources.locate(project, address));

        String answer = write("append", address, null); //$NON-NLS-1$

        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue("the module file is on disk", module.toFile().isFile()); //$NON-NLS-1$
        String text = moduleText(module);
        assertEquals(SOURCE + "\n", text); //$NON-NLS-1$
    }

    /**
     * Creates the form folder of one probe under the catalog's Forms folder, with its
     * {@code Form.oform} sentinel when the probe wants a container, and makes the folder known to
     * the workspace.
     *
     * @param formName the form folder name
     * @param sentinel the exact bytes of the {@code Form.oform} entry, or {@code null} for a
     *            folder with no container
     * @return the form folder on disk
     * @throws Exception when the folder or the sentinel cannot be created or refreshed
     */
    private static Path aFormFolder(String formName, byte[] sentinel) throws Exception
    {
        Path form = projectDir.resolve(FORMS).resolve(formName);
        Files.createDirectories(form);
        if (sentinel != null)
        {
            Files.write(form.resolve("Form.oform"), sentinel); //$NON-NLS-1$
        }
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        return form;
    }

    /**
     * The module address of one form folder of the probe catalog.
     *
     * @param formName the form folder name
     * @return the module path under {@code src/}
     */
    private static String moduleAddressOf(String formName)
    {
        return "Catalogs/Products/Forms/" + formName + "/Module.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The exact bytes of one {@code Form.oform} sentinel.
     * <p>
     * The entry is a short unparsed record: this suite proves the absence of a provider and the
     * file route of the write, not the format of the form container, so the bytes only have to be
     * the probe's own and to stay unchanged.
     * </p>
     *
     * @param tag the probe the sentinel belongs to, so each folder holds its own record
     * @return the sentinel bytes
     */
    private static byte[] sentinel(String tag)
    {
        return ("aiedt-absent-provider-sentinel-" + tag) //$NON-NLS-1$
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Runs one write into the probe project through the public tool entry.
     *
     * @param mode the write mode
     * @param modulePath the module path to write to
     * @param dryRun {@code "true"} for a preview, {@code null} for a real write
     * @return the tool's answer
     */
    private static String write(String mode, String modulePath, String dryRun)
    {
        Map<String, String> args = new HashMap<>();
        args.put("projectName", PROJECT); //$NON-NLS-1$
        args.put("modulePath", modulePath); //$NON-NLS-1$
        args.put("mode", mode); //$NON-NLS-1$
        args.put("source", SOURCE); //$NON-NLS-1$
        if (dryRun != null)
        {
            args.put("dryRun", dryRun); //$NON-NLS-1$
        }
        return new ModuleSourceWriter().execute(args);
    }

    /**
     * Asserts a file on disk holds exactly the given bytes.
     *
     * @param what what the file is, for the failure message
     * @param expected the bytes the file has to hold
     * @param file the file on disk
     * @throws Exception when the file cannot be read
     */
    private static void assertHoldsExactly(String what, byte[] expected, Path file)
        throws Exception
    {
        assertTrue(what + ": the file is gone", file.toFile().isFile()); //$NON-NLS-1$
        assertArrayEquals(what, expected, Files.readAllBytes(file));
    }

    /**
     * Reads a written module file as text, dropping the UTF-8 byte-order mark and the line
     * endings the writer adds.
     *
     * @param module the module file on disk
     * @return its text with LF line endings
     * @throws Exception when the file cannot be read
     */
    private static String moduleText(Path module) throws Exception
    {
        byte[] bytes = Files.readAllBytes(module);
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
            && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        String text = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        return text.replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
