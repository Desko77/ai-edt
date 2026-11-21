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

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.NullProgressMonitor;
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
 * What an external write meets in a session that holds unsaved edits. The draft is never dropped
 * by something the user did not ask to drop: a save, an edit, an undo and a preview that meet a
 * file the session has not seen refuse and leave the layout and the module they hold exactly as
 * the last edit left them, and the container keeps the bytes the outside writer put there. The
 * session's reconciliation is the explicit discard: that call, and only that call, drops the
 * draft and takes the external form.
 * <p>
 * A file rewritten with the same texts is not a conflict - there is nothing of the user's the
 * outside write contradicts - so the bytes are taken over and the save still lands.
 * </p>
 */
public class AConflictKeepsTheDraftUntilExplicitDiscardTest
{
    private static final String PROJECT = "AiEdtOformConflictProbe"; //$NON-NLS-1$

    private static final String DRAFT = "Черновик"; //$NON-NLS-1$

    private static final String DRAFT2 = "Черновик 2"; //$NON-NLS-1$

    private static final String FOREIGN = "Чужой заголовок"; //$NON-NLS-1$

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

    /**
     * A save at a file whose layout changed outside neither writes that file nor drops the draft;
     * the draft stays through a repeated save, and an explicit reconciliation is what drops it.
     */
    @Test
    public void aSaveAtAForeignLayoutWritesNothingAndKeepsTheDraft() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                byte[] foreign = withForeignLayout(FOREIGN);
                probe.anotherWriterWrites(foreign);

                FormEditingSession.Save save = session.save();
                assertFalse("the external bytes were not overwritten", save.written); //$NON-NLS-1$
                assertArrayEquals("the external layout stands", foreign, probe.bytes()); //$NON-NLS-1$
                assertEquals("the refusal names the conflict", //$NON-NLS-1$
                    FormEditingSession.Reconciliation.CONFLICT, save.reconciliation);
                assertTrue("the draft stays unsaved", session.isDirty()); //$NON-NLS-1$
                assertTrue("the layout draft stays", session.formChanged()); //$NON-NLS-1$
                assertEquals("the editor still shows the draft", DRAFT, session.snapshot().title); //$NON-NLS-1$

                FormEditingSession.Save again = session.save();
                assertFalse("a repeated save still writes nothing", again.written); //$NON-NLS-1$
                assertTrue("and still keeps the draft", session.isDirty()); //$NON-NLS-1$
                assertEquals(DRAFT, session.snapshot().title);

                FormEditingSession.Reconciliation discard = session.reconcile();
                assertEquals("the reconciliation is the explicit discard", //$NON-NLS-1$
                    FormEditingSession.Reconciliation.CHANGES_DISCARDED, discard.outcome);
                assertFalse("the explicit discard dropped the draft", session.isDirty()); //$NON-NLS-1$
                assertEquals(FOREIGN, session.snapshot().title);
                assertArrayEquals("and the outside layout was taken over", foreign, probe.bytes()); //$NON-NLS-1$
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
     * An edit at a file whose layout changed outside is refused, changes neither the file nor the
     * draft it already holds, and the draft is dropped only by an explicit reconciliation.
     */
    @Test
    public void anEditAtAForeignLayoutIsRefusedAndKeepsTheDraft() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                byte[] foreign = withForeignLayout(FOREIGN);
                probe.anotherWriterWrites(foreign);

                FormEditingSession.Edit refused = session.edit("set_property", //$NON-NLS-1$
                    Map.of("property", "title", "value", DRAFT2)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertFalse("the edit did not run over the outside layout", refused.done); //$NON-NLS-1$
                assertNotNull("the refusal says what the session found", refused.message); //$NON-NLS-1$
                assertTrue(refused.message, refused.message.contains("changed outside")); //$NON-NLS-1$
                assertEquals(FormEditingSession.Reconciliation.CONFLICT, refused.reconciliation);
                assertArrayEquals("the external layout stands", foreign, probe.bytes()); //$NON-NLS-1$
                assertTrue("the draft stays unsaved", session.isDirty()); //$NON-NLS-1$
                assertTrue("the layout draft stays", session.formChanged()); //$NON-NLS-1$
                assertEquals("the refused edit did not replace the draft", DRAFT, session.snapshot().title); //$NON-NLS-1$

                FormEditingSession.Reconciliation discard = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, discard.outcome);
                assertFalse(session.isDirty());
                assertEquals(FOREIGN, session.snapshot().title);
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

