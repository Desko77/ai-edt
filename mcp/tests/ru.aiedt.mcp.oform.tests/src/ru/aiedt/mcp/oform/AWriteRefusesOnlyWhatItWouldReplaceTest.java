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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.ops.OperationResult;
import ru.aiedt.mcp.oform.ops.OperationSpec;

/**
 * A write is refused only for the entry it would replace, and a write that lands becomes the state
 * the next one is compared against. A module write leaves a layout entry, an unknown entry or a
 * module another writer changed untouched; a layout edit leaves a module and an unknown entry the
 * other writer changed; an entry write that replaced the module lets the next module write land,
 * and a second save of one reader lands too. What is compared is the entry about to be replaced,
 * so an ordinary write of a form keeps working while other writers are on it.
 *
 * <p>The other writer is a real file write followed by a workspace refresh, made inside an
 * operation handler - after the container was read and before it is written. Nothing here waits on
 * a clock or on a thread.</p>
 */
public class AWriteRefusesOnlyWhatItWouldReplaceTest
{
    private static final String PROJECT = "AiEdtOformSaveScopeProbe"; //$NON-NLS-1$

    private static final String FQN = "Catalog.Products.Form.ItemForm"; //$NON-NLS-1$

    private static final String MODULE_ADDRESS = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    private static final String CONTAINER_PATH = "src/Catalogs/Products/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String MODULE = "Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    /** The module another writer leaves in the container. */
    private static final String OUTSIDE_MODULE = "Процедура Снаружи()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    /** The module an operation would write. */
    private static final String EDITED_MODULE = "Процедура ИзОперации()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    /** An entry no operation of this server reads or writes. */
    private static final String UNKNOWN_ENTRY = "metadata"; //$NON-NLS-1$

    private static final byte[] UNKNOWN_BYTES = new byte[] { 0x00, 0x01, 0x02, (byte)0xFF, 0x0A, 0x0D, 0x00 };

    /** The bytes the other writer leaves in the unknown entry. */
    private static final byte[] OUTSIDE_UNKNOWN_BYTES = new byte[] { 0x07, (byte)0xFE, 'm', 'e', 't', 'a', 0x00 };

    /** The title the fixture's layout carries, and what another writer puts there instead. */
    private static final String TITLE = "Карточка товара"; //$NON-NLS-1$

    private static final String OUTSIDE_TITLE = "Снаружи"; //$NON-NLS-1$

    /** The title the operation sets. */
    private static final String EDITED_TITLE = "Изменено операцией"; //$NON-NLS-1$

    private static String layoutText;

    /** The fixture's layout with another title in it - what another writer leaves behind. */
    private static String outsideLayoutText;

    private static Path root;

    private static Path container;

    private static IProject project;

    private static byte[] original;

    @BeforeClass
    public static void aProjectWithOneOrdinaryFormOfThreeEntries() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        outsideLayoutText = layoutText.replace(TITLE, OUTSIDE_TITLE);
        assertFalse("the fixture carries the title this test moves", outsideLayoutText.equals(layoutText)); //$NON-NLS-1$
        original = containerOf(layoutText, MODULE, UNKNOWN_BYTES);
        root = Files.createTempDirectory("aiedt-oform-scope"); //$NON-NLS-1$
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

