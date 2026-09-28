/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A form picture reopens the form's editor, which discards what it has not saved: an editor with
 * unsaved changes is left alone and the call refused. The page to show is found the way 1C compares
 * names.
 */
public class ADirtyEditorIsNotClosedForAScreenshotTest
{
    private static final String FORM = "Catalog.Goods.Form.ItemForm"; //$NON-NLS-1$

    /**
     * An editor with unsaved changes refuses the reopen and says why.
     */
    @Test
    public void aDirtyEditorRefusesTheReopen()
    {
        String refusal = EditorImageCapture.reopenRefusal(FORM, true, true);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains(FORM));
        assertTrue(refusal, refusal.contains("unsaved")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("nothing was closed")); //$NON-NLS-1$
    }

    /**
     * A clean editor, and no editor at all, may be reopened.
     */
    @Test
    public void aCleanOrAbsentEditorIsReopened()
    {
        assertNull(EditorImageCapture.reopenRefusal(FORM, true, false));
        assertNull(EditorImageCapture.reopenRefusal(FORM, false, false));
    }

    /**
     * A name matches exactly, or ignoring case when asked to; anything but a string never does.
     */
    @Test
    public void aPageNameMatchesIgnoringCaseOnlyWhenAsked()
    {
        assertTrue(EditorImageCapture.nameMatches("Страница1", "Страница1", true)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(EditorImageCapture.nameMatches("Страница1", "страница1", true)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(EditorImageCapture.nameMatches("Страница1", "страница1", false)); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(EditorImageCapture.nameMatches(null, "Страница1", false)); //$NON-NLS-1$
        assertFalse(EditorImageCapture.nameMatches(Integer.valueOf(1), "1", false)); //$NON-NLS-1$
    }
}
