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

import java.nio.charset.StandardCharsets;
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
 * Every row the reconciliation follows, one test each: the file that equals the baseline, the
 * bytes that differ with the same texts, the module that changed outside while the session holds
 * no module edit, the layout that changed outside over a clean session and over one that holds
 * edits, and the file that holds nothing editable, which puts the session into read-only until a
 * readable container is back. An edit that meets a file the session has not seen does not edit
 * over it: the session reconciles itself and answers with what that found.
 */
public class AReconciliationFollowsItsTableTest
{
    private static final String PROJECT = "AiEdtOformSessionReconcileProbe"; //$NON-NLS-1$

    private static final String FOREIGN_MODULE = "Процедура Чужая()\r\nКонецПроцедуры\r\n"; //$NON-NLS-1$

    /** The verbatim line the editor shows over discarded edits. */
    private static final String DISCARD = "Форма изменена вне редактора; несохраненные правки отброшены."; //$NON-NLS-1$

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

    /** A file that equals the baseline reconciles to nothing. */
    @Test
    public void theSameBytesAreNothing() throws Exception
    {
        FormEditingSession session = OpenFormSessions.open(probe.file());
        try
        {
            FormEditingSession.Reconciliation reconciliation = session.reconcile();
            assertEquals(FormEditingSession.Reconciliation.NOTHING, reconciliation.outcome);
            assertNull(reconciliation.message);
            assertFalse(session.readOnly());
        }
        finally
        {
            OpenFormSessions.release(session);
        }
    }

