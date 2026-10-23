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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.EditorBuffer;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceWriter;

/**
 * A text file buffer over {@code Form.oform} that was edited and not saved is not a change on
 * disk, so no comparison of the file sees it: the container is written as a whole, the write
 * would replace the text the editor holds, and a save of the editor would then replace the write.
 * Both writers of the container therefore refuse while such a buffer is dirty - the module write
 * the module tools route to the container, and the layout write of {@code ordinary_forms} - each
 * naming the container and leaving the file's bytes and the editor's text as they were. A buffer
 * that is clean, and a container nothing holds open, are no obstacle: the write lands.
 *
 * <p>The buffer is a real one, connected through {@code FileBuffers} over the workspace file and
 * edited through its document; the refusal is what the module writer and the layout tool answer,
 * and what the file and the document hold afterwards is what decides the test.</p>
 */
public class ADirtyEditorRefusesTheContainerWriteTest
{
    private static final String PROJECT = "AiEdtOformDirtyProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String ITEM_FORM = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    private static final String ITEM_CONTAINER = "Catalogs/Products/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private static final String FORM_FQN = "Catalog.Products.Form.ItemForm"; //$NON-NLS-1$

    private static final String CAPTION_ELEMENT = "НадписьНаименование"; //$NON-NLS-1$

    private static final String CAPTION_VALUE = "Название:"; //$NON-NLS-1$

    /** A line of the module the container holds, and the one a write replaces it with. */
    private static final String WRITTEN_BEFORE = "Сообщить(\"Из файла\");"; //$NON-NLS-1$

    private static final String WRITTEN_AFTER = "Сообщить(\"После записи\");"; //$NON-NLS-1$

    /** The module the container holds: the one the write above is computed from. */
    private static final String FILE_MODULE = "Процедура ПриОткрытии()" + CRLF //$NON-NLS-1$
        + "\t" + WRITTEN_BEFORE + CRLF //$NON-NLS-1$
        + "КонецПроцедуры" + CRLF; //$NON-NLS-1$

    /** The module the editor holds: what the file does not have, and what a write would replace. */
    private static final String EDITOR_MODULE = "Процедура ПриОткрытии()" + CRLF //$NON-NLS-1$
        + "\tСообщить(\"Из редактора\");" + CRLF //$NON-NLS-1$
        + "КонецПроцедуры" + CRLF; //$NON-NLS-1$

    private static String layoutText;

    private static Path root;

    private static Path container;

    private static IFile containerFile;

    private static IProject project;