    /**
     * A gesture - the editor's one-record shape for a drag or a property change - at a file whose
     * layout changed outside is refused before any step runs and keeps the draft.
     */
    @Test
    public void aGestureAtAForeignLayoutIsRefusedAndKeepsTheDraft() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                byte[] foreign = withForeignLayout(FOREIGN);
                probe.anotherWriterWrites(foreign);

                FormEditingSession.Edit gesture = session.gesture(List.of(new FormEditingSession.Step( //$NON-NLS-1$
                    "set_property", Map.of("property", "title", "value", DRAFT2)))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertFalse("the gesture did not run over the outside layout", gesture.done); //$NON-NLS-1$
                assertNotNull(gesture.message);
                assertTrue(gesture.message, gesture.message.contains("changed outside")); //$NON-NLS-1$
                assertEquals(FormEditingSession.Reconciliation.CONFLICT, gesture.reconciliation);
                assertArrayEquals(foreign, probe.bytes());
                assertTrue(session.isDirty());
                assertEquals(DRAFT, session.snapshot().title);

                FormEditingSession.Reconciliation discard = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, discard.outcome);
                assertFalse(session.isDirty());
                assertEquals(FOREIGN, session.snapshot().title);
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
     * An undo at a file whose layout changed outside is refused and keeps the draft and its
     * history; the explicit reconciliation is what drops both.
     */
    @Test
    public void anUndoAtAForeignLayoutIsRefusedAndKeepsTheDraft() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                FormEditingSession.Edit second = session.edit("set_property", //$NON-NLS-1$
                    Map.of("property", "title", "value", DRAFT2)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(second.message, second.done);
                byte[] foreign = withForeignLayout(FOREIGN);
                probe.anotherWriterWrites(foreign);

                FormEditingSession.Reversal undo = session.undo();
                assertFalse("the undo did not run over the outside layout", undo.done); //$NON-NLS-1$
                assertNotNull("the refusal says what the session found", undo.message); //$NON-NLS-1$
                assertEquals(FormEditingSession.Reconciliation.CONFLICT, undo.reconciliation);
                assertArrayEquals("the external layout stands", foreign, probe.bytes()); //$NON-NLS-1$
                assertTrue("the drafts stay unsaved", session.isDirty()); //$NON-NLS-1$
                assertEquals("the newest draft is still what the editor shows", DRAFT2, //$NON-NLS-1$
                    session.snapshot().title);

                FormEditingSession.Reconciliation discard = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, discard.outcome);
                assertFalse(session.isDirty());
                assertEquals(FOREIGN, session.snapshot().title);
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
     * An outside write that touches only the module keeps a form and module draft as well: the
     * save writes nothing, the file keeps the outside module, and the explicit reconciliation
     * drops both drafts.
     */
    @Test
    public void aForeignModuleOverAModuleDraftKeepsIt() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                FormEditingSession.Edit module = session.edit("bind_handler", Map.of( //$NON-NLS-1$
                    "elementName", table, //$NON-NLS-1$
                    "event", "ПриАктивизацииСтроки", //$NON-NLS-1$ //$NON-NLS-2$
                    "procedure", "СвояПроба", //$NON-NLS-1$ //$NON-NLS-2$
                    "stub", "true")); //$NON-NLS-1$ //$NON-NLS-2$
                assertTrue(module.message, module.done);
                assertTrue("the session holds a layout and a module draft", //$NON-NLS-1$
                    session.formChanged() && session.moduleChanged());
                byte[] foreign = withForeignModule();
                probe.anotherWriterWrites(foreign);

                FormEditingSession.Save save = session.save();
                assertFalse("the outside module was not overwritten", save.written); //$NON-NLS-1$
                assertArrayEquals("the external module stands", foreign, probe.bytes()); //$NON-NLS-1$
                assertEquals("the refusal names the conflict", //$NON-NLS-1$
                    FormEditingSession.Reconciliation.CONFLICT, save.reconciliation);
                assertTrue("both drafts stay", session.isDirty()); //$NON-NLS-1$
                assertTrue(session.formChanged() && session.moduleChanged());
                assertTrue("the file still holds the outside module", moduleOf().contains("Чужая")); //$NON-NLS-1$ //$NON-NLS-2$

