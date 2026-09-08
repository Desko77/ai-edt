/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * A read that answers from the file while an editor holds something else describes a state the user
 * is no longer looking at, and the write that follows is made against it. The lines the editor
 * gives therefore have to be numbered exactly as the file reader numbers them: a line number that
 * meant one thing from the editor and another from disk would be worse than either.
 */
public class WhatTheEditorHoldsTest
{
    /** A trailing delimiter ends the last line rather than starting an empty one. */
    @Test
    public void aTrailingDelimiterDoesNotAddALine()
    {
        assertEquals(Arrays.asList("Процедура Один()", "КонецПроцедуры"), //$NON-NLS-1$ //$NON-NLS-2$
            EditorBuffer.linesOf("Процедура Один()\nКонецПроцедуры\n")); //$NON-NLS-1$
        assertEquals(Arrays.asList("Процедура Один()", "КонецПроцедуры"), //$NON-NLS-1$ //$NON-NLS-2$
            EditorBuffer.linesOf("Процедура Один()\nКонецПроцедуры")); //$NON-NLS-1$
    }

    /**
     * Both delimiters count as one break. A module saved by the platform carries CRLF, one saved
     * by the editor may carry LF, and the same text must number the same either way.
     */
    @Test
    public void bothDelimitersCountTheSame()
    {
        List<String> crlf = EditorBuffer.linesOf("а\r\nб\r\nв"); //$NON-NLS-1$
        List<String> lf = EditorBuffer.linesOf("а\nб\nв"); //$NON-NLS-1$
        List<String> cr = EditorBuffer.linesOf("а\rб\rв"); //$NON-NLS-1$
        assertEquals(lf, crlf);
        assertEquals(lf, cr);
        assertEquals(3, lf.size());
    }

    /** An empty line inside the text is a line; an empty text is no lines at all. */
    @Test
    public void anEmptyLineIsALineAndAnEmptyTextIsNone()
    {
        assertEquals(Arrays.asList("а", "", "б"), EditorBuffer.linesOf("а\n\nб")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(EditorBuffer.linesOf("").isEmpty()); //$NON-NLS-1$
        assertTrue(EditorBuffer.linesOf("\n").size() == 1); //$NON-NLS-1$
    }

    /**
     * No file, no editor: the callers then read and write the file exactly as they did before this
     * existed, rather than meeting an exception on a path that used to work.
     */
    @Test
    public void nothingHeldIsAnswerable()
    {
        assertNull(EditorBuffer.unsavedText(null));
        assertNull(EditorBuffer.unsavedLines(null));
        assertTrue(!EditorBuffer.hasUnsavedChanges(null));
    }
}
