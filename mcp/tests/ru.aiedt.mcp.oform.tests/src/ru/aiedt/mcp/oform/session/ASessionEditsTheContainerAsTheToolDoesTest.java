/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.LayoutOperations;
import ru.aiedt.mcp.oform.V8Container;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutIds;
import ru.aiedt.mcp.oform.layout.LayoutReader;
import ru.aiedt.mcp.oform.ops.OperationSpec;
import ru.aiedt.mcp.oform.ops.OperationsCatalog;

/**
 * One chain of every writing layout operation of the catalog, run two ways over one fixture:
 * the way the tool runs it - one {@code LayoutOperations.run} per operation, the container
 * written after each - and the way the editor does - the same operations fixed into a
 * {@link FormEditingSession} and written once at its save. Under one write stamp and one
 * sequence of ids the two write one container byte for byte, and the chain covers the writing
 * layout operations of the catalog exactly, so an operation added to the catalog without a row
 * here fails the coverage guard instead of slipping by.
 */
public class ASessionEditsTheContainerAsTheToolDoesTest
{
    private static final String PROJECT = "AiEdtOformSessionParityProbe"; //$NON-NLS-1$

    private static final String FQN = "Catalog.Products.Form.ItemForm"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    private static final String MODULE = "Процедура ПриОткрытии()" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    /** A write stamp, as a form written in 2026 carries it. */
    private static final long STAMP = 637311959090000L;

    private static SessionProbe probe;

    private static String table;

    /** The fixture's table element is found at run, so the chain names what it holds. */
    @BeforeClass
    public static void aProjectWithOnePanelTableForm() throws Exception
    {
        String layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        probe = SessionProbe.with(PROJECT, layout, MODULE);
        FormLayout read = LayoutReader.read(BraceTree.parse(layout), "Catalog"); //$NON-NLS-1$
        for (FormLayout.Element element : read.allElements())
        {
            if ("Table".equals(element.type) && element.name != null) //$NON-NLS-1$
            {
                table = element.name;
                break;
            }
        }
        assertNotNull("the fixture holds a named Table", table); //$NON-NLS-1$
    }

    @AfterClass
    public static void theProjectGoes()
    {
        if (probe != null)
        {
            probe.dispose();
        }
    }

    /** The chain names every writing layout operation of the catalog, and nothing else. */
    @Test
    public void theChainIsEveryWritingLayoutOperation()
    {
        Set<String> declared = new LinkedHashSet<>();
        for (String name : OperationsCatalog.knownNames())
        {
            OperationSpec spec = OperationsCatalog.find(name);
            if (spec.kind == OperationSpec.Kind.LAYOUT && spec.writes && !"help".equals(name)) //$NON-NLS-1$
            {
                declared.add(name);
            }
        }
        Set<String> chained = new LinkedHashSet<>();
        for (Map<String, String> one : scenario())
        {
            chained.add(one.get("operation")); //$NON-NLS-1$
        }
        assertEquals("every writing layout operation of the catalog, once each", declared, chained); //$NON-NLS-1$
    }

    /** The session and the tool, over the same chain under one stamp and one id sequence, write one container. */
    @Test
    public void theSessionAndTheToolWriteOneContainer() throws Exception
    {
        try
        {
            byte[] before = probe.bytes();
            byte[] throughTheTool = underOneStamp(() -> {
                for (Map<String, String> one : scenario())
                {
                    runAsTheTool(one);
                }
            });
            assertFalse("the chain changed the container", java.util.Arrays.equals(before, throughTheTool)); //$NON-NLS-1$
            probe.restore();
            byte[] throughTheSession = underOneStamp(this::runAsTheSession);
            assertArrayEquals("one chain, one container", throughTheTool, throughTheSession); //$NON-NLS-1$

            String module = moduleOf();
            assertTrue("the stubs reached the module", module.contains("ТаблицаПриАктивизацииСтроки") //$NON-NLS-1$ //$NON-NLS-2$
                && module.contains("ПробаНажатие")); //$NON-NLS-1$
        }
        finally
        {
            probe.restore();
        }
    }

    // ---- the chain --------------------------------------------------------------------------

