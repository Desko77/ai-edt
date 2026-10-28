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

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The registry of open forms: every editor of one {@code Form.oform} - the page of the form, the
 * module beside it, whatever else opens it - works on one session, and the protection the session
 * gives, its hold over the file's unsaved changes, stands until the last editor closes. One
 * editor's edit is the other's, and closing one of two leaves the session with its history intact.
 */
public class TwoEditorsShareOneSessionTest
{
    private static final String PROJECT = "AiEdtOformSessionRegistryProbe"; //$NON-NLS-1$

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

    /** Two editors of one file share its session; closing one of them changes nothing for the other. */
    @Test
    public void twoEditorsShareTheSessionUntilTheLastOneCloses() throws Exception
    {
        try
        {
            FormEditingSession first = OpenFormSessions.open(probe.file());
            try
            {
                assertEquals(1, OpenFormSessions.editorsOf(probe.file()));
                FormEditingSession second = OpenFormSessions.open(probe.file());
                assertEquals("both editors are counted", 2, OpenFormSessions.editorsOf(probe.file())); //$NON-NLS-1$
                assertTrue("the file is held", OpenFormSessions.isHeld(probe.file())); //$NON-NLS-1$
                assertEquals("one session for both editors", first.id(), second.id()); //$NON-NLS-1$

                FormEditingSession.Edit edit = first.edit("set_property", Map.of( //$NON-NLS-1$
                    "property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertTrue(edit.message, edit.done);
                assertTrue("the hold covers the unsaved change", OpenFormSessions.holdsChanges(probe.file())); //$NON-NLS-1$
                assertTrue("the other editor sees the edit on its own session", second.isDirty()); //$NON-NLS-1$

                OpenFormSessions.release(second);
                assertEquals(1, OpenFormSessions.editorsOf(probe.file()));
                assertTrue("one editor closed, the file stays held", OpenFormSessions.isHeld(probe.file())); //$NON-NLS-1$
                assertTrue("and the shared history stands", OpenFormSessions.holdsChanges(probe.file())); //$NON-NLS-1$
                assertTrue(first.isDirty());

                OpenFormSessions.release(first);
                assertEquals(0, OpenFormSessions.editorsOf(probe.file()));
                assertFalse("the last editor closed ends the session", OpenFormSessions.isHeld(probe.file())); //$NON-NLS-1$
                assertFalse("and the hold with it", OpenFormSessions.holdsChanges(probe.file())); //$NON-NLS-1$
            }
            finally
            {
                OpenFormSessions.release(first);
            }
        }
        finally
        {
            probe.restore();
        }
    }

    /** A session that ends with the last editor does not take the next one's edits with it. */
    @Test
    public void aFreshSessionAfterTheLastEditorClosed() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            FormEditingSession.Edit edit = session.edit("set_property", Map.of( //$NON-NLS-1$
                "property", "title", "value", "Проба")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            assertTrue(edit.message, edit.done);
            OpenFormSessions.release(session);
            assertFalse(OpenFormSessions.isHeld(probe.file()));

            FormEditingSession reopened = OpenFormSessions.open(probe.file());
            try
            {
                assertFalse("the unsaved edit of the closed session is gone with it", reopened.isDirty()); //$NON-NLS-1$
                FormEditingSession.Edit fresh = reopened.edit("set_property", Map.of( //$NON-NLS-1$
                    "property", "title", "value", "Проба2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                assertTrue(fresh.message, fresh.done);
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
}
