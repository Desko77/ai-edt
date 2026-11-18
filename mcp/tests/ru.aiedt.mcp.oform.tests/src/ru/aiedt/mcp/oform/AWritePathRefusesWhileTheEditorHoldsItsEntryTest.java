/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.session.FormEditingSession;
import ru.aiedt.mcp.oform.session.OpenFormSessions;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceWriter;

/**
 * The four paths that write a form container - the layout write of {@code ordinary_forms}, the
 * module write of the module tools, the import's write and the creation's write - each refuse
 * while a live session of the form editor holds the entry the path replaces changed: the
 * container stays byte for byte as it was, and the refusal names it and the unsaved changes. A
 * session that holds nothing changed is no obstacle to any of them, and the module write passes
 * while the session holds nothing but layout changes - it replaces the module entry only.
 */
public class AWritePathRefusesWhileTheEditorHoldsItsEntryTest
{
    private static final String PROJECT = "AiEdtOformHeldEntryProbe"; //$NON-NLS-1$

    private static final String ITEM_FORM = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    private static final String ITEM_CONTAINER = "Catalogs/Products/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private static final String FORM_FQN = "Catalog.Products.Form.ItemForm"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String WRITTEN_BEFORE = "Сообщить(\"Из файла\");"; //$NON-NLS-1$

    private static final String WRITTEN_AFTER = "Сообщить(\"После записи\");"; //$NON-NLS-1$

    private static final String FILE_MODULE = "Процедура ПриОткрытии()" + CRLF //$NON-NLS-1$
        + "\t" + WRITTEN_BEFORE + CRLF //$NON-NLS-1$
        + "КонецПроцедуры" + CRLF; //$NON-NLS-1$

    private static String layoutText;

    private static Path root;

    private static Path container;

    private static IFile containerFile;

    private static IProject project;

    private static OrdinaryFormModuleProvider registeredHere;

    private static String table;

    private FormEditingSession session;

    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        for (FormLayout.Element element : LayoutReader.read(BraceTree.parse(layoutText), "Catalog").allElements()) //$NON-NLS-1$
        {
            if ("Table".equals(element.type) && element.name != null) //$NON-NLS-1$
            {
                table = element.name;
                break;
            }
        }
        assertNotNull("the fixture names a table", table); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-held"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src/Catalogs/Products/Forms/ItemForm")); //$NON-NLS-1$
        container = projectDir.resolve("src").resolve(ITEM_CONTAINER); //$NON-NLS-1$
        Files.write(container, containerOf(FILE_MODULE));
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

    /** Every test starts from the container as the suite wrote it and holds nothing open. */
    @Before
    public void theContainerHoldsTheModule() throws Exception
    {
        releaseSession();
        Files.write(container, containerOf(FILE_MODULE));
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /** Nothing is left held open for the next test. */
    @After
    public void nothingIsHeldOpen()
    {
        releaseSession();
    }

    /** A session that holds the layout changed refuses the layout write of the tool. */
    @Test
    public void theLayoutWriteIsRefusedAtAChangedLayoutEntry() throws Exception
    {
        byte[] asWritten = aSessionHoldsTheLayoutChanged();
        String answer = new OrdinaryFormsTool().execute(Map.of("operation", "set_property", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", FORM_FQN, "property", "title", "value", "Другой заголовок")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertFalse(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = json.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error, error.contains("unsaved changes")); //$NON-NLS-1$
        assertTrue(error, error.contains(ITEM_CONTAINER));
        assertArrayEquals("the container keeps its bytes", asWritten, Files.readAllBytes(container)); //$NON-NLS-1$
        assertTrue("the session keeps its edit", session.formChanged()); //$NON-NLS-1$
    }

    /** A session that holds the module changed refuses the module write of the module tools. */
    @Test
    public void theModuleWriteIsRefusedAtAChangedModuleEntry() throws Exception
    {
        byte[] asWritten = aSessionHoldsTheModuleChanged();
        String answer = new ModuleSourceWriter().execute(args("projectName", PROJECT, "modulePath", ITEM_FORM, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "mode", "searchReplace", "oldSource", WRITTEN_BEFORE, "source", WRITTEN_AFTER)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(answer, answer.contains("unsaved changes")); //$NON-NLS-1$
        assertTrue(answer, answer.contains(ITEM_CONTAINER));
        assertFalse(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertArrayEquals("the container keeps its bytes", asWritten, Files.readAllBytes(container)); //$NON-NLS-1$
        assertTrue("the session keeps its module edit", session.moduleChanged()); //$NON-NLS-1$
    }

    /** A session that holds either entry changed refuses the import's write at its seam. */
    @Test
    public void theImportWriteIsRefusedAtAChangedEntry() throws Exception
    {
        byte[] asWritten = aSessionHoldsTheLayoutChanged();
        byte[] replacement = containerOf(FILE_MODULE.replace(WRITTEN_BEFORE, WRITTEN_AFTER));
        try
        {
            new OrdinaryFormImport.ProjectSpace(project).write(ITEM_CONTAINER, Files.readAllBytes(container),
                replacement);
            throw new AssertionError("the import replaced a container a session held changed"); //$NON-NLS-1$
        }
        catch (IOException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("unsaved changes")); //$NON-NLS-1$
            assertTrue(refused.getMessage(), refused.getMessage().contains(ITEM_CONTAINER)); //$NON-NLS-1$
        }
        assertArrayEquals("the container keeps its bytes", asWritten, Files.readAllBytes(container)); //$NON-NLS-1$
    }

    /** A session that holds either entry changed refuses the creation's write at its seam. */
    @Test
    public void theCreationWriteIsRefusedAtAChangedEntry() throws Exception
    {
        byte[] asWritten = aSessionHoldsTheLayoutChanged();
        byte[] replacement = containerOf(FILE_MODULE.replace(WRITTEN_BEFORE, WRITTEN_AFTER));
        try
        {
            creationFiles().write("src/" + ITEM_CONTAINER, replacement); //$NON-NLS-1$
            throw new AssertionError("the creation replaced a container a session held changed"); //$NON-NLS-1$
        }
        catch (IOException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("unsaved changes")); //$NON-NLS-1$
            assertTrue(refused.getMessage(), refused.getMessage().contains(ITEM_CONTAINER)); //$NON-NLS-1$
        }
        assertArrayEquals("the container keeps its bytes", asWritten, Files.readAllBytes(container)); //$NON-NLS-1$
    }

    /** A session that holds nothing changed is no obstacle: the layout write lands. */
    @Test
    public void aCleanSessionLetsTheLayoutWriteLand() throws Exception
    {
        session = OpenFormSessions.open(containerFile);
        assertFalse(session.isDirty());
        String answer = new OrdinaryFormsTool().execute(Map.of("operation", "set_property", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", FORM_FQN, "property", "title", "value", "Другой заголовок")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer, json.get("written").getAsBoolean()); //$NON-NLS-1$
        assertTrue(OrdinaryFormFile.read(container).formText().contains("Другой заголовок")); //$NON-NLS-1$
    }

    /**
     * A session that holds nothing but layout changes lets the module write land: the write
     * replaces the module entry only, the file's layout entry is what the session's baseline
     * holds, and the session keeps its edit.
     */
    @Test
    public void theModuleWritePassesAtAFormOnlySession() throws Exception
    {
        aSessionHoldsTheLayoutChanged();
        byte[] formEntry = OrdinaryFormFile.read(container).formText().getBytes(StandardCharsets.UTF_8);
        String answer = new ModuleSourceWriter().execute(args("projectName", PROJECT, "modulePath", ITEM_FORM, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "mode", "searchReplace", "oldSource", WRITTEN_BEFORE, "source", WRITTEN_AFTER)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(answer, answer.contains("status: success")); //$NON-NLS-1$
        assertTrue("the write's answer names the editor that still holds its layout edit", //$NON-NLS-1$
            answer.contains("unsavedInEditor")); //$NON-NLS-1$
        OrdinaryFormFile written = OrdinaryFormFile.read(container);
        assertTrue(written.moduleText(), written.moduleText().contains(WRITTEN_AFTER));
        assertArrayEquals("the layout entry keeps its bytes", formEntry, //$NON-NLS-1$
            written.formText().getBytes(StandardCharsets.UTF_8));
        assertTrue("the session keeps its edit", session.formChanged()); //$NON-NLS-1$
    }

    /** Opens the session and fixes one layout edit into it, over the container as written. */
    private byte[] aSessionHoldsTheLayoutChanged()
    {
        session = OpenFormSessions.open(containerFile);
        FormEditingSession.Edit edit = session.edit("set_property", //$NON-NLS-1$
            Map.of("property", "title", "value", "Заголовок из редактора")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(edit.message, edit.done);
        assertTrue(session.formChanged());
        assertFalse(session.moduleChanged());
        return theContainersBytes();
    }

    /** Opens the session and fixes one module edit into it, over the container as written. */
    private byte[] aSessionHoldsTheModuleChanged()
    {
        session = OpenFormSessions.open(containerFile);
        FormEditingSession.Edit edit = session.edit("bind_handler", //$NON-NLS-1$
            Map.of("elementName", table, "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "procedure", "ОбработчикИзРедактора", "stub", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(edit.message, edit.done);
        assertTrue("the stub reached the session's module", session.moduleChanged()); //$NON-NLS-1$
        return theContainersBytes();
    }

    private byte[] theContainersBytes()
    {
        try
        {
            return Files.readAllBytes(container);
        }
        catch (IOException e)
        {
            throw new UncheckedIOExceptionOf(e);
        }
    }

    private void releaseSession()
    {
        if (session != null)
        {
            OpenFormSessions.release(session);
            session = null;
        }
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

    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static byte[] containerOf(String module)
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

    /** The container's bytes, or the failure as an assertion. */
    private static final class UncheckedIOExceptionOf
        extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        UncheckedIOExceptionOf(IOException cause)
        {
            super(cause);
        }
    }
}
