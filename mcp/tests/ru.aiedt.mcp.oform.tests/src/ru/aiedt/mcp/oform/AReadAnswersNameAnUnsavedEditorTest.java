/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import ru.aiedt.mcp.oform.session.FormEditingSession;
import ru.aiedt.mcp.oform.session.OpenFormSessions;
import ru.aiedt.mcp.server.support.modules.ModuleSources;
import ru.aiedt.mcp.server.toolkit.ops.ModuleSourceReader;

/**
 * What a read says about the form editor's unsaved state. A session that holds a change makes
 * {@code layout} and {@code inspect_layout} carry {@code unsavedInEditor} and the module read
 * name it in its source note, so a caller that goes on to write knows what the file is about to
 * be reconciled with. A session that holds nothing changed, and no session at all, answer without
 * the mark.
 */
public class AReadAnswersNameAnUnsavedEditorTest
{
    private static final String PROJECT = "AiEdtOformUnsavedProbe"; //$NON-NLS-1$

    private static final String ITEM_CONTAINER = "Catalogs/Products/Forms/ItemForm/Form.oform"; //$NON-NLS-1$

    private static final String ITEM_FORM = "Catalogs/Products/Forms/ItemForm/Module.bsl"; //$NON-NLS-1$

    private static final String FORM_FQN = "Catalog.Products.Form.ItemForm"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String MODULE = "Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    private static String layoutText;

    private static Path root;

    private static Path container;

    private static IFile containerFile;

    private static IProject project;

    private static OrdinaryFormModuleProvider registeredHere;

    private FormEditingSession session;

    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-unsaved"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Files.createDirectories(projectDir.resolve("src/Catalogs/Products/Forms/ItemForm")); //$NON-NLS-1$
        container = projectDir.resolve("src").resolve(ITEM_CONTAINER); //$NON-NLS-1$
        Files.write(container, containerOf(MODULE));
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

    /** Every test starts with the container as the suite wrote it and nothing held open. */
    @Before
    public void theContainerIsWhatTheSuiteWrote() throws Exception
    {
        releaseSession();
        Files.write(container, containerOf(MODULE));
        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
    }

    /** Nothing is left held open for the next test. */
    @After
    public void nothingIsHeldOpen()
    {
        releaseSession();
    }

    /** The layout answer carries the mark while the session holds its change, and loses it after. */
    @Test
    public void theLayoutAnswerNamesAnUnsavedEditor() throws Exception
    {
        session = OpenFormSessions.open(containerFile);
        FormEditingSession.Edit edit = session.edit("set_property", //$NON-NLS-1$
            Map.of("property", "title", "value", "Заголовок из редактора")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(edit.message, edit.done);

        String answer = new OrdinaryFormsTool().execute(Map.of("operation", "layout", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", FORM_FQN)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer, json.has("unsavedInEditor")); //$NON-NLS-1$
        assertTrue(answer, json.get("unsavedInEditor").getAsBoolean()); //$NON-NLS-1$

        OpenFormSessions.release(session);
        session = null;
        String after = new OrdinaryFormsTool().execute(Map.of("operation", "layout", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", FORM_FQN)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject released = JsonParser.parseString(after).getAsJsonObject();
        assertTrue(after, released.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(after, released.has("unsavedInEditor")); //$NON-NLS-1$
    }

    /** The inspection answer carries the mark the same way. */
    @Test
    public void theInspectionAnswerNamesAnUnsavedEditor() throws Exception
    {
        session = OpenFormSessions.open(containerFile);
        FormEditingSession.Edit edit = session.edit("set_property", //$NON-NLS-1$
            Map.of("property", "title", "value", "Заголовок из редактора")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(edit.message, edit.done);

        String answer = new OrdinaryFormsTool().execute(Map.of("operation", "inspect_layout", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", FORM_FQN)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(answer, json.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(answer, json.has("unsavedInEditor")); //$NON-NLS-1$
        assertTrue(answer, json.get("unsavedInEditor").getAsBoolean()); //$NON-NLS-1$
    }

    /**
     * The module read names the unsaved editor in its source note: the address it read by holds
     * a container a session has changed.
     */
    @Test
    public void theModuleAnswerNamesAnUnsavedEditor() throws Exception
    {
        session = OpenFormSessions.open(containerFile);
        FormEditingSession.Edit edit = session.edit("set_property", //$NON-NLS-1$
            Map.of("property", "title", "value", "Заголовок из редактора")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(edit.message, edit.done);

        String answer = new ModuleSourceReader().execute(Map.of("projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", ITEM_FORM)); //$NON-NLS-1$
        assertTrue(answer, answer.contains("unsavedInEditor: true")); //$NON-NLS-1$
        assertTrue(answer, answer.contains("**Source:** oform")); //$NON-NLS-1$

        OpenFormSessions.release(session);
        session = null;
        String after = new ModuleSourceReader().execute(Map.of("projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", ITEM_FORM)); //$NON-NLS-1$
        assertFalse(after, after.contains("unsavedInEditor")); //$NON-NLS-1$
    }

    /** A session that holds nothing changed answers like no session at all: no mark anywhere. */
    @Test
    public void aCleanSessionAnswersWithoutTheMark() throws Exception
    {
        session = OpenFormSessions.open(containerFile);
        assertFalse(session.isDirty());

        String layout = new OrdinaryFormsTool().execute(Map.of("operation", "layout", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", FORM_FQN)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject layoutJson = JsonParser.parseString(layout).getAsJsonObject();
        assertTrue(layout, layoutJson.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(layout, layoutJson.has("unsavedInEditor")); //$NON-NLS-1$

        String inspection = new OrdinaryFormsTool().execute(Map.of("operation", "inspect_layout", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", FORM_FQN)); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject inspectionJson = JsonParser.parseString(inspection).getAsJsonObject();
        assertTrue(inspection, inspectionJson.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse(inspection, inspectionJson.has("unsavedInEditor")); //$NON-NLS-1$

        String module = new ModuleSourceReader().execute(Map.of("projectName", PROJECT, //$NON-NLS-1$ //$NON-NLS-2$
            "modulePath", ITEM_FORM)); //$NON-NLS-1$
        assertFalse(module, module.contains("unsavedInEditor")); //$NON-NLS-1$
    }

    private void releaseSession()
    {
        if (session != null)
        {
            OpenFormSessions.release(session);
            session = null;
        }
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
}
