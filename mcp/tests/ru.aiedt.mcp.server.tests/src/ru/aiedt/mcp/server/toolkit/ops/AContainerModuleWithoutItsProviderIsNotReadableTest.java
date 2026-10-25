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
import java.util.List;
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

import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;

/**
 * A module of a form kept in a {@code Form.oform} container is an address a module source
 * provider owns, and with no provider registered the read of that module is refused instead of
 * answering an empty module or an empty structure that the disk does not hold.
 * <p>
 * The probe project holds one catalog with a form folder per probe, and every probe reads through
 * the public tool entries: {@code read_module_source} of {@link ModuleSourceReader} and
 * {@code get_module_structure} of {@link ModuleOutlineReader}. The {@code Form.oform} entry of a
 * probe folder is a short unparsed sentinel record: this suite proves the absence of a provider
 * and the refusal of the read, not the format of the form container, so the sentinel only has to
 * keep its exact bytes.
 * </p>
 * <p>
 * No provider is registered by this suite, and every negative probe asks
 * {@link ModuleSources#locate} first and expects {@code null}, so a provider another test left in
 * the registry is a setup error rather than a passing probe. The suite never touches the registry,
 * and the teardown proves the registry handed back is the one captured at setup. A form folder
 * without a {@code Form.oform} container holds a real file module, and its read stays allowed -
 * that probe is what makes the refusal assertions meaningful, because it shows the readers answer
 * successfully in this very runtime when there is something to answer with.
 * </p>
 * <p>
 * Where the refusal comes from is read off the code, not guessed. {@link ModuleSourceReader} finds
 * no file at the address, finds no provider through {@link ModuleSources#locate}, and answers the
 * {@code "no such file"} error. {@link ModuleOutlineReader} finds no provider, then no built model
 * of the probe project, then no file for the text fallback, and answers the
 * {@code "BSL model is not available"} error. Both refusals are proven on the disk beside the
 * answer: no {@code Module.bsl} ever appears and the container keeps its bytes.
 * </p>
 */
public class AContainerModuleWithoutItsProviderIsNotReadableTest
{
    private static final String PROJECT = "AiEdtAbsentProviderReadProbe"; //$NON-NLS-1$

    private static final String FORMS = "src/Catalogs/Products/Forms"; //$NON-NLS-1$

    private static final String SOURCE =
        "// Exercise the standalone-file read path.\n" //$NON-NLS-1$
            + "Procedure ProbeRead()\n\tReturn;\nEndProcedure"; //$NON-NLS-1$

    private static Path root;

    private static IProject project;

    private static Path projectDir;

    private static List<IModuleSourceProvider> providersBefore;

    /**
     * Captures the provider registry the suite starts from and creates an open project of one
     * catalog with its Forms folder and nothing in it: every probe adds the form folder of its
     * own, so no probe reads what another wrote.
     *
     * @throws Exception when the project or its folder cannot be created
     */
    @BeforeClass
    public static void aProjectOfOneCatalogWithItsFormsFolder() throws Exception
    {
        providersBefore = ModuleSources.providers();
        root = Files.createTempDirectory("aiedt-absent-provider-read"); //$NON-NLS-1$
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
     * Removes the project and its directory, then proves the provider registry is handed back
     * exactly as it was captured: this suite never registers a provider, so nothing is restored,
     * and a difference here means another suite's leftover, not this suite's.
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
        assertEquals("the provider registry leaves as it was captured", //$NON-NLS-1$
            providersBefore, ModuleSources.providers());
    }

