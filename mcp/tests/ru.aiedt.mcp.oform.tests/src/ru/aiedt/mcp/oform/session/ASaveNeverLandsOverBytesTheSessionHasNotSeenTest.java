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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import ru.aiedt.mcp.oform.BraceTree;
import ru.aiedt.mcp.oform.OrdinaryFormFile;
import ru.aiedt.mcp.oform.V8Container;
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutEditor;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * What a save does with the file it stands on. A clean session writes nothing and marks no owner.
 * A save writes the session's texts, and what it wrote becomes the baseline inside the save: the
 * next save of a clean session writes nothing, and the next edit runs against the written bytes
 * without a reconciliation. A file that changed outside the editor is never written over - the
 * save is refused and the session reconciles itself first - also when the session holds edits of
 * both texts and the outside change touched only the module.
 */
public class ASaveNeverLandsOverBytesTheSessionHasNotSeenTest
{
    private static final String PROJECT = "AiEdtOformSessionSaveProbe"; //$NON-NLS-1$

    private static final String FOREIGN_MODULE = "Процедура Чужая()\r\nКонецПроцедуры\r\n"; //$NON-NLS-1$

    private static SessionProbe probe;

    private static String table;

    @BeforeClass
    public static void aProjectWithOnePanelTableForm() throws Exception
    {
        String layout = SessionProbe.fixture("fixtures/panel-table-form-v27.form"); //$NON-NLS-1$
        probe = SessionProbe.with(PROJECT, layout, "\r\n"); //$NON-NLS-1$
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

    /** A session that holds no edit writes nothing and marks no owner. */
    @Test
    public void aCleanSessionSavesNothing() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                FormEditingSession.Save save = session.save();
                assertFalse(save.written);
                assertNull(save.delivery);
                assertArrayEquals(probe.original(), probe.bytes());
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

    /** A save writes the session's texts, and what it wrote is the baseline the session goes on with. */
    @Test
    public void aSaveWritesItsTextsAndBecomesTheBaseline() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                FormEditingSession.Edit edit = session.edit("set_property", Map.of( //$NON-NLS-1$
                    "property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(edit.message, edit.done);
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written); //$NON-NLS-1$
                assertNotNull("the owner was marked for the update", save.delivery); //$NON-NLS-1$
                assertFalse("the save cleaned the session", session.isDirty()); //$NON-NLS-1$
                byte[] written = probe.bytes();

                FormEditingSession.Save again = session.save();
                assertFalse("a clean session writes nothing again", again.written); //$NON-NLS-1$
                assertArrayEquals("and the file keeps what the save wrote", written, probe.bytes()); //$NON-NLS-1$

                FormEditingSession.Edit next = session.edit("set_property", Map.of( //$NON-NLS-1$
                    "property", "title", "value", "Проба2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue("the next edit runs on the written baseline: " + next.message, next.done); //$NON-NLS-1$
                assertNull("with no reconciliation to do first", next.reconciliation); //$NON-NLS-1$
                FormEditingSession.Save second = session.save();
                assertTrue(second.written);
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

    /** A file the outside rewrote is not written over: the save refuses and the session reconciles. */
    @Test
    public void aSaveAtForeignBytesWritesNothingAndReconciles() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                FormEditingSession.Edit edit = session.edit("set_property", Map.of( //$NON-NLS-1$
                    "property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(edit.message, edit.done);
                byte[] foreign = withForeignLayout("Чужой заголовок"); //$NON-NLS-1$
                probe.anotherWriterWrites(foreign);

                FormEditingSession.Save save = session.save();
                assertFalse("nothing was written over the foreign bytes", save.written); //$NON-NLS-1$
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, save.reconciliation); //$NON-NLS-1$
                assertArrayEquals("the file keeps the foreign bytes", foreign, probe.bytes()); //$NON-NLS-1$
                assertFalse("the discarded edit left the session clean", session.isDirty()); //$NON-NLS-1$
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

    /**
     * A session that changed the layout and the module does not write over an outside change of
     * the module alone: the save refuses, the outside module is taken over and the edits go.
     */
    @Test
    public void aModuleOnlyForeignChangeDiscardsAFormAndModuleSession() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                FormEditingSession.Edit form = session.edit("set_property", Map.of( //$NON-NLS-1$
                    "property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(form.message, form.done);
                FormEditingSession.Edit module = session.edit("bind_handler", Map.of( //$NON-NLS-1$
                    "elementName", table, //$NON-NLS-1$
                    "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$
                    "procedure", "СвояПроба", //$NON-NLS-1$ //$NON-NLS-2$
                    "stub", "true")); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(module.message, module.done);
                assertTrue("the session holds a layout edit and a module edit", //$NON-NLS-1$
                    session.formChanged() && session.moduleChanged());

                byte[] foreign = withForeignModule();
                probe.anotherWriterWrites(foreign);
                FormEditingSession.Save save = session.save();
                assertFalse("nothing was written over the foreign module", save.written); //$NON-NLS-1$
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, save.reconciliation); //$NON-NLS-1$
                assertArrayEquals(foreign, probe.bytes());
                assertFalse(session.isDirty());
                assertTrue("the outside module was taken over", moduleOf().contains("Чужая")); //$NON-NLS-1$
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

    // ---- the foreign writers ----------------------------------------------------------------

    /**
     * The original container with another title in its layout.
     *
     * @param title the foreign title
     * @return the container's bytes
     */
    private static byte[] withForeignLayout(String title)
    {
        OrdinaryFormFile file = OrdinaryFormFile.of(null, V8Container.read(probe.original()), probe.original());
        try
        {
            LayoutEditor editor = LayoutEditor.over(BraceTree.parse(file.formText()), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            LayoutEditor.Outcome set = editor.setTitle(title);
            assertTrue(set.message, set.done);
            file.setFormText(editor.text());
        }
        catch (ru.aiedt.mcp.oform.layout.UnsupportedLayoutVersionException e)
        {
            throw new AssertionError(e.getMessage(), e);
        }
        return file.toBytes();
    }

    /** @return the original container with another module in it */
    private static byte[] withForeignModule()
    {
        OrdinaryFormFile file = OrdinaryFormFile.of(null, V8Container.read(probe.original()), probe.original());
        file.setModuleText(FOREIGN_MODULE);
        return file.toBytes();
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
}