    /**
     * One chain that touches every writing layout operation once. The pages come and go empty,
     * so the removals are plain; the table carries the value-table rows a column is measured
     * for, and the panel is a fresh one, so its source is set and the button taken away again.
     *
     * @return the operations with their arguments
     */
    private static List<Map<String, String>> scenario()
    {
        List<Map<String, String>> out = new ArrayList<>();
        out.add(op("set_property", "property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        out.add(op("bind_handler", "elementName", table, "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "procedure", "ТаблицаПриАктивизацииСтроки", "stub", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        out.add(op("unbind_handler", "elementName", table, "event", "ПриАктивизацииСтроки")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        out.add(op("add_page", "name", "СтраницаПроба", "title", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        out.add(op("add_element", "type", "Label", "name", "НадписьПроба", "caption", "Проба", "left", "10", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            "top", "400", "width", "120", "height", "19")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        out.add(op("move_element", "elementName", "НадписьПроба", "parent", "form", "page", "СтраницаПроба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        out.add(op("remove_element", "elementName", "НадписьПроба")); //$NON-NLS-1$ //$NON-NLS-2$
        out.add(op("remove_page", "name", "СтраницаПроба")); //$NON-NLS-1$ //$NON-NLS-2$
        out.add(op("add_command_panel", "name", "ПанельПроба", "left", "0", "top", "500", "width", "400", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "height", "25")); //$NON-NLS-1$ //$NON-NLS-2$
        out.add(op("add_button", "panel", "ПанельПроба", "name", "КнопкаПроба", "caption", "Проба", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "procedure", "ПробаНажатие", "stub", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        out.add(op("add_submenu", "panel", "ПанельПроба", "name", "ПодменюПроба", "caption", "Еще")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        out.add(op("add_separator", "panel", "ПанельПроба", "name", "РазделительПроба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        out.add(op("remove_button", "panel", "ПанельПроба", "name", "РазделительПроба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        out.add(op("set_action_source", "panel", "ПанельПроба", "table", table)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        out.add(op("add_column", "elementName", table, "name", "КолонкаПроба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        out.add(op("remove_column", "elementName", table, "name", "КолонкаПроба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return out;
    }

    private static Map<String, String> op(String operation, String... pairs)
    {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("operation", operation); //$NON-NLS-1$
        map.put("language", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ---- the two ways ----------------------------------------------------------------------

    /**
     * Runs one operation the way the tool does, and requires it to land.
     *
     * @param one the operation with its arguments
     */
    private static void runAsTheTool(Map<String, String> one)
    {
        Map<String, String> params = new LinkedHashMap<>(one);
        params.remove("operation"); //$NON-NLS-1$
        params.put("formFqn", FQN); //$NON-NLS-1$
        String answer = LayoutOperations.run(OperationsCatalog.find(one.get("operation")), probe.project(), params); //$NON-NLS-1$
        JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
        assertTrue(one + ": " + answer, json.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(one + ": " + answer, json.get("written").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Runs the chain the way the editor does - fixed into the session, written once at the save.
     */
    private void runAsTheSession()
    {
        FormEditingSession session = OpenFormSessions.open(probe.file());
        try
        {
            for (Map<String, String> one : scenario())
            {
                Map<String, String> params = new LinkedHashMap<>(one);
                String operation = params.remove("operation"); //$NON-NLS-1$
                FormEditingSession.Edit edit = session.edit(operation, params);
                assertTrue(operation + ": " + edit.message, edit.done); //$NON-NLS-1$
            }
            assertTrue(session.isDirty());
            FormEditingSession.Save save = session.save();
            assertTrue(String.valueOf(save.reconciliation), save.written); //$NON-NLS-1$
            assertFalse("the save cleaned the session", session.isDirty()); //$NON-NLS-1$
        }
        finally
        {
            OpenFormSessions.release(session);
        }
    }

    /**
     * Runs work under a fresh sequence of ids and one write stamp, and answers the container it
     * left.
     *
     * @param work the chain
     * @return the container's bytes after it
     */
    private static byte[] underOneStamp(Runnable work)
    {
        V8Container.stampedAt(STAMP, () -> LayoutIds.using(LayoutIds.sequence(), () -> {
            work.run();
            return null;
        }));
        return probe.bytes();
    }

    private static String moduleOf()
    {
        try
        {
            return ru.aiedt.mcp.oform.OrdinaryFormFile.read(
                probe.file().getLocation().toFile().toPath()).moduleText();
        }
        catch (java.io.IOException e)
        {
            throw new AssertionError(e);
        }
    }
}
