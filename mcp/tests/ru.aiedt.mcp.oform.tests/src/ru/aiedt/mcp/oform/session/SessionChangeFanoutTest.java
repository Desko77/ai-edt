/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The session-scoped change notice every editor of one {@code Form.oform} subscribes to: a
 * successful edit, save, undo or state-changing reconciliation reaches every receiver, a refused
 * edit, a conflict and a settling that changes nothing reach none, a removed receiver is not
 * called, and one receiver that throws does not stop the others. This is what keeps a second
 * editor's snapshot and dirty flag from staying at the values they had before the first one
 * edited.
 */
public class SessionChangeFanoutTest
{
    private static final String PROJECT = "AiEdtOformSessionNoticeProbe"; //$NON-NLS-1$

    private static SessionProbe probe;

    @BeforeClass
    public static void aProjectWithOneOrdinaryForm() throws Exception
    {
        probe = SessionProbe.with(PROJECT, SessionProbe.fixture("fixtures/item-form-v27.form"), "\r\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @AfterClass
    public static void theProjectGoes()
    {
        if (probe != null)
        {
            probe.dispose();
        }
    }

    /** An edit of one editor reaches the other editor, whose snapshot and dirty flag move with it. */
    @Test
    public void anEditReachesEveryEditorOfTheFile() throws Exception
    {
        try
        {
            FormEditingSession first = OpenFormSessions.open(probe.file());
            FormEditingSession second = OpenFormSessions.open(probe.file());
            AtomicInteger firstCount = new AtomicInteger();
            AtomicInteger secondCount = new AtomicInteger();
            FormEditingSession.ChangeListener firstListener = session -> firstCount.incrementAndGet();
            FormEditingSession.ChangeListener secondListener = session -> secondCount.incrementAndGet();
            first.addChangeListener(firstListener);
            second.addChangeListener(secondListener);
            try
            {
                assertFalse(second.isDirty());
                FormEditingSession.Edit edit = first.edit("set_property", //$NON-NLS-1$
                    Map.of("property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertTrue(edit.message, edit.done);
                assertEquals(1, firstCount.get());
                assertEquals("the other editor heard the edit", 1, secondCount.get()); //$NON-NLS-1$
                assertTrue("and sees the shared draft", second.isDirty()); //$NON-NLS-1$
                assertEquals("Проба", second.snapshot().title); //$NON-NLS-1$

                assertTrue(first.save().written);
                assertEquals("the other editor heard the save", 2, secondCount.get()); //$NON-NLS-1$
                assertFalse("and is clean with it", second.isDirty()); //$NON-NLS-1$
                assertEquals("Проба", second.snapshot().title); //$NON-NLS-1$
            }
            finally
            {
                first.removeChangeListener(firstListener);
                second.removeChangeListener(secondListener);
                OpenFormSessions.release(second);
                OpenFormSessions.release(first);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** A refusal, a conflict and a settling that changes nothing notify nobody. */
    @Test
    public void aRefusalAndASettlingThatChangesNothingNotifyNobody() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            AtomicInteger count = new AtomicInteger();
            FormEditingSession.ChangeListener listener = changed -> count.incrementAndGet();
            session.addChangeListener(listener);
            try
            {
                assertTrue(session.edit("set_property", Map.of("property", "title", "value", "Проба")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    .done);
                assertEquals(1, count.get());

                probe.anotherWriterWrites(foreignLayout());
                FormEditingSession.Edit refused = session.edit("set_property", //$NON-NLS-1$
                    Map.of("property", "title", "value", "Другая")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertFalse(refused.done);
                FormEditingSession.Reconciliation settled = session.settle();
                assertEquals(FormEditingSession.Reconciliation.CONFLICT, settled.outcome);
                assertEquals("nothing was discarded, nothing was announced", 1, count.get()); //$NON-NLS-1$
                assertTrue("the draft stays", session.isDirty()); //$NON-NLS-1$
            }
            finally
            {
                session.removeChangeListener(listener);
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** A save and an undo each reach the receivers once. */
    @Test
    public void aSaveAndAnUndoReachTheReceivers() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            AtomicInteger count = new AtomicInteger();
            FormEditingSession.ChangeListener listener = changed -> count.incrementAndGet();
            session.addChangeListener(listener);
            try
            {
                assertTrue(session.edit("set_property", Map.of("property", "title", "value", "Проба")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    .done);
                assertEquals(1, count.get());
                assertTrue(session.save().written);
                assertEquals("the save is a state change", 2, count.get()); //$NON-NLS-1$
                FormEditingSession.Reversal undo = session.undo();
                assertTrue(undo.message, undo.done);
                assertEquals("the undo is a state change", 3, count.get()); //$NON-NLS-1$
            }
            finally
            {
                session.removeChangeListener(listener);
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** A receiver removed on dispose is not called any more. */
    @Test
    public void aRemovedReceiverIsNotCalled() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            AtomicInteger count = new AtomicInteger();
            FormEditingSession.ChangeListener listener = changed -> count.incrementAndGet();
            session.addChangeListener(listener);
            session.removeChangeListener(listener);
            try
            {
                assertTrue(session.edit("set_property", Map.of("property", "title", "value", "Проба")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    .done);
                assertEquals(0, count.get());
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

    /** One receiver that throws does not stop the other receivers. */
    @Test
    public void oneThrowingReceiverDoesNotStopAnother() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            AtomicInteger count = new AtomicInteger();
            FormEditingSession.ChangeListener broken = changed -> {
                throw new IllegalStateException("the receiver is broken"); //$NON-NLS-1$
            };
            FormEditingSession.ChangeListener listener = changed -> count.incrementAndGet();
            session.addChangeListener(broken);
            session.addChangeListener(listener);
            try
            {
                assertTrue(session.edit("set_property", Map.of("property", "title", "value", "Проба")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    .done);
                assertEquals("the good receiver still heard it", 1, count.get()); //$NON-NLS-1$
            }
            finally
            {
                session.removeChangeListener(broken);
                session.removeChangeListener(listener);
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** A reconciliation that discards reaches the receivers; a second one that changes nothing does not. */
    @Test
    public void aStateChangingReconciliationReachesTheReceivers() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            AtomicInteger count = new AtomicInteger();
            FormEditingSession.ChangeListener listener = changed -> count.incrementAndGet();
            session.addChangeListener(listener);
            try
            {
                assertTrue(session.edit("set_property", Map.of("property", "title", "value", "Проба")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    .done);
                probe.anotherWriterWrites(foreignLayout());
                assertEquals(FormEditingSession.Reconciliation.CHANGES_DISCARDED, session.reconcile().outcome);
                assertEquals(2, count.get());
                assertEquals(FormEditingSession.Reconciliation.NOTHING, session.reconcile().outcome);
                assertEquals("a reconciliation that changed nothing is not announced", 2, count.get()); //$NON-NLS-1$
            }
            finally
            {
                session.removeChangeListener(listener);
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** A settling that changes state reaches the receivers once; the next one that changes nothing does not loop. */
    @Test
    public void aStateChangingSettleReachesOnceAndDoesNotLoop() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            AtomicInteger count = new AtomicInteger();
            FormEditingSession.ChangeListener listener = changed -> count.incrementAndGet();
            session.addChangeListener(listener);
            try
            {
                probe.anotherWriterWrites(foreignLayout());
                FormEditingSession.Reconciliation first = session.settle();
                assertEquals(FormEditingSession.Reconciliation.REREAD, first.outcome);
                assertEquals("the state-changing settle is announced once", 1, count.get()); //$NON-NLS-1$
                FormEditingSession.Reconciliation second = session.settle();
                assertEquals(FormEditingSession.Reconciliation.NOTHING, second.outcome);
                assertEquals("a settle that changes nothing is not announced", 1, count.get()); //$NON-NLS-1$
            }
            finally
            {
                session.removeChangeListener(listener);
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** A read-only settling is news once; repeating it over the same unreadable file is not. */
    @Test
    public void aReadOnlySettleIsAnnouncedOnce() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            AtomicInteger count = new AtomicInteger();
            FormEditingSession.ChangeListener listener = changed -> count.incrementAndGet();
            session.addChangeListener(listener);
            try
            {
                probe.anotherWriterWrites("not a container".getBytes(java.nio.charset.StandardCharsets.UTF_8)); //$NON-NLS-1$
                assertEquals(FormEditingSession.Reconciliation.READ_ONLY, session.settle().outcome);
                assertEquals("the first read-only answer is announced", 1, count.get()); //$NON-NLS-1$
                assertEquals(FormEditingSession.Reconciliation.READ_ONLY, session.settle().outcome);
                assertEquals("the same read-only answer is not repeated", 1, count.get()); //$NON-NLS-1$
            }
            finally
            {
                session.removeChangeListener(listener);
                OpenFormSessions.release(session);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    private static byte[] foreignLayout()
    {
        ru.aiedt.mcp.oform.OrdinaryFormFile file = ru.aiedt.mcp.oform.OrdinaryFormFile.of(null,
            ru.aiedt.mcp.oform.V8Container.read(probe.original()), probe.original());
        try
        {
            ru.aiedt.mcp.oform.layout.LayoutEditor editor = ru.aiedt.mcp.oform.layout.LayoutEditor.over(
                ru.aiedt.mcp.oform.BraceTree.parse(file.formText()), "Catalog", "ru"); //$NON-NLS-1$ //$NON-NLS-2$
            ru.aiedt.mcp.oform.layout.LayoutEditor.Outcome set = editor.setTitle("Чужой заголовок"); //$NON-NLS-1$
            assertTrue(set.message, set.done);
            file.setFormText(editor.text());
        }
        catch (ru.aiedt.mcp.oform.layout.UnsupportedLayoutVersionException e)
        {
            throw new AssertionError(e.getMessage(), e);
        }
        return file.toBytes();
    }
}
