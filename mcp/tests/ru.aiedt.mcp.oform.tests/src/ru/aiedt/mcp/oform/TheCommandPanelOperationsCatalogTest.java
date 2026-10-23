/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
 * The command panel catalog's own handlers at work: a panel is added from its template with the
 * source of its actions and the auto-fill that goes with it, and a bogus source refuses and
 * takes the panel away again; a button is added for a procedure and for a standard action, and
 * a procedure the module declares in another letter case is recognized instead of stubbed a
 * second time; a submenu takes a buttons block of its own and a separator sits beside them; the
 * source of a panel's actions is set and cleared by the handler, which refuses an absent table
 * argument by name.
 */
public class TheCommandPanelOperationsCatalogTest
{
    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String EMPTY_MODULE = CRLF;

    /** The fixtures the catalog is driven over: an object panel, and a table panel with a source. */
    private static final List<String> FIXTURES = List.of("fixtures/panel-object-form-v26.form", //$NON-NLS-1$
        "fixtures/panel-table-form-v27.form"); //$NON-NLS-1$

    /**
     * @param fixture the fixture's resource name
     * @return its text
     */
    private static String text(String fixture)
    {
        try
        {
            return ALayoutIsReadOffTheContainerTest.fixture(fixture);
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }
    }

    /**
     * @param name the operation's name
     * @return the spec of that name in the command panel catalog's own list
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
     * @param fixture the fixture to edit
     * @param module the module text the form carries
     * @param params the operation's arguments
     * @return a context over a fresh editor of the fixture
     */
    private static LayoutContext context(String fixture, String module, Map<String, String> params)
    {
        LayoutEditor editor = LayoutEditor.over(BraceTree.parse(text(fixture)), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        V8Container container = V8Container.empty();
        container.add(V8Container.Entry.of("module", module.getBytes(StandardCharsets.UTF_8))); //$NON-NLS-1$
        return new LayoutContext(null, "Catalog.Products.Form.ItemForm", "Catalog", //$NON-NLS-1$
            OrdinaryFormFile.of(Path.of("form.oform"), container), editor.root(), editor, params, ToolResult.success()); //$NON-NLS-1$
    }

    /** The catalog declares the panel operations, every one an edit of an existing form. */
    @Test
    public void theCatalogDeclaresThePanelOperations()
    {
        List<String> names = CommandPanelOperations.specs().stream().map(spec -> spec.name).toList();
        assertEquals(List.of("add_command_panel", "add_button", "add_submenu", "add_separator", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "remove_button", "set_action_source"), names); //$NON-NLS-1$
        for (OperationSpec one : CommandPanelOperations.specs())
        {
            assertEquals(one.name, OperationSpec.Kind.LAYOUT, one.kind);
            assertTrue(one.name, one.writes);
            assertTrue(one.name, one.needsProject);
        }
    }

    /** A panel is added with a source of actions and the auto-fill that names one; without a source neither comes. */
    @Test
    public void aPanelIsAddedWithAndWithoutASource()
    {
        LayoutContext sourced = context(FIXTURES.get(1), EMPTY_MODULE,
            Map.of("name", "ПанельДействий", "actionSource", "Text12", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "left", "0", "top", "300", "width", "400", "height", "25")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        OperationResult withSource = spec("add_command_panel").layout.apply(sourced); //$NON-NLS-1$
        assertTrue(withSource.outcome.message, withSource.outcome.done);
        FormLayout.Element panel = LayoutReader.read(sourced.editor.root(), "Catalog").elementNamed("ПанельДействий"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(panel);
        assertEquals("CommandPanel", panel.type); //$NON-NLS-1$
        assertEquals("Text12", panel.details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, panel.details.get("autoFill")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject answer = JsonParser.parseString(sourced.answer.toJson()).getAsJsonObject();
        assertEquals("0,300,400,325", answer.get("box").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        LayoutContext plain = context(FIXTURES.get(1), EMPTY_MODULE, Map.of("name", "ПанельПростая")); //$NON-NLS-1$ //$NON-NLS-2$
        OperationResult without = spec("add_command_panel").layout.apply(plain); //$NON-NLS-1$
        assertTrue(without.outcome.message, without.outcome.done);
        FormLayout.Element bare = LayoutReader.read(plain.editor.root(), "Catalog").elementNamed("ПанельПростая"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("none", bare.details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, bare.details.get("autoFill")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A bogus source refuses and the panel goes with it; a missing name refuses before anything is written. */
    @Test
    public void aBogusSourceRefusesAndThePanelGoes()
    {
        LayoutContext refused = context(FIXTURES.get(1), EMPTY_MODULE,
            Map.of("name", "ПанельЛишняя", "actionSource", "НетТаблицы")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        OperationResult bogus = spec("add_command_panel").layout.apply(refused); //$NON-NLS-1$
        assertFalse(bogus.outcome.done);
        assertTrue(bogus.outcome.message, bogus.outcome.message.contains("НетТаблицы")); //$NON-NLS-1$
        assertNull("the refused panel is taken away again", //$NON-NLS-1$
            LayoutReader.read(refused.editor.root(), "Catalog").elementNamed("ПанельЛишняя")); //$NON-NLS-1$ //$NON-NLS-2$

        LayoutContext unnamed = context(FIXTURES.get(0), EMPTY_MODULE, Map.of()); //$NON-NLS-1$
        OperationResult noName = spec("add_command_panel").layout.apply(unnamed); //$NON-NLS-1$
        assertFalse(noName.outcome.done);
        assertTrue(noName.outcome.message, noName.outcome.message.contains("add_command_panel needs name")); //$NON-NLS-1$
    }

    /** A button is added for a procedure and for a standard action; an unknown action is refused by name. */
    @Test
    public void aButtonIsAddedForAProcedureAndAStandardAction()
    {
        LayoutContext procedure = context(FIXTURES.get(0), EMPTY_MODULE,
            Map.of("panel", "Text7", "name", "КнопкаПечать", "caption", "Печать", "procedure", "ПечатьНажатие")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        OperationResult added = spec("add_button").layout.apply(procedure); //$NON-NLS-1$
        assertTrue(added.outcome.message, added.outcome.done);
        assertNull("no stub was asked for", added.moduleText); //$NON-NLS-1$
        FormLayout.Button button = buttonNamed(procedure, "КнопкаПечать"); //$NON-NLS-1$
        assertNotNull(button);
        assertEquals("procedure:ПечатьНажатие", button.details.get("action")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Печать", button.caption.get("ru")); //$NON-NLS-1$ //$NON-NLS-2$

        LayoutContext standard = context(FIXTURES.get(0), EMPTY_MODULE,
            Map.of("panel", "Text7", "name", "КнопкаЗакрыть", "action", "close")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OperationResult closed = spec("add_button").layout.apply(standard); //$NON-NLS-1$
        assertTrue(closed.outcome.message, closed.outcome.done);
        assertEquals("standard:close", buttonNamed(standard, "КнопкаЗакрыть").details.get("action")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        LayoutContext unknown = context(FIXTURES.get(0), EMPTY_MODULE,
            Map.of("panel", "Text7", "name", "КнопкаНеизвестно", "action", "doEverything")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OperationResult refused = spec("add_button").layout.apply(unknown); //$NON-NLS-1$
        assertFalse(refused.outcome.done);
        assertTrue(refused.outcome.message, refused.outcome.message.contains("Unknown standard action doEverything")); //$NON-NLS-1$
        assertTrue(refused.outcome.message, refused.outcome.message.contains("close")); //$NON-NLS-1$
    }

    /**
     * A button procedure the module declares in another letter case is recognized and not stubbed
     * a second time. The declaration search compares Cyrillic names without regard to letter case.
     */
    @Test
    public void aButtonProcedureInAnotherLetterCaseIsRecognized()
    {
        String module = "Процедура ПЕЧАТЬНАЖАТИЕ(Кнопка)" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$
        LayoutContext bound = context(FIXTURES.get(0), module,
            Map.of("panel", "Text7", "name", "КнопкаПечать", "procedure", "ПечатьНажатие", "stub", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        OperationResult result = spec("add_button").layout.apply(bound); //$NON-NLS-1$
        assertTrue(result.outcome.message, result.outcome.done);
        JsonObject answer = JsonParser.parseString(bound.answer.toJson()).getAsJsonObject();
        assertTrue("the module declares the procedure in another letter case", //$NON-NLS-1$
            answer.get("procedureDeclared").getAsBoolean()); //$NON-NLS-1$
        assertNull("the module stays as it is", result.moduleText); //$NON-NLS-1$
    }

    /** A submenu takes a buttons block of its own, a separator sits beside it, and both go by name. */
    @Test
    public void aSubmenuAndASeparatorAreAddedAndRemoved()
    {
        LayoutContext menu = context(FIXTURES.get(0), EMPTY_MODULE,
            Map.of("panel", "Text7", "name", "ПодменноеДействия", "caption", "Действия")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        OperationResult submenu = spec("add_submenu").layout.apply(menu); //$NON-NLS-1$
        assertTrue(submenu.outcome.message, submenu.outcome.done);
        FormLayout.Button added = buttonNamed(menu, "ПодменноеДействия"); //$NON-NLS-1$
        assertNotNull(added);
        assertEquals("submenu", added.details.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a submenu starts with an empty buttons block", //$NON-NLS-1$
            added.details.get("children") == null || ((List<?>)added.details.get("children")).isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$

        LayoutContext withSeparator = context(FIXTURES.get(0), EMPTY_MODULE,
            Map.of("panel", "Text7", "name", "Разделитель1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        OperationResult separator = spec("add_separator").layout.apply(withSeparator); //$NON-NLS-1$
        assertTrue(separator.outcome.message, separator.outcome.done);
        assertEquals("separator", buttonNamed(withSeparator, "Разделитель1").details.get("kind")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        OperationResult removed = spec("remove_button").layout.apply(withSeparator); //$NON-NLS-1$
        assertTrue(removed.outcome.message, removed.outcome.done);
        assertNull("the separator is gone", buttonNamed(withSeparator, "Разделитель1")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The source of a panel's actions is set and cleared by the handler; an absent table argument is refused. */
    @Test
    public void theSourceIsSetAndClearedByTheHandler()
    {
        LayoutContext set = context(FIXTURES.get(1), EMPTY_MODULE,
            Map.of("panel", "Text3", "table", "Text12")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        OperationResult withSource = spec("set_action_source").layout.apply(set); //$NON-NLS-1$
        assertTrue(withSource.outcome.message, withSource.outcome.done);
        FormLayout.Element panel = LayoutReader.read(set.editor.root(), "Catalog").elementNamed("Text3"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Text12", panel.details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.TRUE, panel.details.get("autoFill")); //$NON-NLS-1$ //$NON-NLS-2$

        LayoutContext cleared = context(FIXTURES.get(1), EMPTY_MODULE,
            Map.of("panel", "Text3", "table", "none")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        OperationResult without = spec("set_action_source").layout.apply(cleared); //$NON-NLS-1$
        assertTrue(without.outcome.message, without.outcome.done);
        FormLayout.Element empty = LayoutReader.read(cleared.editor.root(), "Catalog").elementNamed("Text3"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("none", empty.details.get("actionSource")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Boolean.FALSE, empty.details.get("autoFill")); //$NON-NLS-1$ //$NON-NLS-2$

        LayoutContext missing = context(FIXTURES.get(1), EMPTY_MODULE, Map.of("panel", "Text3")); //$NON-NLS-1$ //$NON-NLS-2$
        OperationResult refused = spec("set_action_source").layout.apply(missing); //$NON-NLS-1$
        assertFalse(refused.outcome.done);
        assertTrue(refused.outcome.message, refused.outcome.message.contains("set_action_source needs table")); //$NON-NLS-1$
    }

    /**
     * @param context the edit's context
     * @param name the button's name
     * @return the button as the reader sees it, or {@code null}
     */
    private static FormLayout.Button buttonNamed(LayoutContext context, String name)
    {
        FormLayout layout = LayoutReader.read(context.editor.root(), "Catalog"); //$NON-NLS-1$
        for (FormLayout.Button button : layout.elementNamed("Text7").buttons) //$NON-NLS-1$
        {
            if (name.equals(button.name))
            {
                return button;
            }
        }
        return null;
    }
}
