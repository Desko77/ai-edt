/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.OrdinaryFormFile;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * The stubs a binding writes into the module through the session: two bindings with a stub each,
 * made before any save, keep both stubs - the second handler reads the module with the first stub
 * in it, because the session builds its target from its own texts. A procedure the module already
 * declares is not stubbed again, and one the session's own earlier stub declared is not either:
 * one save leaves one procedure of that name.
 */
public class AStubIsWrittenOncePerProcedureTest
{
    private static final String PROJECT = "AiEdtOformSessionStubProbe"; //$NON-NLS-1$

    private static final String CRLF = "\r\n"; //$NON-NLS-1$

    /** The module declares one procedure, so a binding to it needs no stub. */
    private static final String MODULE = "Процедура ОбъявленнаяНажатие(Элемент)" + CRLF + "КонецПроцедуры" + CRLF; //$NON-NLS-1$ //$NON-NLS-2$

    private static SessionProbe probe;

    private static String table;

    @BeforeClass
    public static void aProjectWithOnePanelTableForm() throws Exception
    {
        String layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        probe = SessionProbe.with(PROJECT, layout, MODULE);
        for (FormLayout.Element element : LayoutReader.read(BraceTree.parse(layout), "Catalog").allElements()) //$NON-NLS-1$
        {
            if ("Table".equals(element.type) && element.name != null) //$NON-NLS-1$
            {
                table = element.name;
                break;
            }
        }
        assertNotNull(table);
    }

    @AfterClass
    public static void theProjectGoes()
    {
        if (probe != null)
        {
            probe.dispose();
        }
    }

    /** Two bindings with a stub each, before any save, leave two stubs in the module after one save. */
    @Test
    public void twoStubsBeforeOneSaveKeepBoth() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(bind(session, "ПриАктивизацииСтроки", "ПерваяПроба").done); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(bind(session, "ПриАктивизацииКолонки", "ВтораяПроба").done); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue("the two bindings dirtied the session", session.isDirty()); //$NON-NLS-1$

                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written); //$NON-NLS-1$
                String module = moduleOf();
                assertTrue(module, module.contains("ПерваяПроба")); //$NON-NLS-1$
                assertTrue(module, module.contains("ВтораяПроба")); //$NON-NLS-1$
                assertEquals("one stub of the first procedure", 1, count(module, "Процедура ПерваяПроба")); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals("one stub of the second procedure", 1, count(module, "Процедура ВтораяПроба")); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue("the module the fixture opened with keeps its procedure", module.contains("ОбъявленнаяНажатие")); //$NON-NLS-1$
                assertFalse("the save cleaned the session", session.isDirty()); //$NON-NLS-1$
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** A binding to a procedure the module declares adds no stub, so the module stays as it was. */
    @Test
    public void aDeclaredProcedureIsNotStubbed() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                FormEditingSession.Edit edit = session.edit("bind_handler", Map.of( //$NON-NLS-1$
                    "elementName", table, //$NON-NLS-1$
                    "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$
                    "procedure", "ОбъявленнаяНажатие", //$NON-NLS-1$ //$NON-NLS-2$
                    "stub", "true")); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(edit.message, edit.done);
                assertTrue("the binding changed the layout", session.formChanged()); //$NON-NLS-1$
                assertFalse("the module was already declared and stays", session.moduleChanged()); //$NON-NLS-1$
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** A procedure the session's own earlier stub declared is not stubbed a second time. */
    @Test
    public void theSessionsOwnStubIsNotWrittenTwice() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(bind(session, "ПриАктивизацииСтроки", "ОднаждыПроба").done); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(bind(session, "ПриАктивизацииКолонки", "ОднаждыПроба").done); //$NON-NLS-1$ //$NON-NLS-2$
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written); //$NON-NLS-1$
                assertEquals("one stub of the shared procedure", 1, count(moduleOf(), "Процедура ОднаждыПроба")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            finally
            {
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    private static FormEditingSession.Edit bind(FormEditingSession session, String event, String procedure)
    {
        return session.edit("bind_handler", Map.of( //$NON-NLS-1$
            "elementName", table, //$NON-NLS-1$
            "event", event, //$NON-NLS-1$
            "procedure", procedure, //$NON-NLS-1$
            "stub", "true")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String moduleOf()
    {
        try
        {
            return OrdinaryFormFile.read(probe.file().getLocation().toFile().toPath()).moduleText();
        }
        catch (java.io.IOException e)
        {
            throw new AssertionError(e);
        }
    }

    private static int count(String text, String word)
    {
        return text.split(word, -1).length - 1;
    }
}
