/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * A refusal after a partial edit changes nothing of the session: the handler may have half-built
 * its edit on the copy of the tree it was given - an element the arguments named, a panel whose
 * source turned out to name no table - but the session keeps only the edits of operations that
 * answered done. The container on disk is not touched by an edit at all, and a refusal over an
 * already dirty session leaves the dirty text exactly what the successful edit left.
 */
public class ARefusalLeavesTheSessionAsItWasTest
{
    private static final String PROJECT = "AiEdtOformSessionRefusalProbe"; //$NON-NLS-1$

    private static SessionProbe probe;

    private static String table;

    private static String title;

    @BeforeClass
    public static void aProjectWithOnePanelTableForm() throws Exception
    {
        String layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        probe = SessionProbe.with(PROJECT, layout, "\r\n"); //$NON-NLS-1$
        FormLayout read = LayoutReader.read(BraceTree.parse(layout), "Catalog"); //$NON-NLS-1$
        for (FormLayout.Element element : read.allElements())
        {
            if ("Table".equals(element.type) && element.name != null) //$NON-NLS-1$
            {
                table = element.name;
                break;
            }
        }
        assertNotNull(table);
        Object text = FormLayout.text(read.title);
        assertTrue("the fixture's title is one word", text instanceof String); //$NON-NLS-1$
        title = (String)text;
    }

    @AfterClass
    public static void theProjectGoes()
    {
        if (probe != null)
        {
            probe.dispose();
        }
    }

    /** An element whose data path names nothing is refused, and the session stays clean. */
    @Test
    public void anElementWithAnUnresolvablePathIsRefused() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                FormEditingSession.Edit edit = session.edit("add_element", Map.of( //$NON-NLS-1$
                    "type", "Label", //$NON-NLS-1$ //$NON-NLS-2$
                    "name", "НадписьПлохая", //$NON-NLS-1$ //$NON-NLS-2$
                    "caption", "Плохая", //$NON-NLS-1$ //$NON-NLS-2$
                    "left", "10", "top", "450", "width", "120", "height", "19", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                    "data", "НетАтрибута.Поле")); //$NON-NLS-1$ //$NON-NLS-2$
                assertFalse(edit.message, edit.done);
                assertFalse("a refusal leaves the session clean", session.isDirty()); //$NON-NLS-1$
                assertNull("the session holds no half-added element", named(session.snapshot(), "НадписьПлохая")); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue("the container holds what it held", java.util.Arrays.equals(probe.original(), probe.bytes())); //$NON-NLS-1$
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

    /** A panel whose source of actions names no table is refused after the panel was half-added. */
    @Test
    public void aPanelWithABogusSourceIsRefusedAfterAPartialEdit() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                FormEditingSession.Edit refused = session.edit("add_command_panel", Map.of( //$NON-NLS-1$
                    "name", "ПанельЛишняя", //$NON-NLS-1$ //$NON-NLS-2$
                    "left", "0", "top", "500", "width", "400", "height", "25", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                    "actionSource", "НетТаблицы")); //$NON-NLS-1$ //$NON-NLS-2$
                assertFalse(refused.message, refused.done);
                assertFalse(session.isDirty());
                assertNull("the session holds no half-added panel", named(session.snapshot(), "ПанельЛишняя")); //$NON-NLS-1$ //$NON-NLS-2$

                FormEditingSession.Edit kept = session.edit("set_property", Map.of( //$NON-NLS-1$
                    "property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(kept.message, kept.done);
                assertTrue(session.isDirty());

                FormEditingSession.Edit refusedAgain = session.edit("add_command_panel", Map.of( //$NON-NLS-1$
                    "name", "ПанельЕщеЛишняя", //$NON-NLS-1$ //$NON-NLS-2$
                    "actionSource", "НетТаблицы")); //$NON-NLS-1$ //$NON-NLS-2$
                assertFalse(refusedAgain.message, refusedAgain.done);
                assertTrue("the earlier edit stays", session.isDirty()); //$NON-NLS-1$
                assertNull(named(session.snapshot(), "ПанельЕщеЛишняя")); //$NON-NLS-1$
                assertEquals("Проба", session.snapshot().title); //$NON-NLS-1$

                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written); //$NON-NLS-1$
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

    /** The title comes and goes, and the session that set it back is not dirty and saves nothing. */
    @Test
    public void aReversedPropertyLeavesNothingToSave() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                FormEditingSession.Edit set = session.edit("set_property", Map.of( //$NON-NLS-1$
                    "property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(set.message, set.done);
                assertTrue(session.isDirty());
                FormEditingSession.Edit back = session.edit("set_property", Map.of( //$NON-NLS-1$
                    "property", "title", "value", title)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(back.message, back.done);
                assertFalse("what came and went leaves no edit", session.isDirty()); //$NON-NLS-1$

                byte[] before = probe.bytes();
                FormEditingSession.Save save = session.save();
                assertFalse("nothing was written", save.written); //$NON-NLS-1$
                assertNull("no owner was marked", save.delivery); //$NON-NLS-1$
                assertTrue(java.util.Arrays.equals(before, probe.bytes()));
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

    /** The snapshot's element of a name, or {@code null} when it holds none. */
    private static FormSnapshot.Element named(FormSnapshot snapshot, String name)
    {
        for (FormSnapshot.Element element : snapshot.allElements)
        {
            if (element.name != null && element.name.equalsIgnoreCase(name))
            {
                return element;
            }
        }
        return null;
    }
}
