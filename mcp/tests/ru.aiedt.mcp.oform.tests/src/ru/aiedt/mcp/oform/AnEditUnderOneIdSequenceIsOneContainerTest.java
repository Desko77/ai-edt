/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
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

import ru.aiedt.mcp.oform.LayoutOperations.Applied;
import ru.aiedt.mcp.oform.LayoutOperations.Target;
import ru.aiedt.mcp.oform.LayoutOperations.Written;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutIds;
import ru.aiedt.mcp.oform.ops.OperationSpec;
import ru.aiedt.mcp.oform.ops.OperationsCatalog;

/**
 * Every uuid a layout edit writes comes from {@link LayoutIds}, so an edit run twice under one
 * deterministic sequence - and one write stamp - writes one container byte for byte: a command
 * panel, a procedure button with its stub, a standard action, a submenu with its block and a
 * separator, through the same {@code LayoutOperations.run} the tool calls. The standard actions
 * panel a new form gets is reproduced the same way. Without a sequence the ids stay random.
 *
 * <p>The three steps of {@code run} answer one by one as well: {@code applyTo} edits nothing on
 * disk and hands back the texts, {@code writeBack} writes exactly those, and a refusal - by the
 * handler or on the layout version - comes back without texts.</p>
 */
public class AnEditUnderOneIdSequenceIsOneContainerTest
{
    private static final String PROJECT = "AiEdtOformIdSequenceProbe"; //$NON-NLS-1$

    private static final String FQN = "Catalog.Products.Form.ItemForm"; //$NON-NLS-1$

    private static final String CONTAINER_PATH = "src/Catalogs/Products/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String MODULE = "Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    /** A write stamp, as a form written in 2026 carries it. */
    private static final long STAMP = 637311959090000L;

    /** The prefix of every uuid {@link LayoutIds#sequence()} gives. */
    private static final String SEQUENCE_PREFIX = "00000000-0000-4000-8000-"; //$NON-NLS-1$

    private static final Pattern UUID = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"); //$NON-NLS-1$

    private static String layoutText;

    private static Path root;

    private static Path container;

    private static IProject project;

