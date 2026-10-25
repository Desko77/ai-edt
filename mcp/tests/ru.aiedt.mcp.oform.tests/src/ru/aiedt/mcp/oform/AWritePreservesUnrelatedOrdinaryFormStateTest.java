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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.ops.CommandPanelOperations;
import ru.aiedt.mcp.oform.ops.LayoutContext;
import ru.aiedt.mcp.oform.ops.OperationResult;
import ru.aiedt.mcp.oform.ops.OperationSpec;
import ru.aiedt.mcp.server.wire.ToolResult;

/**
 * A write touches what it names and leaves the rest of the form as it was. Unbinding a handler
 * through the {@code unbind_handler} operation removes that binding alone: the container is
 * written, the sibling binding of the same element survives the read-back, and the module that
 * was already there is written back byte for byte. Moving an element that shows data and carries
 * two handlers through {@code move_element} changes where the element stands and nothing else:
 * after the container is written and read again the data path, the box and every handler -
 * event, code and procedure - are the ones the element had, on the page of its new parent. A
 * separator added with {@code add_separator} naming a submenu as its parent reads back among
 * that submenu's nested children, by name and kind, and not beside the panel's own buttons.
 */
public class AWritePreservesUnrelatedOrdinaryFormStateTest
{
    private static final String PROJECT = "AiEdtOformPreserveProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    /** The module the container carries before any call: one procedure, nothing else. */
    private static final String MODULE_TEXT = "Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    private static String layoutText;

    private static Path root;

    private static IProject project;

    private static Path container;

