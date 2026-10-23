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
 * A write refuses a change another writer made between the read and the write, and refuses it by
 * naming the entry it would have replaced: an edit computed from the layout is refused when the
 * layout entry moved under it, an edit that also carries a module is refused for the module when
 * that entry moved, a module write is refused when the module entry moved, and a whole file write
 * is refused when an entry it does not even name moved, and a container expected to be created is
 * refused when a file is there by then. In each case the container keeps the bytes the other writer
 * left, so what the file holds afterwards is the change that came in between. A read made to inspect
 * the form, in between, does not move what the next write is compared against.
 *
 * <p>The other writer is a real file write followed by a workspace refresh, made in the one place
 * the window exists - inside an operation handler, which runs after the container was read and
 * before it is written. Nothing here waits on a clock or on a thread.</p>
 */
public class AWriteRefusesAChangeItDidNotMakeTest
{
    private static final String PROJECT = "AiEdtOformSaveConflictProbe"; //$NON-NLS-1$

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
        root = Files.createTempDirectory("aiedt-oform-conflict"); //$NON-NLS-1$
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

    /**
     * An edit computed from the layout is refused when the layout entry moved under it, and the
     * file keeps the layout the other writer wrote.
     */
    @Test
    public void theLayoutEditIsRefusedWhenTheLayoutChangedUnderIt() throws Exception
    {
        byte[] outside = containerOf(outsideLayoutText, MODULE, UNKNOWN_BYTES);
        try
        {
            OperationSpec edit = OperationSpec.layout("test_set_title", "a test edit", List.of(), context -> //$NON-NLS-1$ //$NON-NLS-2$
            {
                anotherWriterWrites(outside);
                return OperationResult.of(context.editor.setTitle("Изменено операцией")); //$NON-NLS-1$
            });
            String answer = LayoutOperations.run(edit, project, Map.of("formFqn", FQN)); //$NON-NLS-1$
            JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
            assertFalse(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
            String error = json.get("error").getAsString(); //$NON-NLS-1$
            assertTrue(error, error.contains("the container changed after it was read")); //$NON-NLS-1$
            assertTrue(error, error.contains("layout")); //$NON-NLS-1$
            assertArrayEquals("the change that came in between is what the file holds", outside, //$NON-NLS-1$
                Files.readAllBytes(container));
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /**
     * An edit that carries a module too is refused for the module when that entry moved under it,
     * and the layout of the same edit is not written either.
     */
    @Test
    public void theEditIsRefusedForTheModuleItCarriesWhenTheModuleMoved() throws Exception
    {
        byte[] outside = containerOf(layoutText, OUTSIDE_MODULE, UNKNOWN_BYTES);
        try
        {
            OperationSpec edit = OperationSpec.layout("test_set_title_with_module", "a test edit", List.of(), //$NON-NLS-1$ //$NON-NLS-2$
                context ->
                {
                    anotherWriterWrites(outside);
                    return OperationResult.withModule(context.editor.setTitle("Изменено операцией"), EDITED_MODULE); //$NON-NLS-1$
                });
            String answer = LayoutOperations.run(edit, project, Map.of("formFqn", FQN)); //$NON-NLS-1$
            JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
            assertFalse(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
            String error = json.get("error").getAsString(); //$NON-NLS-1$
            assertTrue(error, error.contains("the container changed after it was read")); //$NON-NLS-1$
            assertTrue(error, error.contains("module")); //$NON-NLS-1$
            assertArrayEquals(outside, Files.readAllBytes(container));
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /**
     * A module write is refused when the module entry moved under it, and the module of the other
     * writer survives.
     */
    @Test
    public void theModuleWriteIsRefusedWhenTheModuleChangedUnderIt() throws Exception
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, MODULE_ADDRESS);
        assertNotNull(module);
        module.lines();
        byte[] outside = containerOf(layoutText, OUTSIDE_MODULE, UNKNOWN_BYTES);
        try
        {
            anotherWriterWrites(outside);
            try
            {
                module.write(List.of("Процедура Новая()", "КонецПроцедуры")); //$NON-NLS-1$ //$NON-NLS-2$
                throw new AssertionError("the write replaced the module of another writer"); //$NON-NLS-1$
            }
            catch (IOException expected)
            {
                assertTrue(expected.getMessage(), //$NON-NLS-1$
                    expected.getMessage().contains("the container changed after it was read")); //$NON-NLS-1$
                assertTrue(expected.getMessage(), expected.getMessage().contains("module")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            assertArrayEquals(outside, Files.readAllBytes(container));
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /**
     * A whole file write is refused when an entry it does not even name moved under it: the file is
     * compared as a whole, so the entry this change writes is not written over the other writer.
     */
    @Test
    public void theFileWriteIsRefusedWhenAnotherEntryChangedUnderIt() throws Exception
    {
        OrdinaryFormFile read = OrdinaryFormFile.read(container);
        read.setModuleText(EDITED_MODULE);
        byte[] outside = containerOf(layoutText, MODULE, OUTSIDE_UNKNOWN_BYTES);
        try
        {
            anotherWriterWrites(outside);
            try
            {
                read.save();
                throw new AssertionError("the write replaced an entry it does not name"); //$NON-NLS-1$
            }
            catch (IOException expected)
            {
                assertTrue(expected.getMessage(), //$NON-NLS-1$
                    expected.getMessage().contains("the file changed after it was read")); //$NON-NLS-1$
            }
            assertArrayEquals(outside, Files.readAllBytes(container));
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /**
     * A read made to inspect the form does not move what the next write is compared against: the
     * module read after the other writer's change is not taken for the state this instance started
     * from, so the write computed from the first read is still refused.
     */
    @Test
    public void theInspectionReadDoesNotMoveWhatTheWriteIsComparedAgainst() throws Exception
    {
        OrdinaryFormModule module = OrdinaryFormModule.locate(project, MODULE_ADDRESS);
        assertNotNull(module);
        module.lines();
        byte[] outside = containerOf(layoutText, OUTSIDE_MODULE, UNKNOWN_BYTES);
        try
        {
            anotherWriterWrites(outside);
            assertEquals(OUTSIDE_MODULE, module.text());
            try
            {
                module.write(List.of("Процедура Новая()", "КонецПроцедуры")); //$NON-NLS-1$ //$NON-NLS-2$
                throw new AssertionError("the inspection read moved what the write is compared against"); //$NON-NLS-1$
            }
            catch (IOException expected)
            {
                assertTrue(expected.getMessage(), //$NON-NLS-1$
                    expected.getMessage().contains("the container changed after it was read")); //$NON-NLS-1$
            }
            assertArrayEquals(outside, Files.readAllBytes(container));
        }
        finally
        {
            theContainerIsBackToItsOriginal();
        }
    }

    /**
     * A container expected to be created is not written over a file that appeared meanwhile: the
     * file of the other writer keeps its bytes.
     */
    @Test
    public void theCreationIsRefusedWhenTheFileAppearedMeanwhile() throws Exception
    {
        try
        {
            OrdinaryFormFile created = OrdinaryFormFile.of(container, containerInMemory());
            byte[] outside = containerOf(outsideLayoutText, MODULE, UNKNOWN_BYTES);
            anotherWriterWrites(outside);
            try
            {
                created.save();
                throw new AssertionError("the write replaced a file it expected to create"); //$NON-NLS-1$
            }
            catch (IOException expected)
            {
                assertTrue(expected.getMessage(), expected.getMessage().contains("the file exists")); //$NON-NLS-1$
            }
            assertArrayEquals(outside, Files.readAllBytes(container));
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
     * The container with the three entries, in memory only - what a creation holds before its file
     * exists.
     *
     * @return the container
     */
    private static V8Container containerInMemory()
    {
        V8Container v8 = V8Container.empty();
        v8.add(V8Container.Entry.of(OrdinaryFormFile.FORM_ENTRY, withBom(layoutText)));
        v8.add(V8Container.Entry.of(OrdinaryFormFile.MODULE_ENTRY, withBom(EDITED_MODULE)));
        v8.add(V8Container.Entry.of(UNKNOWN_ENTRY, UNKNOWN_BYTES));
        return v8;
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