    /** A module write lands and leaves the layout the other writer changed. */
    @Test
    public void aModuleWriteKeepsTheLayoutAnotherWriterChanged() throws Exception
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, MODULE_ADDRESS);
        assertNotNull(module);
        module.lines();
        try
        {
            anotherWriterWrites(containerOf(outsideLayoutText, MODULE, UNKNOWN_BYTES));
            assertTrue("the module write lands", module.write(List.of("Процедура Новая()", "КонецПроцедуры"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            OrdinaryFormFile after = OrdinaryFormFile.read(container);
            assertEquals(outsideLayoutText, after.formText());
            assertTrue(after.moduleText().startsWith("Процедура Новая()")); //$NON-NLS-1$
            assertArrayEquals("the unknown entry keeps its bytes", UNKNOWN_BYTES, //$NON-NLS-1$
                after.getContainer().find(UNKNOWN_ENTRY).getData());
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /** A module write lands and leaves the unknown entry the other writer changed. */
    @Test
    public void aModuleWriteKeepsAnUnknownEntryAnotherWriterChanged() throws Exception
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, MODULE_ADDRESS);
        assertNotNull(module);
        module.lines();
        try
        {
            anotherWriterWrites(containerOf(layoutText, MODULE, OUTSIDE_UNKNOWN_BYTES));
            assertTrue("the module write lands", module.write(List.of("Процедура Новая()", "КонецПроцедуры"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            OrdinaryFormFile after = OrdinaryFormFile.read(container);
            assertEquals(layoutText, after.formText());
            assertTrue(after.moduleText().startsWith("Процедура Новая()")); //$NON-NLS-1$
            assertArrayEquals("the change of the other writer is what the entry holds", OUTSIDE_UNKNOWN_BYTES, //$NON-NLS-1$
                after.getContainer().find(UNKNOWN_ENTRY).getData());
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /** A layout edit lands and leaves the module the other writer changed. */
    @Test
    public void aLayoutEditKeepsTheModuleAnotherWriterChanged() throws Exception
    {
        try
        {
            OperationSpec edit = OperationSpec.layout("test_set_title", "a test edit", List.of(), context -> //$NON-NLS-1$ //$NON-NLS-2$
            {
                anotherWriterWrites(containerOf(layoutText, OUTSIDE_MODULE, UNKNOWN_BYTES));
                return OperationResult.of(context.editor.setTitle(EDITED_TITLE));
            });
            String answer = LayoutOperations.run(edit, project, Map.of("formFqn", FQN)); //$NON-NLS-1$
            JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
            assertTrue(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(answer, json.get("written").getAsBoolean()); //$NON-NLS-1$
            OrdinaryFormFile after = OrdinaryFormFile.read(container);
            assertTrue(after.formText().contains(EDITED_TITLE));
            assertEquals("the module of the other writer survives", OUTSIDE_MODULE, after.moduleText()); //$NON-NLS-1$
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /** A layout edit lands and leaves the unknown entry the other writer changed. */
    @Test
    public void aLayoutEditKeepsAnUnknownEntryAnotherWriterChanged() throws Exception
    {
        try
        {
            OperationSpec edit = OperationSpec.layout("test_set_title", "a test edit", List.of(), context -> //$NON-NLS-1$ //$NON-NLS-2$
            {
                anotherWriterWrites(containerOf(layoutText, MODULE, OUTSIDE_UNKNOWN_BYTES));
                return OperationResult.of(context.editor.setTitle(EDITED_TITLE));
            });
            String answer = LayoutOperations.run(edit, project, Map.of("formFqn", FQN)); //$NON-NLS-1$
            JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
            assertTrue(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(answer, json.get("written").getAsBoolean()); //$NON-NLS-1$
            OrdinaryFormFile after = OrdinaryFormFile.read(container);
            assertTrue(after.formText().contains(EDITED_TITLE));
            assertEquals(MODULE, after.moduleText());
            assertArrayEquals("the change of the other writer is what the entry holds", OUTSIDE_UNKNOWN_BYTES, //$NON-NLS-1$
                after.getContainer().find(UNKNOWN_ENTRY).getData());
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /** A write of the module that is already there writes nothing, and the file keeps its bytes. */
    @Test
    public void aWriteOfTheModuleThatIsAlreadyThereChangesNothing() throws IOException
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, MODULE_ADDRESS);
        assertNotNull(module);
        List<String> lines = module.lines();
        long stamp = Files.getLastModifiedTime(container).toMillis();
        assertFalse(module.write(lines));
        assertEquals(stamp, Files.getLastModifiedTime(container).toMillis());
        assertArrayEquals(original, Files.readAllBytes(container));
    }

    /** A landed write becomes the state the next one is compared against. */
    @Test
    public void aSecondWriteOnTheSameModuleLandsAsWell() throws Exception
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, MODULE_ADDRESS);
        assertNotNull(module);
        module.lines();
        try
        {
            assertTrue(module.write(List.of("Процедура Первая()", "КонецПроцедуры"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(module.write(List.of("Процедура Первая()", "КонецПроцедуры"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(module.write(List.of("Процедура Вторая()", "КонецПроцедуры"))); //$NON-NLS-1$ //$NON-NLS-2$
            OrdinaryFormFile after = OrdinaryFormFile.read(container);
            assertTrue(after.moduleText().contains("Процедура Вторая()")); //$NON-NLS-1$
            assertEquals(layoutText, after.formText());
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /**
     * An entry write that replaced the module becomes the state the next module write is compared
     * against: the module write lands, rather than being refused for what this instance wrote
     * itself.
     */
    @Test
    public void aModuleWriteAfterAnEntryWriteThatReplacedTheModuleLands() throws Exception
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, MODULE_ADDRESS);
        assertNotNull(module);
        module.lines();
        try
        {
            OrdinaryFormFile observed = module.read();
            assertTrue("the entry write lands", module.writeEntries(observed, null, EDITED_MODULE)); //$NON-NLS-1$
            assertEquals(EDITED_MODULE, OrdinaryFormFile.read(container).moduleText());
            assertTrue("the module write after it lands", //$NON-NLS-1$
                module.write(List.of("Процедура Третья()", "КонецПроцедуры"))); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(OrdinaryFormFile.read(container).moduleText().contains("Процедура Третья()")); //$NON-NLS-1$
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /** A second save of one reader lands: what a write wrote is what the next one compares with. */
    @Test
    public void twoSavesOnOneReaderBothLand() throws Exception
    {
        try
        {
            OrdinaryFormFile read = OrdinaryFormFile.read(container);
            read.setModuleText(EDITED_MODULE);
            read.save();
            read.setModuleText(OUTSIDE_MODULE);
            read.save();
            OrdinaryFormFile after = OrdinaryFormFile.read(container);
            assertEquals(OUTSIDE_MODULE, after.moduleText());
            assertEquals(layoutText, after.formText());
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /** The container with the three entries, as bytes. */
    private static byte[] containerOf(String layout, String module, byte[] unknown)
    {
        V8Container v8 = V8Container.empty();
        v8.add(V8Container.Entry.of(OrdinaryFormFile.FORM_ENTRY, withBom(layout)));
        v8.add(V8Container.Entry.of(OrdinaryFormFile.MODULE_ENTRY, withBom(module)));
        v8.add(V8Container.Entry.of(UNKNOWN_ENTRY, unknown));
        return v8.write();
    }

    /**
     * A change made by something other than this server: the bytes reach the file on disk and the
     * workspace is told about them, so the next read of the container answers with them.
     *
     * @param bytes the container as the other writer leaves it
     */
    private static void anotherWriterWrites(byte[] bytes)
    {
        try
        {
            Files.write(container, bytes);
            projectFile().refreshLocal(IResource.DEPTH_ZERO, null);
        }
        catch (IOException | CoreException e)
        {
            // A layout handler answers with the edit, not with a checked exception.
            throw new UncheckedIOException(new IOException(e.getMessage(), e));
        }
    }

    private static IFile projectFile()
    {
        return project.getFile(new org.eclipse.core.runtime.Path(CONTAINER_PATH));
    }

    /** Puts the file back as the class fixture wrote it, so one test does not feed the next. */
    private static void theContainerIsBackToItsOriginal() throws Exception
    {
        Files.write(container, original);
        projectFile().refreshLocal(IResource.DEPTH_ZERO, null);
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