    /**
     * A project with one ordinary form: the fixture layout and the one-procedure module in a
     * container on disk, so the operations run against the workspace as their tests do.
     *
     * @throws Exception when the project cannot be created
     */
    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        layoutText = ALayoutIsReadOffTheContainerTest.fixture("fixtures/item-form-v27.form"); //$NON-NLS-1$
        root = Files.createTempDirectory("aiedt-oform-preserve"); //$NON-NLS-1$
        Path projectDir = root.resolve(PROJECT);
        Path form = projectDir.resolve("src/Catalogs/Products/Forms/ItemForm"); //$NON-NLS-1$
        Files.createDirectories(form);
        container = form.resolve("Form.oform"); //$NON-NLS-1$
        writeFreshContainer();
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        project = workspace.getRoot().getProject(PROJECT);
        IProjectDescription description = workspace.newProjectDescription(PROJECT);
        description.setLocation(new org.eclipse.core.runtime.Path(projectDir.toString()));
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());
    }

    /**
     * Deletes the temporary workspace project and its private directory tree.
     *
     * @throws Exception when the project cannot be deleted
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
                walk.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
            }
        }
    }

    /**
     * {@code unbind_handler} on a binding the fixture carries: the operation answers success and
     * writes, the binding of {@code ПриИзменении} is absent when the container is read back, the
     * sibling binding of {@code Открытие} keeps its procedure, and the module text is exactly the
     * text written before the call.
     *
     * @throws Exception when the container cannot be read or written
     */
    @Test
    public void unbindingAHandlerThroughTheOperationLeavesTheModuleUntouched() throws Exception
    {
        writeFreshContainer();
        FormLayout before = LayoutReader.read(BraceTree.parse(layoutText), "Catalog"); //$NON-NLS-1$
        FormLayout.Element nameBefore = before.elementNamed("Наименование"); //$NON-NLS-1$
        assertNotNull(nameBefore);
        assertEquals(2, nameBefore.handlers.size());
        assertEquals("ПриИзменении", nameBefore.handlers.get(0).event); //$NON-NLS-1$
        String removedProcedure = nameBefore.handlers.get(0).procedure;
        assertEquals("НаименованиеПриИзменении", removedProcedure); //$NON-NLS-1$
        String moduleBefore = MODULE_TEXT + CRLF + "Процедура " + removedProcedure + "(Элемент)" //$NON-NLS-1$ //$NON-NLS-2$
            + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$
        writeFreshContainer(moduleBefore);
        OrdinaryFormFile original = OrdinaryFormFile.read(container);
        assertEquals(moduleBefore, original.moduleText());
        byte[] moduleBytesBefore = original.getContainer().find("module").getData().clone(); //$NON-NLS-1$

        String answer = new OrdinaryFormsTool().execute(Map.of("operation", "unbind_handler", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", "Catalog.Products.Form.ItemForm", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "elementName", "Наименование", "event", "ПриИзменении")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject parsed = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(answer, parsed.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(parsed.get("written").getAsBoolean()); //$NON-NLS-1$

        OrdinaryFormFile written = OrdinaryFormFile.read(container);
        assertEquals("the module retains the procedure of the removed binding and its unrelated procedure", //$NON-NLS-1$
            moduleBefore, written.moduleText());
        assertArrayEquals(moduleBytesBefore, written.getContainer().find("module").getData()); //$NON-NLS-1$
        FormLayout layout = LayoutReader.read(written.formTree(), "Catalog"); //$NON-NLS-1$
        FormLayout.Element name = layout.elementNamed("Наименование"); //$NON-NLS-1$
        assertNotNull(name);
        assertEquals(1, name.handlers.size());
        assertEquals("Открытие", name.handlers.get(0).event); //$NON-NLS-1$
        assertEquals(nameBefore.handlers.get(1).procedure, name.handlers.get(0).procedure);
        assertEquals(nameBefore.handlers.get(1).code, name.handlers.get(0).code);
        for (FormLayout.Handler handler : name.handlers)
        {
            assertFalse("ПриИзменении".equals(handler.event)); //$NON-NLS-1$
        }
    }

    /**
     * {@code move_element} on the fixture's field that shows {@code Объект.Наименование} and
     * answers two events: after the container is written and parsed again the element sits in the
     * panel on the page named and is gone from the form's root, while its data path, its box and
     * both of its handlers - event, code and procedure - read back unchanged.
     *
     * @throws Exception when the container cannot be read or written
     */
    @Test
    public void aMoveThroughTheOperationKeepsTheDataAndTheHandlers() throws Exception
    {
        writeFreshContainer();
        FormLayout before = LayoutReader.read(BraceTree.parse(layoutText), "Catalog", //$NON-NLS-1$
            ALayoutIsReadOffTheContainerTest.NAMES);
        FormLayout.Element source = before.elementNamed("Наименование"); //$NON-NLS-1$
        assertNotNull(source);
        assertEquals("Объект.Наименование", source.data); //$NON-NLS-1$
        assertEquals(2, source.handlers.size());
        List<String> handlers = fingerprints(source.handlers);
        List<Integer> box = List.of(source.box[0], source.box[1], source.box[2], source.box[3]);

        String answer = new OrdinaryFormsTool().execute(Map.of("operation", "move_element", //$NON-NLS-1$ //$NON-NLS-2$
            "projectName", PROJECT, "formFqn", "Catalog.Products.Form.ItemForm", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "elementName", "Наименование", "parent", "Панель1", "page", "Общие")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        JsonObject parsed = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(answer, parsed.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(parsed.get("written").getAsBoolean()); //$NON-NLS-1$

        OrdinaryFormFile written = OrdinaryFormFile.read(container);
        String text = written.formText();
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        FormLayout layout = LayoutReader.read(written.formTree(), "Catalog", ALayoutIsReadOffTheContainerTest.NAMES); //$NON-NLS-1$
        FormLayout.Element moved = layout.elementNamed("Наименование"); //$NON-NLS-1$
        assertNotNull(moved);
        assertEquals("Общие", moved.page); //$NON-NLS-1$
        assertNotNull(childNamed(layout.elementNamed("Панель1"), "Наименование")); //$NON-NLS-1$ //$NON-NLS-2$
        boolean stillOnTheForm = false;
        for (FormLayout.Element element : layout.elements)
        {
            stillOnTheForm |= "Наименование".equals(element.name); //$NON-NLS-1$
        }
        assertFalse("the element left the form's root", stillOnTheForm); //$NON-NLS-1$
        assertEquals("Объект.Наименование", moved.data); //$NON-NLS-1$
        assertEquals("Объект.Наименование", layout.bindings.get("12")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(box, List.of(moved.box[0], moved.box[1], moved.box[2], moved.box[3]));
        assertEquals("both handlers read back with their events, codes and procedures", //$NON-NLS-1$
            handlers, fingerprints(moved.handlers));
    }

    /**
     * {@code add_submenu} gives a panel a submenu with a buttons block of its own, and
     * {@code add_separator} with that submenu as the parent puts the separator into it: the
     * reparsed tree reads the separator back as the submenu's one nested child, with its name and
     * the {@code separator} kind, and the panel's own buttons do not hold it.
     */
    @Test
    public void aSeparatorAddedToASubmenuReadsBackAsItsChild()
    {
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(fixture("fixtures/panel-object-form-v26.form")), //$NON-NLS-1$
            "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        OperationResult submenu = spec("add_submenu").layout.apply(context(editor, //$NON-NLS-1$
            Map.of("panel", "Text7", "name", "ПодменюДействия", "caption", "Действия"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(submenu.outcome.message, submenu.outcome.done);
        OperationResult separator = spec("add_separator").layout.apply(context(editor, //$NON-NLS-1$
            Map.of("panel", "Text7", "name", "Разделитель1", "parent", "ПодменюДействия"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(separator.outcome.message, separator.outcome.done);

        String text = BraceTree.serialize(editor.root());
        assertEquals(text, BraceTree.serialize(BraceTree.parse(text)));
        FormLayout layout = LayoutReader.read(BraceTree.parse(text), "Catalog"); //$NON-NLS-1$
        FormLayout.Element panel = layout.elementNamed("Text7"); //$NON-NLS-1$
        assertNotNull(panel);
        assertNull("the separator is not a button of the panel itself", //$NON-NLS-1$
            buttonNamed(panel, "Разделитель1")); //$NON-NLS-1$
        FormLayout.Button menu = buttonNamed(panel, "ПодменюДействия"); //$NON-NLS-1$
        assertNotNull(menu);
        assertEquals("submenu", menu.details.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
        Object children = menu.details.get("children"); //$NON-NLS-1$
        assertTrue("the submenu reads its nested buttons back as children", children instanceof List); //$NON-NLS-1$
        List<?> nested = (List<?>)children;
        assertEquals(1, nested.size());
        assertTrue(nested.get(0) instanceof Map);
        Map<?, ?> child = (Map<?, ?>)nested.get(0);
        assertEquals("Разделитель1", child.get("name")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("separator", child.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A fresh container in the project's form directory - the fixture layout and the
     * one-procedure module - so every test that goes through the tool starts from the same
     * bytes whatever ran before it.
     *
     * @throws Exception when the container write or workspace refresh fails
     */
    private static void writeFreshContainer() throws Exception
    {
        writeFreshContainer(MODULE_TEXT);
    }

    /**
     * Writes the fixture layout and the supplied existing module, then refreshes the workspace resource.
     *
     * @param moduleText the exact module text to store before the operation
     * @throws Exception when the container write or workspace refresh fails
     */
    private static void writeFreshContainer(String moduleText) throws Exception
    {
        V8Container v8 = V8Container.empty();
        v8.add(V8Container.Entry.of("form", withBom(layoutText))); //$NON-NLS-1$
        v8.add(V8Container.Entry.of("module", withBom(moduleText))); //$NON-NLS-1$
        Files.write(container, v8.write());
        if (project != null && project.exists())
        {
            project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        }
    }

    /**
     * A fixture of the test fragment, as text.
     *
     * @param name the resource name beside {@link ALayoutIsReadOffTheContainerTest}
     * @return the text
     */
    private static String fixture(String name)
    {
        try
        {
            return ALayoutIsReadOffTheContainerTest.fixture(name);
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }
    }

    /**
     * The spec of a command panel operation, off the catalog's own list.
     *
     * @param name the operation's name
     * @return the spec of that name
     */
    private static OperationSpec spec(String name)
    {
        for (OperationSpec one : CommandPanelOperations.specs())
        {
            if (one.name.equals(name))
            {
                return one;
            }
        }
        throw new AssertionError("no operation " + name + " in the command panel catalog"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A layout context over the given editor, with a module entry the handlers may read, built
     * the way the command panel catalog's own tests build one.
     *
     * @param editor the editor over the fixture's tree
     * @param params the operation's arguments
     * @return the context, with a fresh answer
     */
    private static LayoutContext context(LayoutEditor editor, Map<String, String> params)
    {
        V8Container module = V8Container.empty();
        module.add(V8Container.Entry.of("module", CRLF.getBytes(StandardCharsets.UTF_8))); //$NON-NLS-1$
        return new LayoutContext(null, "Catalog.Products.Form.ItemForm", "Catalog", //$NON-NLS-1$ //$NON-NLS-2$
            OrdinaryFormFile.of(Path.of("form.oform"), module), editor.root(), editor, params, //$NON-NLS-1$
            ToolResult.success());
    }

    /**
     * The identity of a list of handlers as strings {@code event -> procedure #code}, so a
     * comparison names what changed rather than how many entries differ.
     *
     * @param handlers the handlers
     * @return one string per handler, in order
     */
    private static List<String> fingerprints(List<FormLayout.Handler> handlers)
    {
        List<String> out = new ArrayList<>();
        for (FormLayout.Handler handler : handlers)
        {
            out.add(handler.event + " -> " + handler.procedure + " #" + handler.code); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return out;
    }

    /**
     * Finds a directly nested command-panel button without searching submenu descendants.
     *
     * @param panel the command panel as read
     * @param name the button's name
     * @return the top-level button of that name, or {@code null}
     */
    private static FormLayout.Button buttonNamed(FormLayout.Element panel, String name)
    {
        for (FormLayout.Button button : panel.buttons)
        {
            if (name.equals(button.name))
            {
                return button;
            }
        }
        return null;
    }

    /**
     * Finds a directly nested form element without searching deeper descendants.
     *
     * @param parent the parent element as read
     * @param name the child's name
     * @return the nested element of that name, or {@code null}
     */
    private static FormLayout.Element childNamed(FormLayout.Element parent, String name)
    {
        for (FormLayout.Element one : parent.children)
        {
            if (name.equals(one.name))
            {
                return one;
            }
        }
        return null;
    }

    /**
     * The UTF-8 bytes of a text with the byte order mark the platform writes, as the adjacent
     * tests assemble the container's entries.
     *
     * @param text the text
     * @return the bytes
     */
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
