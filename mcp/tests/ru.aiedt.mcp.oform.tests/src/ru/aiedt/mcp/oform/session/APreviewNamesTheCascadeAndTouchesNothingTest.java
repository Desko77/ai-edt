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
import ru.aiedt.mcp.oform.layout.FormLayout;
import ru.aiedt.mcp.oform.layout.LayoutReader;

/**
 * What a preview answers: the removal of a table a panel takes its actions from names the cascade
 * - the table it would take away and the panel it would leave altered - and the refusal without
 * the cascade names it too. Nothing moves: the session keeps its dirty state as it was, the
 * snapshot keeps its elements, and the container on disk keeps its bytes.
 */
public class APreviewNamesTheCascadeAndTouchesNothingTest
{
    private static final String PROJECT = "AiEdtOformSessionPreviewProbe"; //$NON-NLS-1$

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

    /** A preview of a removal names what would go and what would be left altered, and changes nothing. */
    @Test
    public void aPreviewOfARemovalNamesTheCascade() throws Exception
    {
        try
        {
            FormEditingSession session = OpenFormSessions.open(probe.file());
            try
            {
                FormEditingSession.Edit panel = session.edit("add_command_panel", Map.of( //$NON-NLS-1$
                    "name", "ПанельОткуда", //$NON-NLS-1$ //$NON-NLS-2$
                    "left", "0", "top", "500", "width", "400", "height", "25", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                    "actionSource", table)); //$NON-NLS-1$
                assertTrue(panel.message, panel.done);
                byte[] before = probe.bytes();
                FormSnapshot snapshot = session.snapshot();
                int elements = snapshot.allElements.size();

                FormEditingSession.Preview without = session.preview("remove_element", //$NON-NLS-1$
                    Map.of("elementName", table)); //$NON-NLS-1$
                assertFalse("the removal without the cascade is refused", without.done); //$NON-NLS-1$
                assertTrue(without.message, without.message.contains("cascade")); //$NON-NLS-1$

                FormEditingSession.Preview preview = session.preview("remove_element", //$NON-NLS-1$
                    Map.of("elementName", table, "cascade", "true")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                assertTrue(preview.message, preview.done);
                assertTrue(preview.removed + " names the table", preview.removed.contains(table)); //$NON-NLS-1$
                assertTrue(preview.changed + " names the panel left without its source", preview.changed.contains("ПанельОткуда")); //$NON-NLS-1$ //$NON-NLS-2$

                assertTrue("the dirty state stands as the edit left it", session.isDirty()); //$NON-NLS-1$
                assertEquals("the snapshot keeps its elements", elements, session.snapshot().allElements.size()); //$NON-NLS-1$
                assertEquals("the file keeps its bytes", snapshot.version, session.snapshot().version); //$NON-NLS-1$
                assertTrue(java.util.Arrays.equals(before, probe.bytes()));
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
}
