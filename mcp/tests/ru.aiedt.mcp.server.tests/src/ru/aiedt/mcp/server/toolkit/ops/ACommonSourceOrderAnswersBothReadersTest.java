/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.server.support.modules.IModuleSource;
import ru.aiedt.mcp.server.support.modules.IModuleSourceProvider;
import ru.aiedt.mcp.server.support.modules.ModuleSources;

/**
 * Both source readers answer from one line sequence: the first registered module provider, then a
 * dirty editor buffer, then the file on disk. This is the immutable regression probe for that
 * order: on the unchanged base it fails because {@code read_module_source} prefers an existing
 * physical file over an applicable provider and {@code read_method_source} never consults the
 * editor buffer at all, so the method bounds it reports are the disk offsets of text the user is
 * no longer looking at.
 *
 * <p>The probe needs no EDT model and no display. It creates its own temporary workspace project
 * with real modules on disk, registers its own provider, and connects real text file buffers
 * through {@code FileBuffers} - the same platform buffers an open editor uses. Every assertion is
 * made through the public tool entry points, so the class compiles and runs on the base without
 * any new helper type.</p>
 *
 * <p>A built EMF model that is stale against a dirty buffer or a provider cannot be constructed in
 * this headless fixture; the live EDT acceptance of that case is named in the worker report as a
 * remaining check, not claimed here.</p>
 */
public class ACommonSourceOrderAnswersBothReadersTest
{
    private static final String PROJECT = "AiEdtCommonSourceOrderProbe"; //$NON-NLS-1$

    private static final String PROBE = "CommonModules/Probe/Module.bsl"; //$NON-NLS-1$

    private static final String PROVIDER_AND_FILE = "CommonModules/ProviderAndFile/Module.bsl"; //$NON-NLS-1$

    private static final String PROVIDER_ONLY = "CommonModules/ProviderOnly/Module.bsl"; //$NON-NLS-1$

    private static final String MISSING_CONTAINER = "Catalogs/Products/Forms/NoProvider/Module.bsl"; //$NON-NLS-1$

    private static final String MISSING_CONTAINER_HOLDER = "Catalogs/Products/Forms/NoProvider/Form.oform"; //$NON-NLS-1$

    private static final String DISK_PROBE = "Процедура МетодСДиска()\n\tВозврат;\nКонецПроцедуры"; //$NON-NLS-1$

    private static final String DISK_PROVIDER_AND_FILE = "Процедура ИзФайла()\nКонецПроцедуры"; //$NON-NLS-1$

    private static final String PROVIDER_TEXT = "Процедура ИзПровайдера()\n\tВозврат;\nКонецПроцедуры\n\n" //$NON-NLS-1$
        + "Функция ЗначениеПровайдера() Экспорт\n\tВозврат 42;\nКонецФункции"; //$NON-NLS-1$

    /** Inserted lines before the method, a Cyrillic function and a standard doc-comment block. */
    private static final String DIRTY_TEXT = "// inserted line one\n" //$NON-NLS-1$
        + "// inserted line two\n" //$NON-NLS-1$
        + "\n" //$NON-NLS-1$
        + "// Описание: складывает два числа.\n" //$NON-NLS-1$
        + "//\n" //$NON-NLS-1$
        + "// Параметры:\n" //$NON-NLS-1$
        + "//  А - Число\n" //$NON-NLS-1$
        + "//  Б - Число\n" //$NON-NLS-1$
        + "//\n" //$NON-NLS-1$
        + "// Возвращаемое значение:\n" //$NON-NLS-1$
        + "//   Число\n" //$NON-NLS-1$
        + "Функция Сложить(А, Б) Экспорт\n" //$NON-NLS-1$
        + "\tВозврат А + Б;\n" //$NON-NLS-1$
        + "КонецФункции\n" //$NON-NLS-1$
        + "\n" //$NON-NLS-1$
        + "Процедура НоваяМетодика()\n" //$NON-NLS-1$
        + "\tВозврат;\n" //$NON-NLS-1$
        + "КонецПроцедуры"; //$NON-NLS-1$

    private static final String RENAMED_TEXT = "Процедура МетодИзБуфера()\n\tВозврат;\nКонецПроцедуры"; //$NON-NLS-1$

    private static Path root;

    private static Path projectDir;

    private static IProject project;

    private static final ProbeProvider PROVIDER = new ProbeProvider();