    private static byte[] original;

    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        V8Container v8 = V8Container.empty();
        v8.add(V8Container.Entry.of(OrdinaryFormFile.FORM_ENTRY, withBom(layoutText)));
        v8.add(V8Container.Entry.of(OrdinaryFormFile.MODULE_ENTRY, withBom(MODULE)));
        original = v8.write();
        root = Files.createTempDirectory("aiedt-oform-ids"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path form = projectDir.resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        container = form.resolve("Form.oform"); //$NON-NLS-1$
        Files.write(container, original);
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

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
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /** Two runs of one chain of panel edits under one sequence and one stamp write the same bytes. */
    @Test
    public void twoRunsUnderOneSequenceWriteOneContainer() throws Exception
    {
        try
        {
            byte[] first = underOneSequence(this::panelEdits);
            theContainerIsBackToItsOriginal();
            byte[] second = underOneSequence(this::panelEdits);
            assertFalse("the edits changed the container", java.util.Arrays.equals(original, first)); //$NON-NLS-1$
            assertArrayEquals("one sequence, one container", first, second); //$NON-NLS-1$

            OrdinaryFormFile written = OrdinaryFormFile.read(container);
            assertTrue("the stub reached the module", written.moduleText().contains("ПечатьНажатие")); //$NON-NLS-1$ //$NON-NLS-2$
            theContainerIsBackToItsOriginal();
            V8Container.stampedAt(STAMP, () -> {
                panelEdits();
                return null;
            });
            String random = OrdinaryFormFile.read(container).formText();
            Set<String> fresh = onlySequenceIdsDiffer(written.formText(), random);
            assertTrue(fresh.contains(SEQUENCE_PREFIX + "000000000001")); //$NON-NLS-1$
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /** Each operation alone, run twice from one container under one sequence, writes the same bytes. */
    @Test
    public void eachOperationAloneIsReproduced() throws Exception
    {
        try
        {
            byte[] first = underOneSequence(() -> run("add_command_panel", panelArgs())); //$NON-NLS-1$
            theContainerIsBackToItsOriginal();
            byte[] second = underOneSequence(() -> run("add_command_panel", panelArgs())); //$NON-NLS-1$
            assertArrayEquals(first, second);

            byte[] withPanel = first;
            List<Map<String, String>> next = List.of(
                args("add_button", "panel", "ПанельДействий", "name", "КнопкаЗакрыть", "action", "close"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
                args("add_submenu", "panel", "ПанельДействий", "name", "ПодменюДействия"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                args("add_separator", "panel", "ПанельДействий", "name", "Разделитель1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            for (Map<String, String> one : next)
            {
                String operation = one.remove("operation"); //$NON-NLS-1$
                anotherWriterWrites(withPanel);
                byte[] a = underOneSequence(() -> run(operation, one));
                anotherWriterWrites(withPanel);
                byte[] b = underOneSequence(() -> run(operation, one));
                assertFalse(operation, java.util.Arrays.equals(withPanel, a));
                assertArrayEquals(operation, a, b);
            }
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /** The standard actions panel of a new form takes its seven ids from the sequence. */
    @Test
    public void theStandardActionsPanelIsReproduced() throws Exception
    {
        String first = LayoutIds.using(LayoutIds.sequence(), this::standardActions);
        String second = LayoutIds.using(LayoutIds.sequence(), this::standardActions);
        assertEquals(first, second);
        Set<String> fresh = onlySequenceIdsDiffer(first, standardActions());
        assertEquals("the panel and its seven actions' ids", 7, fresh.size()); //$NON-NLS-1$
    }

    /** Without a sequence the ids stay random: two edits write two different panels. */
    @Test
    public void withoutASequenceTheIdsAreRandom() throws Exception
    {
        String first = standardActions();
        String second = standardActions();
        assertNotEquals(first, second);
        for (String id : idsOf(first))
        {
            assertFalse(id, id.startsWith(SEQUENCE_PREFIX));
        }
    }

    /** A sequence bound for a piece of work is gone after it, also when the work throws. */
    @Test
    public void aSequenceEndsWithItsWork()
    {
        assertEquals(SEQUENCE_PREFIX + "000000000001", LayoutIds.using(LayoutIds.sequence(), LayoutIds::next)); //$NON-NLS-1$
        assertFalse(LayoutIds.next().startsWith(SEQUENCE_PREFIX));
        try
        {
            LayoutIds.using(LayoutIds.sequence(), () -> {
                throw new IllegalStateException("work fails"); //$NON-NLS-1$
            });
        }
        catch (IllegalStateException expected)
        {
            // the work's own failure
        }
        assertFalse(LayoutIds.next().startsWith(SEQUENCE_PREFIX));
    }

    /** applyTo writes nothing and hands back the texts; writeBack writes exactly those and marks the owner. */
    @Test
    public void applyToEditsNothingOnDiskAndWriteBackWritesItsTexts() throws Exception
    {
        try
        {
            Target target = LayoutOperations.target(project, FQN, null);
            assertEquals(FQN, target.fqn());
            Applied applied = LayoutOperations.applyTo(spec("set_property"), target, //$NON-NLS-1$
                args("set_property", "property", "title", "value", "Новый заголовок")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            assertTrue(applied.message, applied.done);
            assertNull(applied.refusedVersion);
            assertEquals(layoutText, applied.layoutBefore);
            assertTrue(applied.layoutText.contains("Новый заголовок")); //$NON-NLS-1$
            assertNull("the module is left", applied.moduleText); //$NON-NLS-1$
            assertArrayEquals("applyTo wrote nothing", original, Files.readAllBytes(container)); //$NON-NLS-1$

            Written written = LayoutOperations.writeBack(target, applied.layoutText, applied.moduleText);
            assertTrue(written.written);
            assertNotNull(written.delivery);
            OrdinaryFormFile after = OrdinaryFormFile.read(container);
            assertEquals(applied.layoutText, after.formText());
            assertEquals(MODULE, after.moduleText());

            Target again = LayoutOperations.target(project, FQN, null);
            Written nothing = LayoutOperations.writeBack(again, after.formText(), null);
            assertFalse("the same text writes nothing", nothing.written); //$NON-NLS-1$
            assertNull(nothing.delivery);
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /** A refusal by the handler and one on the layout version come back without texts, and run answers both as before. */
    @Test
    public void aRefusalComesBackWithoutTexts() throws Exception
    {
        try
        {
            Applied refused = LayoutOperations.applyTo(spec("set_property"), LayoutOperations.target(project, FQN, null), //$NON-NLS-1$
                args("set_property", "elementName", "НетТакого", "property", "caption", "value", "x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            assertFalse(refused.done);
            assertNull(refused.refusedVersion);
            assertNull(refused.layoutText);
            assertNull(refused.moduleText);

            OrdinaryFormFile old = OrdinaryFormFile.read(container);
            old.setFormText(layoutText.replaceFirst("^\\{27,", "{24,")); //$NON-NLS-1$ //$NON-NLS-2$
            anotherWriterWrites(old.toBytes());
            Applied version = LayoutOperations.applyTo(spec("set_property"), LayoutOperations.target(project, FQN, null), //$NON-NLS-1$
                args("set_property", "property", "title", "value", "x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            assertFalse(version.done);
            assertEquals("24", version.refusedVersion); //$NON-NLS-1$
            assertNull(version.layoutText);

            String answer = LayoutOperations.run(spec("set_property"), project, //$NON-NLS-1$
                Map.of("formFqn", FQN, "property", "title", "value", "x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
            assertFalse(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
            assertEquals(answer, "24", json.get("layoutVersion").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(answer, FQN, json.get("form").getAsString()); //$NON-NLS-1$
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    // ---- the edits --------------------------------------------------------------------------

    /** A command panel, a procedure button with its stub, a standard action, a submenu and a separator. */
    private void panelEdits()
    {
        run("add_command_panel", panelArgs()); //$NON-NLS-1$
        run("add_button", args("add_button", "panel", "ПанельДействий", "name", "КнопкаПечать", "procedure", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "ПечатьНажатие", "stub", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        run("add_button", args("add_button", "panel", "ПанельДействий", "name", "КнопкаЗакрыть", "action", "close")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        run("add_submenu", args("add_submenu", "panel", "ПанельДействий", "name", "ПодменюДействия")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        run("add_separator", args("add_separator", "panel", "ПанельДействий", "name", "Разделитель1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    /**
     * Adds the standard actions panel to a fresh copy of the fixture.
     *
     * @return the layout text after it
     */
    private String standardActions()
    {
        try
        {
            LayoutEditor editor = LayoutEditor.over(BraceTree.parse(layoutText), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            LayoutEditor.Outcome added = editor.addStandardActions("ДействияФормы", new int[] { 8, 450, 742, 475 }); //$NON-NLS-1$
            assertTrue(added.message, added.done);
            return editor.text();
        }
        catch (ru.aiedt.mcp.oform.layout.UnsupportedLayoutVersionException e)
        {
            throw new AssertionError(e.getMessage(), e);
        }
    }

    private static Map<String, String> panelArgs()
    {
        return args("add_command_panel", "name", "ПанельДействий", "left", "8", "top", "8", "width", "400", "height", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
            "25"); //$NON-NLS-1$
    }

    /**
     * Runs one operation the way the tool does and requires it to land.
     *
     * @param operation the operation's name
     * @param arguments its arguments; the form is added
     */
    private static void run(String operation, Map<String, String> arguments)
    {
        Map<String, String> params = new LinkedHashMap<>(arguments);
        params.remove("operation"); //$NON-NLS-1$
        params.put("formFqn", FQN); //$NON-NLS-1$
        params.put("language", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        String answer = LayoutOperations.run(spec(operation), project, params);
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(operation + ": " + answer, json.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(operation + ": " + answer, json.get("written").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static OperationSpec spec(String operation)
    {
        OperationSpec spec = OperationsCatalog.find(operation);
        assertNotNull(operation, spec);
        return spec;
    }

    /**
     * Runs work under a fresh sequence of ids and one write stamp, and answers the container it left.
     *
     * @param work the edits
     * @return the container's bytes after them
     */
    private static byte[] underOneSequence(Runnable work)
    {
        V8Container.stampedAt(STAMP, () -> LayoutIds.using(LayoutIds.sequence(), () -> {
            work.run();
            return null;
        }));
        try
        {
            return Files.readAllBytes(container);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> args(String operation, String... pairs)
    {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("operation", operation); //$NON-NLS-1$
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * Requires two texts of one edit - one under a sequence, one with random ids - to differ only
     * in the ids the edit made: what the sequenced text alone holds comes from the sequence, and
     * the random text holds as many ids of its own, none from a sequence. The ids a template
     * carries as constants are in both and drop out.
     *
     * @param sequenced the text written under a sequence
     * @param random the text of the same edit with random ids
     * @return the ids the sequence gave
     */
    private static Set<String> onlySequenceIdsDiffer(String sequenced, String random)
    {
        Set<String> fresh = idsOf(sequenced);
        fresh.removeAll(idsOf(random));
        Set<String> randomOnly = idsOf(random);
        randomOnly.removeAll(idsOf(sequenced));
        assertFalse("the edit wrote fresh ids", fresh.isEmpty()); //$NON-NLS-1$
        for (String id : fresh)
        {
            assertTrue("an id not from the sequence: " + id, id.startsWith(SEQUENCE_PREFIX)); //$NON-NLS-1$
        }
        for (String id : randomOnly)
        {
            assertFalse(id, id.startsWith(SEQUENCE_PREFIX));
        }
        assertEquals("each sequenced id stands where a random one stood", fresh.size(), randomOnly.size()); //$NON-NLS-1$
        return fresh;
    }

    /**
     * The uuids a layout text holds.
     *
     * @param text the layout text
     * @return the ids, lower case
     */
    private static Set<String> idsOf(String text)
    {
        Set<String> ids = new HashSet<>();
        Matcher matcher = UUID.matcher(text);
        while (matcher.find())
        {
            ids.add(matcher.group().toLowerCase(java.util.Locale.ROOT));
        }
        return ids;
    }

    private static void anotherWriterWrites(byte[] bytes) throws Exception
    {
        Files.write(container, bytes);
        projectFile().refreshLocal(IResource.DEPTH_ZERO, null);
    }

    private static IFile projectFile()
    {
        return project.getFile(new org.eclipse.core.runtime.Path(CONTAINER_PATH));
    }

    private static void theContainerIsBackToItsOriginal() throws Exception
    {
        anotherWriterWrites(original);
    }

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