    /** Bytes that differ with the same texts are taken over, and the session's edits stay. */
    @Test
    public void otherBytesWithTheSameTextsAreTakenOver() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theTitleEdit(session).done);
                byte[] repacked = repacked();
                probe.anotherWriterWrites(repacked);
                FormEditingSession.Reconciliation reconciliation = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.REPACKED, reconciliation.outcome);
                assertTrue("the edit stays", session.isDirty()); //$NON-NLS-1$
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written); //$NON-NLS-1$
                assertTrue("the entry the other writer added survives the save", //$NON-NLS-1$
                    OrdinaryFormFile.read(probe.file().getLocation().toFile().toPath()).entryNames()
                        .contains("extra")); //$NON-NLS-1$
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

    /** A module that changed outside, over a session with no module edit, is taken over as it is. */
    @Test
    public void aForeignModuleIsAcceptedOverASessionWithoutAModuleEdit() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theTitleEdit(session).done);
                probe.anotherWriterWrites(withForeignModule());
                FormEditingSession.Reconciliation reconciliation = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.MODULE_ACCEPTED, reconciliation.outcome);
                assertTrue("the layout edit stays", session.formChanged()); //$NON-NLS-1$
                assertFalse("the module was taken over, not edited", session.moduleChanged()); //$NON-NLS-1$
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written); //$NON-NLS-1$
                String module = OrdinaryFormFile.read(probe.file().getLocation().toFile().toPath()).moduleText();
                assertTrue(module, module.contains("Чужая")); //$NON-NLS-1$
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

    /** A foreign module over a session that holds a module edit behaves as a changed layout: the edits go. */
    @Test
    public void aForeignModuleDiscardsTheSessionThatEditedTheModule() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                FormEditingSession.Edit module = session.edit("bind_handler", Map.of( //$NON-NLS-1$
                    "elementName", table, //$NON-NLS-1$
                    "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$
                    "procedure", "СвояПроба", //$NON-NLS-1$ //$NON-NLS-2$
                    "stub", "true")); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(module.message, module.done);
                assertTrue(session.moduleChanged());
                probe.anotherWriterWrites(withForeignModule());
                FormEditingSession.Reconciliation reconciliation = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, reconciliation.outcome);
                assertEquals(DISCARD, reconciliation.message);
                assertFalse(session.isDirty());
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

    /** A layout that changed outside over a clean session is just reread. */
    @Test
    public void aForeignLayoutIsRereadOverACleanSession() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                byte[] foreign = withForeignLayout("Чужой заголовок"); //$NON-NLS-1$
                probe.anotherWriterWrites(foreign);
                FormEditingSession.Reconciliation reconciliation = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.REREAD, reconciliation.outcome);
                assertNull(reconciliation.message);
                assertEquals("Чужой заголовок", session.snapshot().title); //$NON-NLS-1$
                assertFalse(session.isDirty());
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

    /** A layout that changed outside over a session with edits discards them and rereads. */
    @Test
    public void aForeignLayoutDiscardsTheSessionThatHeldEdits() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theTitleEdit(session).done);
                byte[] foreign = withForeignLayout("Чужой заголовок"); //$NON-NLS-1$
                probe.anotherWriterWrites(foreign);
                FormEditingSession.Reconciliation reconciliation = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, reconciliation.outcome);
                assertEquals(DISCARD, reconciliation.message);
                assertFalse(session.isDirty());
                assertEquals("Чужой заголовок", session.snapshot().title); //$NON-NLS-1$
                assertArrayEquals(foreign, probe.bytes());
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

    /** A file that is no container, and one of a version outside the set, put the session into read-only. */
    @Test
    public void anUneditableFileIsReadOnlyUntilAReadableOneIsBack() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                probe.anotherWriterWrites("not a container at all".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
                FormEditingSession.Reconciliation broken = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.READ_ONLY, broken.outcome);
                assertTrue(session.readOnly());
                assertNotNull(session.problem());
                FormEditingSession.Edit edit = session.edit("set_property", Map.of( //$NON-NLS-1$
                    "property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertFalse(edit.done);
                FormEditingSession.Save save = session.save();
                assertFalse(save.written);

                probe.restore();
                probe.anotherWriterWrites(withForeignVersion("24")); //$NON-NLS-1$
                FormEditingSession.Reconciliation version = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.READ_ONLY, version.outcome);
                assertTrue(version.outcome, version.message.contains("24")); //$NON-NLS-1$

                probe.restore();
                FormEditingSession.Reconciliation back = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.NOTHING, back.outcome);
                assertFalse("a readable container ends the read-only", session.readOnly()); //$NON-NLS-1$
                assertNull(session.problem());
                assertTrue(theTitleEdit(session).done);
                FormEditingSession.Save written = session.save();
                assertTrue(written.written);
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

    /** A session opened over an unmeasured version stays read-only while the file stands at it. */
    @Test
    public void aSessionOpenedOverAnUnmeasuredVersionStaysReadOnlyAtAnUnchangedFile() throws Exception
    {
        try
        {
            probe.anotherWriterWrites(withForeignVersion("24")); //$NON-NLS-1$
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue("the session opens read-only over an unmeasured version", session.readOnly()); //$NON-NLS-1$
                assertTrue(String.valueOf(session.problem()), session.problem().contains("24")); //$NON-NLS-1$

                FormEditingSession.Reconciliation unchanged = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.NOTHING, unchanged.outcome);
                assertTrue("the unchanged file keeps the read-only: its baseline holds no editable layout", //$NON-NLS-1$
                    session.readOnly());
                assertTrue(session.problem().contains("24")); //$NON-NLS-1$
                FormEditingSession.Edit edit = session.edit("set_property", Map.of( //$NON-NLS-1$
                    "property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertFalse(edit.done);
                FormEditingSession.Save save = session.save();
                assertFalse(save.written);

                probe.restore();
                FormEditingSession.Reconciliation back = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.REREAD, back.outcome);
                assertFalse("a measured container ends the read-only", session.readOnly()); //$NON-NLS-1$
                assertNull(session.problem());
                assertTrue(theTitleEdit(session).done);
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

    /** An edit that meets a file the session has not seen does not edit over it. */
    @Test
    public void anEditAtAForeignFileReconcilesInsteadOfEditing() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                byte[] foreign = withForeignLayout("Чужой заголовок"); //$NON-NLS-1$
                probe.anotherWriterWrites(foreign);
                FormEditingSession.Edit edit = theTitleEdit(session);
                assertFalse("the edit did not run", edit.done); //$NON-NLS-1$
                assertNotNull("the session answers with what the reconciliation found", edit.reconciliation); //$NON-NLS-1$
                assertTrue(edit.message, edit.message.contains("changed outside")); //$NON-NLS-1$
                assertArrayEquals("the foreign bytes stand", foreign, probe.bytes()); //$NON-NLS-1$
                assertFalse(session.isDirty());
                FormEditingSession.Edit after = theTitleEdit(session);
                assertTrue("the next edit runs on the reread form: " + after.message, after.done); //$NON-NLS-1$
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

    private static FormEditingSession.Edit theTitleEdit(FormEditingSession session)
    {
        return session.edit("set_property", Map.of("property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /** @return the original container with one unknown entry added - the same texts, other bytes */
    private static byte[] repacked()
    {
        V8Container container = V8Container.read(probe.original());
        container.add(V8Container.Entry.of("extra", new byte[] { 1, 2, 3 })); //$NON-NLS-1$
        return container.write();
    }

    /** @return the original container with another module in it */
    private static byte[] withForeignModule()
    {
        OrdinaryFormFile file = OrdinaryFormFile.of(null, V8Container.read(probe.original()), probe.original());
        file.setModuleText(FOREIGN_MODULE);
        return file.toBytes();
    }

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

    /**
     * The original container with the layout's version token replaced.
     *
     * @param version the foreign version
     * @return the container's bytes
     */
    private static byte[] withForeignVersion(String version)
    {
        OrdinaryFormFile file = OrdinaryFormFile.of(null, V8Container.read(probe.original()), probe.original());
        file.setFormText(file.formText().replaceFirst("^\\{27,", "{" + version + ",")); //$NON-NLS-1$ //$NON-NLS-2$
        return file.toBytes();
    }
}
