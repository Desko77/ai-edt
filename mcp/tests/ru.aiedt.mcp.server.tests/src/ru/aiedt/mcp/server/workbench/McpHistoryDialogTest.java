/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.workbench;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.eclipse.swt.SWT;
import org.junit.Test;

/**
 * Covers the one thing about the history window that can be wrong without looking wrong.
 * <p>
 * {@code SWT.MODELESS} is zero. Asking for a modeless window by OR-ing it into the style a JFace
 * dialog arrives with reads exactly like a request for a modeless window and produces a modal one -
 * a window that locks the whole IDE while somebody reads a list. Nothing in the code says so, and
 * nothing but opening it would.
 * </p>
 */
public class McpHistoryDialogTest
{
    /** What a JFace dialog carries before anything is done to it. */
    private static final int JFACE_DEFAULT = SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL;

    @Test
    public void modelessIsZeroSoOringItInWouldHaveChangedNothing()
    {
        // The premise of the whole test, stated rather than assumed.
        assertEquals(0, SWT.MODELESS);

        int naive = JFACE_DEFAULT | SWT.MODELESS;
        assertTrue("asking for modeless by OR leaves the window modal", //$NON-NLS-1$
            (naive & McpHistoryDialog.MODAL_BITS) != 0);
    }

    @Test
    public void everyModalBitIsClearedFromTheInheritedStyle()
    {
        assertEquals(0, McpHistoryDialog.modeless(JFACE_DEFAULT) & McpHistoryDialog.MODAL_BITS);
    }

    @Test
    public void theOtherKindsOfModalityAreClearedToo()
    {
        int style = SWT.DIALOG_TRIM | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL;

        assertEquals(0, McpHistoryDialog.modeless(style) & McpHistoryDialog.MODAL_BITS);
    }

    @Test
    public void theWindowStaysResizableAndKeepsItsTrim()
    {
        int style = McpHistoryDialog.modeless(JFACE_DEFAULT);

        assertTrue((style & SWT.RESIZE) != 0);
        // The trim is what makes it a window one can close and move; clearing modality must not
        // take it with it.
        assertTrue((style & SWT.TITLE) != 0);
        assertTrue((style & SWT.CLOSE) != 0);
    }
}