    private static OrdinaryFormModuleProvider registeredHere;

    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-dirty"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path form = projectDir.resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        container = form.resolve("Form.oform"); //$NON-NLS-1$
        Files.write(container, container(FILE_MODULE));
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        containerFile = project.getFile("src/" + ITEM_CONTAINER); //$NON-NLS-1$
        if (ModuleSources.providers().stream().noneMatch(OrdinaryFormModuleProvider.class::isInstance))
        {
            registeredHere = new OrdinaryFormModuleProvider();
            ModuleSources.register(registeredHere);
        }
    }

    @AfterClass
    public static void theProjectGoes() throws Exception
    {
        if (registeredHere != null)
        {
            ModuleSources.unregister(registeredHere);
        }
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

    /** Every test starts from the container as the suite wrote it, and holds nothing open. */
    @Before
    public void theContainerHoldsTheModuleTheWriteIsComputedFrom() throws Exception
    {
        Files.write(container, container(FILE_MODULE));
        project.refreshLocal(IProject.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /** Nothing is left connected for the next test, whatever this one did. */
    @After
    public void nothingHoldsTheContainerOpen() throws Exception
    {
        disconnect();
    }

    /**
     * A container held by an editor with unsaved changes is refused by the module write: the
     * answer names the container and the editor, the file keeps its bytes and the editor keeps
     * its text.
     */
    @Test
    public void aDirtyEditorRefusesTheModuleWrite() throws Exception
    {
        byte[] asWritten = Files.readAllBytes(container);
        ITextFileBuffer buffer = connect();
        buffer.getDocument().set(EDITOR_MODULE);
        assertTrue("the buffer was edited and is dirty", buffer.isDirty()); //$NON-NLS-1$
        assertTrue("the guard reads the buffer as unsaved", EditorBuffer.hasUnsavedChanges(containerFile)); //$NON-NLS-1$

        String answer = new ModuleSourceWriter().execute(args("projectName", PROJECT, "modulePath", ITEM_FORM, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "mode", "searchReplace", "oldSource", WRITTEN_BEFORE, "source", WRITTEN_AFTER)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(answer, answer.contains("unsaved changes")); //$NON-NLS-1$
        assertTrue(answer, answer.contains(ITEM_CONTAINER));
        assertFalse(answer, answer.contains("status: success")); //$NON-NLS-1$

        assertArrayEquals("the file keeps its bytes", asWritten, Files.readAllBytes(container)); //$NON-NLS-1$
        assertEquals("the editor keeps its text", EDITOR_MODULE, buffer.getDocument().get()); //$NON-NLS-1$
        assertTrue(buffer.isDirty());
    }

    /**
     * A container held by an editor with unsaved changes is refused by the layout write: the
     * answer names the container and the editor, the file keeps its bytes and the editor keeps
     * its text.
     */
    @Test
    public void aDirtyEditorRefusesTheLayoutWrite() throws Exception
    {
        byte[] asWritten = Files.readAllBytes(container);
        ITextFileBuffer buffer = connect();
        buffer.getDocument().set(EDITOR_MODULE);
        assertTrue(buffer.isDirty());

        String answer = new OrdinaryFormsTool().execute(Map.of("operation", "set_property", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", FORM_FQN, "property", "caption", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "elementName", CAPTION_ELEMENT, "value", CAPTION_VALUE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = json.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("unsaved changes")); //$NON-NLS-1$
        assertTrue(error, error.contains(ITEM_CONTAINER));

        assertArrayEquals("the file keeps its bytes", asWritten, Files.readAllBytes(container)); //$NON-NLS-1$
        assertEquals("the editor keeps its text", EDITOR_MODULE, buffer.getDocument().get()); //$NON-NLS-1$
        assertTrue(buffer.isDirty());
    }

    /**
     * A buffer that holds what the file holds is no obstacle: the module write lands, the file
     * carries the new text and the editor was not in the way.
     */
    @Test
    public void aCleanEditorLetsTheModuleWriteLand() throws Exception
    {
        ITextFileBuffer buffer = connect();
        assertFalse("a buffer nothing was typed into holds what the file holds", buffer.isDirty()); //$NON-NLS-1$
        assertFalse(EditorBuffer.hasUnsavedChanges(containerFile));

        String answer = new ModuleSourceWriter().execute(args("projectName", PROJECT, "modulePath", ITEM_FORM, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "mode", "searchReplace", "oldSource", WRITTEN_BEFORE, "source", WRITTEN_AFTER)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        String module = OrdinaryFormFile.read(container).moduleText();
        assertTrue(module, module.contains(WRITTEN_AFTER));
        assertFalse(module, module.contains(WRITTEN_BEFORE));
    }

    /**
     * A container nothing holds open - the state a write outside an editor sees - is no obstacle
     * either: the layout write the dirty editor was refused with lands, and the layout carries
     * the new caption.
     */
    @Test
    public void aContainerNothingHoldsOpenTakesTheLayoutWrite() throws Exception
    {
        assertFalse("nothing is connected over the container", EditorBuffer.hasUnsavedChanges(containerFile)); //$NON-NLS-1$

        String answer = new OrdinaryFormsTool().execute(Map.of("operation", "set_property", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", FORM_FQN, "property", "caption", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "elementName", CAPTION_ELEMENT, "value", CAPTION_VALUE)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer, json.get("written").getAsBoolean()); //$NON-NLS-1$
        String layout = OrdinaryFormFile.read(container).formText();
        assertTrue(layout, layout.contains("\"" + CAPTION_VALUE + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Refuses an import that would replace a container with unsaved editor changes. */
    @Test
    public void aDirtyEditorRefusesTheImportWrite() throws Exception
    {
        assertDirtyWriteRefused(() -> new OrdinaryFormImport.ProjectSpace(project).write(ITEM_CONTAINER,
            Files.readAllBytes(container), container(FILE_MODULE.replace(WRITTEN_BEFORE, WRITTEN_AFTER))));
    }

    /** Refuses the final creation write if its destination has an unsaved editor buffer. */
    @Test
    public void aDirtyEditorRefusesTheCreationWrite() throws Exception
    {
        assertDirtyWriteRefused(() -> creationFiles().write("src/" + ITEM_CONTAINER, //$NON-NLS-1$
            container(FILE_MODULE.replace(WRITTEN_BEFORE, WRITTEN_AFTER))));
    }

    /** Allows the import writer to replace a container whose connected buffer is clean. */
    @Test
    public void aCleanEditorAllowsTheImportWrite() throws Exception
    {
        assertFalse(connect().isDirty());
        byte[] replacement = container(FILE_MODULE.replace(WRITTEN_BEFORE, WRITTEN_AFTER));
        new OrdinaryFormImport.ProjectSpace(project).write(ITEM_CONTAINER, Files.readAllBytes(container), replacement);
        assertArrayEquals(replacement, Files.readAllBytes(container));
    }

    /** Allows the final creation writer to replace a container whose connected buffer is clean. */
    @Test
    public void aCleanEditorAllowsTheCreationWrite() throws Exception
    {
        assertFalse(connect().isDirty());
        byte[] replacement = container(FILE_MODULE.replace(WRITTEN_BEFORE, WRITTEN_AFTER));
        creationFiles().write("src/" + ITEM_CONTAINER, replacement); //$NON-NLS-1$
        assertArrayEquals(replacement, Files.readAllBytes(container));
    }

    /**
     * Calls the real writer and verifies that a dirty buffer and the disk bytes are preserved.
     *
     * @param write the workspace write to attempt
     * @throws Exception when setup fails or the writer does not return the expected refusal
     */
    private static void assertDirtyWriteRefused(ContainerWrite write) throws Exception
    {
        byte[] before = Files.readAllBytes(container);
        ITextFileBuffer buffer = connect();
        buffer.getDocument().set(EDITOR_MODULE);
        assertTrue(buffer.isDirty());
        try
        {
            write.run();
            throw new AssertionError("the write replaced a container with unsaved editor changes"); //$NON-NLS-1$
        }
        catch (IOException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("unsaved changes")); //$NON-NLS-1$
            assertTrue(refused.getMessage(), refused.getMessage().contains(ITEM_CONTAINER));
        }
        assertArrayEquals(before, Files.readAllBytes(container));
        assertEquals(EDITOR_MODULE, buffer.getDocument().get());
        assertTrue(buffer.isDirty());
    }

    /**
     * Creates the production creation writer without invoking the metadata creation stage.
     *
     * @return the workspace container writer used by form creation
     * @throws Exception when its constructor cannot be resolved
     */
    private static OrdinaryFormCreation.ContainerWriter creationFiles() throws Exception
    {
        Class<?> type = Class.forName(OrdinaryFormCreation.class.getName() + "$ProjectFiles"); //$NON-NLS-1$
        var constructor = type.getDeclaredConstructor(IProject.class);
        constructor.setAccessible(true);
        return (OrdinaryFormCreation.ContainerWriter)constructor.newInstance(project);
    }

    /** A real container-write operation that may report a checked refusal. */
    @FunctionalInterface
    private interface ContainerWrite
    {
        /** Performs the workspace write. */
        void run() throws Exception;
    }

    /**
     * Connects the container's own text file buffer, as an editor does when it opens the file.
     *
     * @return the buffer over {@code Form.oform}
     * @throws Exception when the runtime has no buffer manager or the connection is refused
     */
    private static ITextFileBuffer connect() throws Exception
    {
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        assertNotNull("the runtime has a text file buffer manager", manager); //$NON-NLS-1$
        manager.connect(containerFile.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
        ITextFileBuffer buffer = manager.getTextFileBuffer(containerFile.getFullPath(), LocationKind.IFILE);
        assertNotNull("the container's buffer is connected", buffer); //$NON-NLS-1$
        return buffer;
    }

    /**
     * Disconnects the container's buffer, whatever it holds; the file on disk is left alone.
     *
     * @throws Exception when the runtime has no buffer manager or the disconnect is refused
     */
    private static void disconnect() throws Exception
    {
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        if (containerFile == null || manager == null)
        {
            return;
        }
        if (manager.getTextFileBuffer(containerFile.getFullPath(), LocationKind.IFILE) != null)
        {
            manager.disconnect(containerFile.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
        }
    }

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static byte[] container(String module)
    {
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("form", withBom(layoutText))); //$NON-NLS-1$
        container.add(V8Container.Entry.of("module", withBom(module))); //$NON-NLS-1$
        return container.write();
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