    /**
     * A source read of a form module kept in a container no provider holds is refused with the
     * missing file named, and the disk keeps what it held: no module file appears beside the
     * container and the container keeps its exact bytes. The refusal is not a successful empty
     * module: the answer carries neither the line count nor the code fence of a real read.
     *
     * @throws Exception when the probe folder cannot be created or read back
     */
    @Test
    public void aSourceReadWithoutAProviderIsRefusedAndTheContainerKeepsItsBytes() throws Exception
    {
        byte[] sentinel = sentinel("source"); //$NON-NLS-1$
        Path form = aFormFolder("SourceProbe", sentinel); //$NON-NLS-1$
        String address = moduleAddressOf("SourceProbe"); //$NON-NLS-1$

        assertNull("a provider must hold nothing before the probe", //$NON-NLS-1$
            ModuleSources.locate(project, address));

        String answer = read(address);

        assertHoldsExactly("the container kept its exact bytes", sentinel, //$NON-NLS-1$
            form.resolve("Form.oform")); //$NON-NLS-1$
        assertFalse("no standalone module file may appear: " + answer, //$NON-NLS-1$
            Files.exists(form.resolve("Module.bsl"))); //$NON-NLS-1$
        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("no such file")); //$NON-NLS-1$
        assertTrue(answer, answer.contains(address));
        assertFalse("a refusal is not a successful read: " + answer, //$NON-NLS-1$
            answer.contains("**Lines:**")); //$NON-NLS-1$
        assertFalse("a refusal is not a successful read: " + answer, //$NON-NLS-1$
            answer.contains("```bsl")); //$NON-NLS-1$
    }

    /**
     * An outline read of a form module kept in a container no provider holds is refused with the
     * absent model named, on a form of its own in the same state the source probe started from,
     * and the disk keeps what it held. The refusal is not a successful empty structure: the answer
     * carries neither the layout heading nor the totals line of a real outline.
     *
     * @throws Exception when the probe folder cannot be created or read back
     */
    @Test
    public void anOutlineReadWithoutAProviderIsRefusedAndTheContainerKeepsItsBytes()
        throws Exception
    {
        byte[] sentinel = sentinel("outline"); //$NON-NLS-1$
        Path form = aFormFolder("OutlineProbe", sentinel); //$NON-NLS-1$
        String address = moduleAddressOf("OutlineProbe"); //$NON-NLS-1$

        assertNull("a provider must hold nothing before the probe", //$NON-NLS-1$
            ModuleSources.locate(project, address));

        String answer = outline(address);

        assertHoldsExactly("the container kept its exact bytes", sentinel, //$NON-NLS-1$
            form.resolve("Form.oform")); //$NON-NLS-1$
        assertFalse("no standalone module file may appear: " + answer, //$NON-NLS-1$
            Files.exists(form.resolve("Module.bsl"))); //$NON-NLS-1$
        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("BSL model is not available")); //$NON-NLS-1$
        assertTrue(answer, answer.contains(address));
        assertFalse("a refusal is not a successful outline: " + answer, //$NON-NLS-1$
            answer.contains("## Module Layout:")); //$NON-NLS-1$
        assertFalse("a refusal is not a successful outline: " + answer, //$NON-NLS-1$
            answer.contains("**Total:**")); //$NON-NLS-1$
    }

    /**
     * A form folder that holds no container keeps a real file module, and both readers answer it:
     * the source read carries the numbered lines and the outline names the procedure. This is the
     * control that keeps the refusals honest - the readers demonstrably answer in this runtime
     * when there is a module to answer with. The reads touch nothing: the file keeps its exact
     * bytes.
     *
     * @throws Exception when the probe folder cannot be created or read back
     */
    @Test
    public void aFormFolderWithoutAContainerStillReadsBackItsModuleFile() throws Exception
    {
        Path form = aFormFolder("PlainProbe", null); //$NON-NLS-1$
        String address = moduleAddressOf("PlainProbe"); //$NON-NLS-1$
        Path module = form.resolve("Module.bsl"); //$NON-NLS-1$
        byte[] written = SOURCE.getBytes(StandardCharsets.UTF_8);
        Files.write(module, written);
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        assertNull("a provider must hold nothing before the probe", //$NON-NLS-1$
            ModuleSources.locate(project, address));

        String sourceAnswer = read(address);
        assertTrue(sourceAnswer, sourceAnswer.contains("```bsl")); //$NON-NLS-1$
        assertTrue(sourceAnswer, sourceAnswer.contains("**Lines:**")); //$NON-NLS-1$
        assertTrue(sourceAnswer, sourceAnswer.contains("Procedure ProbeRead()")); //$NON-NLS-1$

        String outlineAnswer = outline(address);
        assertTrue(outlineAnswer, outlineAnswer.contains("## Module Layout:")); //$NON-NLS-1$
        assertTrue(outlineAnswer, outlineAnswer.contains("ProbeRead")); //$NON-NLS-1$

        assertArrayEquals("the reads left the module file byte for byte", //$NON-NLS-1$
            written, Files.readAllBytes(module));
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
     * refusal of the read, not the format of the form container, so the bytes only have to be the
     * probe's own and to stay unchanged.
     * </p>
     *
     * @param tag the probe the sentinel belongs to, so each folder holds its own record
     * @return the sentinel bytes
     */
    private static byte[] sentinel(String tag)
    {
        return ("aiedt-absent-provider-read-sentinel-" + tag) //$NON-NLS-1$
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Runs one source read against the probe project through the public tool entry.
     *
     * @param modulePath the module path to read
     * @return the tool's answer
     */
    private static String read(String modulePath)
    {
        Map<String, String> args = new HashMap<>();
        args.put("projectName", PROJECT); //$NON-NLS-1$
        args.put("modulePath", modulePath); //$NON-NLS-1$
        return new ModuleSourceReader().execute(args);
    }

    /**
     * Runs one outline read against the probe project through the public tool entry.
     *
     * @param modulePath the module path whose structure is asked for
     * @return the tool's answer
     */
    private static String outline(String modulePath)
    {
        Map<String, String> args = new HashMap<>();
        args.put("projectName", PROJECT); //$NON-NLS-1$
        args.put("modulePath", modulePath); //$NON-NLS-1$
        return new ModuleOutlineReader().execute(args);
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
}
