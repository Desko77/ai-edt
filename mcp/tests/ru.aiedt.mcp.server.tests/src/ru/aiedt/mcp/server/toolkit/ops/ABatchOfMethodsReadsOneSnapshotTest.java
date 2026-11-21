/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 * The accepted batch contract of {@code read_method_source}: {@code methodBatch} is a strict JSON
 * array of 1 to 25 names, duplicates are refused ignoring case, invalid names and unquoted or
 * single-quoted elements are refused before any source is touched, one immutable provider or
 * dirty-buffer snapshot serves every name, missing names are collected into one list, and the
 * single {@code methodName} route keeps its bytes.
 *
 * <p>This is the red probe for that contract on the unchanged base: only the single-name byte
 * test passes there, and every batch test fails because {@code methodBatch} is not read at all.
 * The provider counts its {@link IModuleSource#lines()} calls and can answer with a
 * different generation on each call, so a batch that re-reads its source per name is caught
 * rather than merely suspected. The dirty-buffer case connects a real {@code FileBuffers} buffer,
 * the same kind an open editor holds, so the batch must answer from it instead of the disk.</p>
 */
public class ABatchOfMethodsReadsOneSnapshotTest
{
    private static final String PROJECT = "AiEdtBatchMethodProbe"; //$NON-NLS-1$

    private static final String ADDRESS = "CommonModules/Batch/Module.bsl"; //$NON-NLS-1$

    private static final String CONTAINER = "CommonModules/Batch/Form.probe"; //$NON-NLS-1$

    private static final String BUFFERED = "CommonModules/Buffered/Module.bsl"; //$NON-NLS-1$

    private static final String BASE_TEXT = "Процедура Загрузить()\n" //$NON-NLS-1$
        + "\tВозврат;\n" //$NON-NLS-1$
        + "КонецПроцедуры\n" //$NON-NLS-1$
        + "\n" //$NON-NLS-1$
        + "Функция Сложить(А, Б) Экспорт\n" //$NON-NLS-1$
        + "\tВозврат А + Б;\n" //$NON-NLS-1$
        + "КонецФункции\n" //$NON-NLS-1$
        + "\n" //$NON-NLS-1$
        + "Процедура Сохранить()\n" //$NON-NLS-1$
        + "\tВозврат;\n" //$NON-NLS-1$
        + "КонецПроцедуры"; //$NON-NLS-1$

    /** The same module with the last method renamed, so a second read is visible in the answer. */
    private static final String SECOND_GENERATION_TEXT = "Процедура Загрузить()\n" //$NON-NLS-1$
        + "\tВозврат;\n" //$NON-NLS-1$
        + "КонецПроцедуры\n" //$NON-NLS-1$
        + "\n" //$NON-NLS-1$
        + "Функция Сложить(А, Б) Экспорт\n" //$NON-NLS-1$
        + "\tВозврат А + Б;\n" //$NON-NLS-1$
        + "КонецФункции\n" //$NON-NLS-1$
        + "\n" //$NON-NLS-1$
        + "Процедура Записать()\n" //$NON-NLS-1$
        + "\tВозврат;\n" //$NON-NLS-1$
        + "КонецПроцедуры"; //$NON-NLS-1$

    private static final String DISK_TEXT = "Процедура СТекста()\n" //$NON-NLS-1$
        + "\tВозврат;\n" //$NON-NLS-1$
        + "КонецПроцедуры"; //$NON-NLS-1$

    private static final String BUFFER_TEXT = "Процедура ИзБуфера()\n" //$NON-NLS-1$
        + "\tВозврат;\n" //$NON-NLS-1$
        + "КонецПроцедуры"; //$NON-NLS-1$

    /** The exact answer the single-name route has always given for this provider method. */
    private static final String SINGLE_NAME_ANSWER = "---\n" //$NON-NLS-1$
        + "projectName: " + PROJECT + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "module: " + ADDRESS + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "method: Сложить\n" //$NON-NLS-1$
        + "type: Function\n" //$NON-NLS-1$
        + "export: true\n" //$NON-NLS-1$
        + "startLine: 5\n" //$NON-NLS-1$
        + "endLine: 7\n" //$NON-NLS-1$
        + "totalLines: 11\n" //$NON-NLS-1$
        + "source: probe\n" //$NON-NLS-1$
        + "container: " + CONTAINER + "\n" //$NON-NLS-1$ //$NON-NLS-2$
        + "---\n" //$NON-NLS-1$
        + "```bsl\n" //$NON-NLS-1$
        + "Функция Сложить(А, Б) Экспорт\n" //$NON-NLS-1$
        + "\tВозврат А + Б;\n" //$NON-NLS-1$
        + "КонецФункции\n" //$NON-NLS-1$
        + "```\n"; //$NON-NLS-1$

    private static Path root;

    private static Path projectDir;

    private static IProject project;

    private static final Probe PROBE = new Probe();

    /**
     * A provider that owns one address, counts its reads and answers with the next generation when
     * one is queued. A batch that reads per name consumes the second generation instead of the
     * snapshot the first read returned.
     */
    private static final class Probe
        implements IModuleSourceProvider, IModuleSource
    {
        private final List<List<String>> generations = new ArrayList<>();

        private int nextGeneration;

        int reads;

        private Probe()
        {
            reset(BASE_TEXT);
        }

        /**
         * Queues the generations this provider answers with, in order, and clears the read count.
         *
         * @param texts the module texts, one per read
         */
        void reset(String... texts)
        {
            generations.clear();
            for (String text : texts)
            {
                generations.add(List.of(text.split("\n"))); //$NON-NLS-1$
            }
            nextGeneration = 0;
            reads = 0;
        }

        @Override
        public String kind()
        {
            return "ProbeModule"; //$NON-NLS-1$
        }

        @Override
        public IModuleSource locate(IProject candidate, String modulePath)
        {
            if (candidate == null || candidate != project || modulePath == null)
            {
                return null;
            }
            String path = modulePath.startsWith("src/") ? modulePath.substring(4) : modulePath; //$NON-NLS-1$
            return ADDRESS.equals(path) ? this : null;
        }

        @Override
        public List<IModuleSource> list(IProject candidate)
        {
            return candidate == project ? List.of(this) : List.of();
        }

        @Override
        public String coverage(Collection<IProject> projects, String what)
        {
            return null;
        }

        @Override
        public String source()
        {
            return "probe"; //$NON-NLS-1$
        }

        @Override
        public String modulePath()
        {
            return ADDRESS;
        }

        @Override
        public String containerPath()
        {
            return CONTAINER;
        }

        @Override
        public String fqn()
        {
            return null;
        }

        @Override
        public List<String> lines()
        {
            reads++;
            int index = Math.min(nextGeneration, generations.size() - 1);
            nextGeneration++;
            return new ArrayList<>(generations.get(index));
        }

        @Override
        public boolean write(List<String> newLines)
        {
            throw new UnsupportedOperationException("read-only probe"); //$NON-NLS-1$
        }
    }

    @BeforeClass
    public static void aProjectWithAProvidedModuleAndARealOne() throws Exception
    {
        root = Files.createTempDirectory("aiedt-batch-method-read"); //$NON-NLS-1$
        projectDir = root.resolve(PROJECT);
        Path bufferedFile = projectDir.resolve("src").resolve(BUFFERED); //$NON-NLS-1$
        Files.createDirectories(bufferedFile.getParent());
        Files.write(bufferedFile, ("\uFEFF" + DISK_TEXT).getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        ModuleSources.register(PROBE);
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        ModuleSources.unregister(PROBE);
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
        assertFalse(ModuleSources.providers().contains(PROBE));
    }

    /** Every test starts with the base generation and no connected buffer. */
    @Before
    public void theBaseGenerationAndNoBuffer() throws Exception
    {
        PROBE.reset(BASE_TEXT);
        disconnect(BUFFERED);
        if (projectDir != null)
        {
            Files.write(projectDir.resolve("src").resolve(BUFFERED), //$NON-NLS-1$
                ("\uFEFF" + DISK_TEXT).getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        }
    }

    /** No buffer is left connected for the next test. */
    @After
    public void noBufferStaysConnected() throws Exception
    {
        disconnect(BUFFERED);
    }

    /**
     * A batch with several found names answers every one of them, in one document and from one
     * provider read. On the base this fails at validation: no {@code methodBatch} route exists.
     */
    @Test
    public void aBatchAnswersEveryNamedMethodFromOneRead()
    {
        String answer = batchAnswer(ADDRESS, "Загрузить", "Сложить", "Сохранить"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Процедура Загрузить()")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Функция Сложить(А, Б) Экспорт")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Процедура Сохранить()")); //$NON-NLS-1$
        assertEquals("one provider read serves the whole batch", 1, PROBE.reads); //$NON-NLS-1$
        assertEquals("one code block per found method", 3, count(answer, "```bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("one document, not one front matter per method", //$NON-NLS-1$
            count(answer, "projectName:") <= 1); //$NON-NLS-1$
    }

    /** One name is a batch of one: the lower bound is 1, not 2. */
    @Test
    public void aBatchOfOneNameIsAnswered()
    {
        String answer = batchAnswer(ADDRESS, "Сложить"); //$NON-NLS-1$

        assertFalse(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Функция Сложить(А, Б) Экспорт")); //$NON-NLS-1$
        assertEquals(1, PROBE.reads);
    }

    /**
     * The names that were not found are collected into one missing list, while the found ones are
     * still answered. On the base the call is refused for a missing {@code methodName}.
     */
    @Test
    public void aBatchNamesEveryMissingMethodInOneList()
    {
        String answer = batchAnswer(ADDRESS, "Сложить", "НетТакого", "ТожеНет"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertFalse("a mixed batch still answers its found names", //$NON-NLS-1$
            answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Функция Сложить(А, Б) Экспорт")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("НетТакого")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("ТожеНет")); //$NON-NLS-1$
        assertEquals("a missing name is listed once", 1, count(answer, "НетТакого")); //$NON-NLS-1$ //$NON-NLS-2$
        int first = answer.indexOf("НетТакого"); //$NON-NLS-1$
        int second = answer.indexOf("ТожеНет"); //$NON-NLS-1$
        assertTrue("the missing names share one list", first >= 0 && first < second); //$NON-NLS-1$
        assertFalse("no method section between two missing names", //$NON-NLS-1$
            answer.substring(first, second).contains("```")); //$NON-NLS-1$
        assertEquals(1, PROBE.reads);
    }

    /**
     * A provider that would answer differently on its next read is read once: every name comes
     * from the snapshot the first read returned. On the base there is no batch route to read it.
     */
    @Test
    public void aBatchKeepsTheProviderSnapshotItReadOnce()
    {
        PROBE.reset(BASE_TEXT, SECOND_GENERATION_TEXT);

        String answer = batchAnswer(ADDRESS, "Загрузить", "Сохранить"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Процедура Сохранить()")); //$NON-NLS-1$
        assertFalse("the second generation was never read", //$NON-NLS-1$
            answer.contains("Записать")); //$NON-NLS-1$
        assertEquals("one read for the whole batch", 1, PROBE.reads); //$NON-NLS-1$
    }

    /**
     * A dirty editor buffer is the one source for the whole batch: both the method that exists
     * only in the buffer and the name that exists only on disk are decided against that snapshot.
     *
     * @throws Exception when the buffer cannot be connected
     */
    @Test
    public void aBatchIsAnsweredFromTheCapturedDirtyBuffer() throws Exception
    {
        ITextFileBuffer buffer = connect(BUFFERED);
        buffer.getDocument().set(BUFFER_TEXT);
        assertTrue("the probe buffer is dirty", buffer.isDirty()); //$NON-NLS-1$

        String answer = batchAnswer(BUFFERED, "ИзБуфера", "СТекста"); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("Процедура ИзБуфера()")); //$NON-NLS-1$
        assertTrue("the disk-only name is reported missing", //$NON-NLS-1$
            answer.contains("СТекста")); //$NON-NLS-1$
        assertFalse("the disk text is not mixed into the buffer snapshot", //$NON-NLS-1$
            answer.contains("Процедура СТекста()")); //$NON-NLS-1$
        assertEquals("the provider is not consulted for a file address", 0, PROBE.reads); //$NON-NLS-1$
    }

    /**
     * A duplicate that differs only in case is refused before the provider is read. On the base
     * the call is refused earlier, for a missing {@code methodName}, without naming the duplicate.
     */
    @Test
    public void aCaseInsensitiveDuplicateIsRefusedBeforeReading()
    {
        String answer = batchAnswer(ADDRESS, "Сложить", "сложить"); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        String lower = answer.toLowerCase(Locale.ROOT);
        assertTrue(answer, lower.contains("duplicate") || lower.contains("twice") //$NON-NLS-1$
            || lower.contains("more than once")); //$NON-NLS-1$
        assertTrue("the offending name is named", //$NON-NLS-1$
            answer.contains("Сложить") || answer.contains("сложить")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("nothing was read", 0, PROBE.reads); //$NON-NLS-1$
    }

    /** An empty or blank name is invalid and is refused before reading. */
    @Test
    public void blankNamesAreRefusedBeforeReading()
    {
        String empty = batchAnswer(ADDRESS, "Сложить", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(empty, empty.startsWith("Error:")); //$NON-NLS-1$
        assertTrue(empty, empty.toLowerCase(Locale.ROOT).contains("empty") //$NON-NLS-1$
            || empty.toLowerCase(Locale.ROOT).contains("blank")); //$NON-NLS-1$

        String blank = batchAnswer(ADDRESS, "Сложить", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(blank, blank.startsWith("Error:")); //$NON-NLS-1$
        assertEquals("nothing was read", 0, PROBE.reads); //$NON-NLS-1$
    }

    /**
     * The wire contract is strict JSON: an unquoted element is not a JSON string. The lenient
     * parser of the shipped Gson would accept this array, so the lexical validation is what makes
     * the refusal happen before any module source is touched.
     */
    @Test
    public void anUnquotedArrayIsRefusedBeforeReading()
    {
        String answer = rawBatchAnswer(ADDRESS, "[Загрузить,Сложить]"); //$NON-NLS-1$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue("the refusal names the wire shape", //$NON-NLS-1$
            answer.toLowerCase(Locale.ROOT).contains("json array")); //$NON-NLS-1$
        assertEquals("nothing was read", 0, PROBE.reads); //$NON-NLS-1$
    }

    /**
     * A single-quoted element is accepted only by a lenient JSON parser, so the lexical validation
     * refuses it before the module is resolved.
     */
    @Test
    public void aSingleQuotedArrayIsRefusedBeforeReading()
    {
        String answer = rawBatchAnswer(ADDRESS, "['Загрузить','Сложить']"); //$NON-NLS-1$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue("the refusal names the wire shape", //$NON-NLS-1$
            answer.toLowerCase(Locale.ROOT).contains("json array")); //$NON-NLS-1$
        assertEquals("nothing was read", 0, PROBE.reads); //$NON-NLS-1$
    }

    /** A batch is 1 to 25 names: the 26th is refused before reading. */
    @Test
    public void moreThanTwentyFiveNamesIsRefusedBeforeReading()
    {
        String[] names = new String[26];
        for (int i = 0; i < names.length; i++)
        {
            names[i] = "М" + (i + 1); //$NON-NLS-1$
        }

        String answer = batchAnswer(ADDRESS, names);

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue("the bound is named", answer.contains("25")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("nothing was read", 0, PROBE.reads); //$NON-NLS-1$
    }

    /** Twenty-five names is the largest batch, and it is answered from one read. */
    @Test
    public void twentyFiveNamesIsAnsweredFromOneRead()
    {
        String[] names = new String[25];
        StringBuilder module = new StringBuilder();
        for (int i = 0; i < names.length; i++)
        {
            names[i] = "М" + (i + 1); //$NON-NLS-1$
            if (module.length() > 0)
            {
                module.append('\n');
            }
            module.append("Процедура ").append(names[i]).append("()\nКонецПроцедуры"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        PROBE.reset(module.toString());

        String answer = batchAnswer(ADDRESS, names);

        assertFalse(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertEquals("one read for the largest batch", 1, PROBE.reads); //$NON-NLS-1$
        for (String name : names)
        {
            assertTrue(name, answer.contains("Процедура " + name + "()")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertEquals("one code block per method", names.length, count(answer, "```bsl")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Both argument spellings or neither: one call cannot be both a single read and a batch, so
     * the combination is refused before reading. On the base {@code methodBatch} is ignored and
     * the single-name answer comes back.
     */
    @Test
    public void bothArgumentSpellingsAreRefusedBeforeReading()
    {
        Map<String, String> args = args("projectName", PROJECT, "modulePath", ADDRESS, //$NON-NLS-1$ //$NON-NLS-2$
            "methodName", "Сложить", "methodBatch", jsonArray("Загрузить")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String answer = new MethodSourceReader().execute(args);

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue("the caller is told which two arguments disagree", //$NON-NLS-1$
            answer.contains("methodName") && answer.contains("methodBatch")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("nothing was read", 0, PROBE.reads); //$NON-NLS-1$
    }

    /**
     * Neither spelling is refused before reading, and the refusal names both choices instead of
     * pretending only the old one exists. The unchanged base names only {@code methodName}.
     */
    @Test
    public void neitherArgumentSpellingIsRefusedBeforeReading()
    {
        String answer = new MethodSourceReader().execute(
            args("projectName", PROJECT, "modulePath", ADDRESS)); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(answer, answer.startsWith("Error:")); //$NON-NLS-1$
        assertTrue("both ways to ask are named", //$NON-NLS-1$
            answer.contains("methodName") && answer.contains("methodBatch")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("nothing was read", 0, PROBE.reads); //$NON-NLS-1$
    }

    /**
     * The single {@code methodName} route keeps its exact bytes: one read, the same front matter
     * and the same body the tool has always returned.
     */
    @Test
    public void theSingleNameRouteStaysByteIdentical()
    {
        String answer = new MethodSourceReader().execute(args("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", ADDRESS, "methodName", "Сложить")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(SINGLE_NAME_ANSWER, answer);
        assertEquals(1, PROBE.reads);
    }

    private static String batchAnswer(String modulePath, String... names)
    {
        return new MethodSourceReader().execute(args("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", modulePath, "methodBatch", jsonArray(names))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String rawBatchAnswer(String modulePath, String raw)
    {
        return new MethodSourceReader().execute(args("projectName", PROJECT, //$NON-NLS-1$
            "modulePath", modulePath, "methodBatch", raw)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String jsonArray(String... names)
    {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < names.length; i++)
        {
            if (i > 0)
            {
                builder.append(',');
            }
            builder.append('"').append(names[i].replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\"", "\\\"")).append('"'); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return builder.append(']').toString();
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> args = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            args.put(pairs[i], pairs[i + 1]);
        }
        return args;
    }

    private static int count(String haystack, String needle)
    {
        int total = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0)
        {
            total++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return total;
    }

    private static IFile fileOf(String modulePath)
    {
        assertNotNull(project);
        return project.getFile("src/" + modulePath); //$NON-NLS-1$
    }

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
}