                FormEditingSession.Reconciliation discard = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, discard.outcome);
                assertFalse("the explicit discard dropped both drafts", session.isDirty()); //$NON-NLS-1$
                assertTrue(moduleOf().contains("Чужая")); //$NON-NLS-1$ //$NON-NLS-2$
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

    /** A preview at a foreign layout touches nothing and keeps the draft. */
    @Test
    public void aPreviewAtAForeignLayoutKeepsTheDraft() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                byte[] foreign = withForeignLayout(FOREIGN);
                probe.anotherWriterWrites(foreign);

                FormEditingSession.Preview preview = session.preview("remove_element", //$NON-NLS-1$
                    Map.of("elementName", table)); //$NON-NLS-1$
                assertFalse("the preview ran over the outside layout", preview.done); //$NON-NLS-1$
                assertNotNull(preview.message);
                assertArrayEquals(foreign, probe.bytes());
                assertTrue(session.isDirty());
                assertEquals(DRAFT, session.snapshot().title);
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
     * An outside module write over a session that edits only the layout is not a conflict: the
     * session takes the module over, keeps the layout draft, and the save lands it while the
     * outside module survives.
     */
    @Test
    public void aForeignModuleOverALayoutDraftIsAcceptedAndTheSaveLands() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                byte[] foreign = withForeignModule();
                probe.anotherWriterWrites(foreign);

                FormEditingSession.Save first = session.save();
                if (!first.written)
                {
                    assertTrue("a save that waits for the next round keeps the draft", //$NON-NLS-1$
                        session.isDirty());
                    assertTrue(session.formChanged());
                    assertFalse("the session held no module edit", session.moduleChanged()); //$NON-NLS-1$
                    FormEditingSession.Save second = session.save();
                    assertTrue("the layout draft still lands: " + second.reconciliation, //$NON-NLS-1$
                        second.written);
                }
                assertFalse(session.isDirty());
                OrdinaryFormFile written = theContainersFile();
                assertTrue(written.formText(), written.formText().contains(DRAFT)); //$NON-NLS-1$
                assertTrue("the outside module survives the save", //$NON-NLS-1$
                    written.moduleText().contains("Чужая")); //$NON-NLS-1$
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
     * A file rewritten with the same texts contradicts no draft: the session takes the bytes over
     * and the save still lands the draft, keeping the entry the other writer added.
     */
    @Test
    public void aRepackedFileWithTheSameTextsIsNotAConflict() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                probe.anotherWriterWrites(repacked());

                FormEditingSession.Save first = session.save();
                if (!first.written)
                {
                    assertTrue("a save that waits for the next round keeps the draft", //$NON-NLS-1$
                        session.isDirty());
                    FormEditingSession.Save second = session.save();
                    assertTrue("the draft still lands over the repacked file: " + second.reconciliation, //$NON-NLS-1$
                        second.written);
                }
                assertFalse(session.isDirty());
                OrdinaryFormFile written = theContainersFile();
                assertTrue(written.formText(), written.formText().contains(DRAFT)); //$NON-NLS-1$
                assertTrue("the entry the other writer added survives the save", //$NON-NLS-1$
                    written.entryNames().contains("extra")); //$NON-NLS-1$
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

    /** A saved draft is in the file, survives a reconciliation and is what a reopened session reads. */
    @Test
    public void aSavedDraftSurvivesReconcileAndReopen() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                FormEditingSession.Save save = session.save();
                assertTrue(String.valueOf(save.reconciliation), save.written);
                assertFalse(session.isDirty());
                assertEquals("the save wrote the draft", DRAFT, session.snapshot().title); //$NON-NLS-1$

                FormEditingSession.Reconciliation reload = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.NOTHING, reload.outcome);
                assertEquals("a reload keeps the saved draft", DRAFT, session.snapshot().title); //$NON-NLS-1$
            }
            finally
            {
                OpenFormSessions.release(session);
            }

            FormEditingSession reopened = OpenFormSessions.open(probe.file());
            try
            {
                assertFalse("the reopened session is clean", reopened.isDirty()); //$NON-NLS-1$
                assertEquals("the reopened session reads the saved draft", DRAFT, //$NON-NLS-1$
                    reopened.snapshot().title);
            }
            finally
            {
                OpenFormSessions.release(reopened);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /**
     * A save whose monitor is canceled writes nothing and keeps the draft: a save that outlives
     * its editor cannot land. A later save with no monitor still writes it.
     */
    @Test
    public void aCancelledSaveWritesNothingAndKeepsTheDraft() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                NullProgressMonitor monitor = new NullProgressMonitor();
                monitor.setCanceled(true);
                FormEditingSession.Save cancelled = session.save(monitor);
                assertFalse(cancelled.written);
                assertEquals(FormEditingSession.CANCELLED, cancelled.reconciliation);
                assertArrayEquals("the canceled save wrote nothing", probe.original(), probe.bytes()); //$NON-NLS-1$
                assertTrue("the draft stays", session.isDirty()); //$NON-NLS-1$
                assertEquals(DRAFT, session.snapshot().title);

                FormEditingSession.Save written = session.save();
                assertTrue(String.valueOf(written.reconciliation), written.written);
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

    // ---- the helpers -----------------------------------------------------------------------

    /** The editor's atomic refresh: settling reports the conflict and never drops the draft. */
    @Test
    public void settleNeverDropsTheDraft() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                byte[] foreign = withForeignLayout(FOREIGN);
                probe.anotherWriterWrites(foreign);

                FormEditingSession.Reconciliation conflict = session.settle();
                assertEquals(FormEditingSession.Reconciliation.CONFLICT, conflict.outcome);
                assertTrue("the draft stays", session.isDirty()); //$NON-NLS-1$
                assertEquals(DRAFT, session.snapshot().title);
                assertArrayEquals(foreign, probe.bytes());

                FormEditingSession.Reconciliation discard = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, discard.outcome);
                FormEditingSession.Reconciliation settled = session.settle();
                assertEquals(FormEditingSession.Reconciliation.NOTHING, settled.outcome);
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

    /** A clean session at a foreign layout is reread by settling; there is no draft to keep. */
    @Test
    public void settleRereadsACleanSession() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                byte[] foreign = withForeignLayout(FOREIGN);
                probe.anotherWriterWrites(foreign);
                FormEditingSession.Reconciliation settled = session.settle();
                assertEquals(FormEditingSession.Reconciliation.REREAD, settled.outcome);
                assertFalse(session.isDirty());
                assertEquals(FOREIGN, session.snapshot().title);
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

    /**
     * The editor's own question before a refresh: a conflict is reported, the draft and the file
     * stand as they were, and only the explicit reconciliation clears it.
     */
    @Test
    public void hasConflictReportsWithoutChangingTheSession() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertFalse("a clean session has no conflict", session.hasConflict()); //$NON-NLS-1$
                assertTrue(theDraft(session).done);
                byte[] foreign = withForeignLayout(FOREIGN);
                probe.anotherWriterWrites(foreign);

                assertTrue("the outside layout conflicts with the draft", session.hasConflict()); //$NON-NLS-1$
                assertTrue("asking did not drop it", session.isDirty()); //$NON-NLS-1$
                assertEquals(DRAFT, session.snapshot().title);
                assertArrayEquals("asking wrote nothing", foreign, probe.bytes()); //$NON-NLS-1$

                FormEditingSession.Reconciliation discard = session.reconcile();
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, discard.outcome);
                assertFalse("the explicit discard ends the conflict", session.hasConflict()); //$NON-NLS-1$
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

    /** A clean session at a foreign layout has nothing to lose, so it is not a conflict. */
    @Test
    public void aCleanSessionHasNoConflict() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                probe.anotherWriterWrites(withForeignLayout(FOREIGN));
                assertFalse(session.hasConflict());
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

    /** A module-only outside write over a layout-only draft is no conflict: nothing of the draft it replaces. */
    @Test
    public void aModuleOnlyChangeWithoutAModuleDraftIsNoConflict() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                assertTrue(theDraft(session).done);
                probe.anotherWriterWrites(withForeignModule());
                assertFalse("the layout draft survives the module write", session.hasConflict()); //$NON-NLS-1$
                assertTrue(session.formChanged());
                assertFalse(session.moduleChanged());
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

    /** @return the edit that puts {@link #DRAFT} on the form's title */
    private static FormEditingSession.Edit theDraft(FormEditingSession session)
    {
        return session.edit("set_property", Map.of("property", "title", "value", DRAFT)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
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

    private static OrdinaryFormFile theContainersFile() throws java.io.IOException
    {
        return OrdinaryFormFile.read(probe.file().getLocation().toFile().toPath());
    }

    private static String moduleOf()
    {
        try
        {
            return theContainersFile().moduleText();
        }
        catch (java.io.IOException e)
        {
            throw new AssertionError(e);
        }
    }
}