    /** One provided module holding its text in memory, read-only for this probe. */
    private static final class ProvidedModule
        implements IModuleSource
    {
        private final String modulePath;

        private final List<String> lines;

        private ProvidedModule(String modulePath, List<String> lines)
        {
            this.modulePath = modulePath;
            this.lines = List.copyOf(lines);
        }

        @Override
        public String kind()
        {
            return "ProbeModule"; //$NON-NLS-1$
        }

        @Override
        public String source()
        {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String modulePath()
        {
            return modulePath;
        }

        @Override
        public String containerPath()
        {
            return null;
        }

        @Override
        public String fqn()
        {
            return null;
        }

        @Override
        public List<String> lines()
        {
            return new ArrayList<>(lines);
        }

        @Override
        public boolean write(List<String> newLines)
        {
            throw new UnsupportedOperationException("read-only probe"); //$NON-NLS-1$
        }
    }

    /**
     * A provider that owns two addresses of this probe project, one of which also has a physical
     * file on disk. The file case is what the unchanged module reader gets wrong.
     */
    private static final class ProbeProvider
        implements IModuleSourceProvider
    {
        private final Map<String, IModuleSource> modules = new HashMap<>();

        private ProbeProvider()
        {
            List<String> lines = List.of(PROVIDER_TEXT.split("\n")); //$NON-NLS-1$
            modules.put(PROVIDER_AND_FILE, new ProvidedModule(PROVIDER_AND_FILE, lines));
            modules.put(PROVIDER_ONLY, new ProvidedModule(PROVIDER_ONLY, lines));
        }

        /** Returns the module kind supplied by this test provider. */
        @Override
        public String kind()
        {
            return "ProbeModule"; //$NON-NLS-1$
        }

        @Override
        public IModuleSource locate(IProject candidate, String modulePath)
        {
            if (candidate == null || !PROJECT.equals(candidate.getName()) || modulePath == null)
            {
                return null;
            }
            String path = modulePath.startsWith("src/") ? modulePath.substring(4) : modulePath; //$NON-NLS-1$
            return modules.get(path);
        }

        @Override
        public List<IModuleSource> list(IProject candidate)
        {
            return candidate != null && PROJECT.equals(candidate.getName())
                ? new ArrayList<>(modules.values()) : List.of();
        }

        @Override
        public String coverage(Collection<IProject> projects, String what)
        {
            return null;
        }
    }

    /**
     * Creates the probe project and registers the provider.
     *
     * @throws Exception when the project or its files cannot be created
     */
    @BeforeClass
    public static void aProjectWithOneFileOneProviderAndOneMissingAddress() throws Exception
    {
        root = Files.createTempDirectory("aiedt-common-source-order"); //$NON-NLS-1$
        projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src/CommonModules/Probe")); //$NON-NLS-1$
        Files.createDirectories(projectDir.resolve("src/CommonModules/ProviderAndFile")); //$NON-NLS-1$
        writeUtf8Bom(projectDir.resolve("src").resolve(PROBE), DISK_PROBE); //$NON-NLS-1$
        writeUtf8Bom(projectDir.resolve("src").resolve(PROVIDER_AND_FILE), DISK_PROVIDER_AND_FILE); //$NON-NLS-1$
        Path holder = projectDir.resolve("src").resolve(MISSING_CONTAINER_HOLDER); //$NON-NLS-1$
        Files.createDirectories(holder.getParent());
        Files.write(holder, "probe container".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        ModuleSources.register(PROVIDER);
    }

    /**
     * Removes the probe project, its directory and the provider, and proves the provider registry
     * handed back only this suite's registration.
     *
     * @throws Exception when the project cannot be removed
     */
    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        ModuleSources.unregister(PROVIDER);
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
        assertFalse(ModuleSources.providers().contains(PROVIDER));
    }

    /** Every test starts with no buffer connected and the disk files as the suite wrote them. */
    @Before
    public void theFilesAreWhatTheSuiteWrote() throws Exception
    {
        disconnect(PROBE);
        disconnect(PROVIDER_AND_FILE);
        if (projectDir != null)
        {
            writeUtf8Bom(projectDir.resolve("src").resolve(PROBE), DISK_PROBE); //$NON-NLS-1$
            writeUtf8Bom(projectDir.resolve("src").resolve(PROVIDER_AND_FILE), DISK_PROVIDER_AND_FILE); //$NON-NLS-1$
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        }
    }

    /** No buffer is left connected for the next test. */
    @After
    public void noBufferStaysConnected() throws Exception
    {
        disconnect(PROBE);
        disconnect(PROVIDER_AND_FILE);
    }

    /**
     * A dirty buffer is the one source for both readers: the module reader shows the buffer's
     * lines, and the method reader finds a function that exists only in the buffer, uses the
     * buffer's inclusive bounds after the inserted lines, keeps the Cyrillic end marker and parses
     * the doc-comment when asked. On the base this fails in the method answer: the disk text is
     * read and the buffer's function is reported missing.
     *
     * @throws Exception when the buffer cannot be connected
     */
    @Test
    public void aDirtyBufferIsTheOneSourceForBothReaders() throws Exception
    {
        ITextFileBuffer buffer = connect(PROBE);
        buffer.getDocument().set(DIRTY_TEXT);
        assertTrue("the probe buffer is dirty", buffer.isDirty()); //$NON-NLS-1$

        String module = moduleAnswer(PROBE, "startLine", "12", "endLine", "14"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(module, module.contains("Функция Сложить(А, Б) Экспорт")); //$NON-NLS-1$
        assertTrue(module, module.contains("12: Функция Сложить(А, Б) Экспорт")); //$NON-NLS-1$
        assertTrue(module, module.contains("14: КонецФункции")); //$NON-NLS-1$
        assertTrue(module, module.contains("**Unsaved:**")); //$NON-NLS-1$
        assertFalse(module, module.contains("МетодСДиска")); //$NON-NLS-1$

        String method = methodAnswer(PROBE, "Сложить", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(method, method.contains("method: Сложить")); //$NON-NLS-1$
        assertTrue(method, method.contains("startLine: 4")); //$NON-NLS-1$
        assertTrue(method, method.contains("endLine: 14")); //$NON-NLS-1$
        assertTrue(method, method.contains("totalLines: 18")); //$NON-NLS-1$
        assertTrue(method, method.contains("type: Function")); //$NON-NLS-1$
        assertTrue(method, method.contains("export: true")); //$NON-NLS-1$
        assertTrue(method, method.contains("Возврат А + Б;")); //$NON-NLS-1$
        assertTrue(method, method.contains("## Parsed Documentation")); //$NON-NLS-1$
        assertTrue(method, method.contains("Описание: складывает два числа.")); //$NON-NLS-1$
        assertFalse(method, method.contains("МетодСДиска")); //$NON-NLS-1$
    }

    /**
     * A method renamed in the dirty buffer answers by its new name, and the disk name is listed as
     * missing with the buffer's methods. On the base both answers come from the disk text.
     *
     * @throws Exception when the buffer cannot be connected
     */
    @Test
    public void aRenamedMethodAndAMissingOneAreAnsweredFromTheDirtyBuffer() throws Exception
    {
        ITextFileBuffer buffer = connect(PROBE);
        buffer.getDocument().set(RENAMED_TEXT);
        assertTrue(buffer.isDirty());

        String renamed = methodAnswer(PROBE, "МетодИзБуфера", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(renamed, renamed.contains("method: МетодИзБуфера")); //$NON-NLS-1$
        assertTrue(renamed, renamed.contains("startLine: 1")); //$NON-NLS-1$
        assertTrue(renamed, renamed.contains("endLine: 3")); //$NON-NLS-1$
        assertTrue(renamed, renamed.contains("totalLines: 3")); //$NON-NLS-1$
        assertTrue(renamed, renamed.contains("Возврат;")); //$NON-NLS-1$

        String missing = methodAnswer(PROBE, "МетодСДиска", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(missing, missing.contains("no method called 'МетодСДиска'")); //$NON-NLS-1$
        assertTrue(missing, missing.contains("- МетодИзБуфера")); //$NON-NLS-1$
        assertFalse(missing, missing.contains("Error: could not read")); //$NON-NLS-1$
    }

    /**
     * A provider that claims an address alongside a physical file is the source for both readers:
     * the module answer names the provider and never shows the file text. On the base the module
     * reader takes the file because {@code file.exists()} hides the provider.
     */
    @Test
    public void aProviderBesideAPhysicalFileWinsForTheModuleReader()
    {
        String module = moduleAnswer(PROVIDER_AND_FILE);
        assertTrue(module, module.contains("Процедура ИзПровайдера()")); //$NON-NLS-1$
        assertTrue(module, module.contains("**Source:** probe")); //$NON-NLS-1$
        assertFalse(module, module.contains("ИзФайла")); //$NON-NLS-1$

        String providerMethod = methodAnswer(PROVIDER_AND_FILE, "ИзПровайдера", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(providerMethod, providerMethod.contains("source: probe")); //$NON-NLS-1$
        assertTrue(providerMethod, providerMethod.contains("Возврат;")); //$NON-NLS-1$

        String fileMethod = methodAnswer(PROVIDER_AND_FILE, "ИзФайла", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(fileMethod, fileMethod.contains("no method called 'ИзФайла'")); //$NON-NLS-1$
        assertFalse(fileMethod, fileMethod.contains("- ИзФайла")); //$NON-NLS-1$
    }

    /**
     * A provider whose address has no physical file answers both readers and carries its origin
     * note; this is the unchanged part of the precedence and stays true after the change.
     */
    @Test
    public void aProviderWithoutAPhysicalFileStillAnswers()
    {
        String module = moduleAnswer(PROVIDER_ONLY);
        assertTrue(module, module.contains("Процедура ИзПровайдера()")); //$NON-NLS-1$
        assertTrue(module, module.contains("**Source:** probe")); //$NON-NLS-1$

        String method = methodAnswer(PROVIDER_ONLY, "ЗначениеПровайдера", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(method, method.contains("source: probe")); //$NON-NLS-1$
        assertTrue(method, method.contains("Возврат 42;")); //$NON-NLS-1$
    }

    /**
     * A clean buffer is no source: the module reader answers the disk text byte for byte, with no
     * origin note. This pins the unchanged disk response bytes that the source-order fix must
     * preserve. The method reader's disk rendering stays the same code as before the fix; it is
     * not re-asserted here because a headless call would have to wait on a UI thread no test pumps.
     *
     * @throws Exception when the buffer cannot be connected
     */
    @Test
    public void aCleanBufferLeavesTheDiskAnswerByteForByte() throws Exception
    {
        ITextFileBuffer buffer = connect(PROBE);
        assertFalse("a fresh buffer holds what the file holds", buffer.isDirty()); //$NON-NLS-1$

        String module = moduleAnswer(PROBE);
        assertEquals("## " + PROBE + "\n\n**Lines:** 1-3 of 3 total\n\n```bsl\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "1: Процедура МетодСДиска()\n2: \tВозврат;\n3: КонецПроцедуры\n```\n", module); //$NON-NLS-1$
        assertFalse(module, module.contains("**Unsaved:**")); //$NON-NLS-1$
        assertFalse(module, module.contains("**Source:**")); //$NON-NLS-1$
    }

    /**
     * An address whose container has no provider and no file is still refused: the source read
     * names the missing file and shows no successful line count. This refusal is unchanged by the
     * source order. The method reader's refusal for the same address keeps its own text path and is
     * not re-called here: in this headless runtime that call would have to wait on a UI thread no
     * test pumps.
     */
    @Test
    public void aContainerAddressWithoutItsProviderIsStillRefused()
    {
        assertNull("no provider owns the probe container", ModuleSources.locate(project, MISSING_CONTAINER)); //$NON-NLS-1$

        String module = moduleAnswer(MISSING_CONTAINER);
        assertTrue(module, module.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(module, module.contains("no such file")); //$NON-NLS-1$
        assertTrue(module, module.contains(MISSING_CONTAINER));
        assertFalse(module, module.contains("**Lines:**")); //$NON-NLS-1$
        assertFalse(module, module.contains("```bsl")); //$NON-NLS-1$
    }

    private static String moduleAnswer(String modulePath, String... argumentPairs)
    {
        Map<String, String> args = new HashMap<>();
        args.put("projectName", PROJECT); //$NON-NLS-1$
        args.put("modulePath", modulePath); //$NON-NLS-1$
        for (int i = 0; i + 1 < argumentPairs.length; i += 2)
        {
            args.put(argumentPairs[i], argumentPairs[i + 1]);
        }
        return new ModuleSourceReader().execute(args);
    }

    private static String methodAnswer(String modulePath, String methodName, String includeDoc)
    {
        Map<String, String> args = new HashMap<>();
        args.put("projectName", PROJECT); //$NON-NLS-1$
        args.put("modulePath", modulePath); //$NON-NLS-1$
        args.put("methodName", methodName); //$NON-NLS-1$
        args.put("includeDoc", includeDoc); //$NON-NLS-1$
        return new MethodSourceReader().execute(args);
    }

    private static IFile fileOf(String modulePath)
    {
        assertNotNull(project);
        return project.getFile("src/" + modulePath); //$NON-NLS-1$
    }

    /**
     * Connects a real text file buffer, as an open editor does.
     *
     * @param modulePath the module whose file is opened
     * @return the connected buffer
     * @throws Exception when the runtime has no buffer manager or the connection is refused
     */
    private static ITextFileBuffer connect(String modulePath) throws Exception
    {
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        assertNotNull("the runtime has a text file buffer manager", manager); //$NON-NLS-1$
        IFile file = fileOf(modulePath);
        manager.connect(file.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
        ITextFileBuffer buffer = manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
        assertNotNull("the module's buffer is connected", buffer); //$NON-NLS-1$
        return buffer;
    }

    private static void disconnect(String modulePath) throws Exception
    {
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        if (manager == null || project == null)
        {
            return;
        }
        IFile file = fileOf(modulePath);
        if (manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE) != null)
        {
            manager.disconnect(file.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
        }
    }

    private static void writeUtf8Bom(Path file, String text) throws IOException
    {
        Files.createDirectories(file.getParent());
        Files.write(file, ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
    }
}
