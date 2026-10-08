/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.labels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import ru.aiedt.mcp.server.labels.model.Marker;

/**
 * What one marker edit outcome carries: the stored change, or the refusal code with its sentence.
 */
public class MarkerWriteOutcomeTest
{
    /** A stored edit answers its marker, its changed assignments and no code. */
    @Test
    public void aStoredEditCarriesTheMarkerAndTheCount()
    {
        Marker marker = new Marker("bug", "#112233", "needs a look");
        MarkerWriteOutcome outcome = MarkerWriteOutcome.stored(marker, 3);

        assertFalse(outcome.isRefused());
        assertNull(outcome.getCode());
        assertSame(marker, outcome.getMarker());
        assertEquals(3, outcome.getChangedAssignments());
        assertTrue(outcome.getDetail() != null && !outcome.getDetail().isEmpty());
    }

    /** A refusal answers its code, nothing stored and no changed assignments. */
    @Test
    public void aRefusalAnswersItsCodeAndChangesNothing()
    {
        MarkerWriteOutcome outcome = MarkerWriteOutcome.refused(MarkerWriteOutcome.CHANGED_ON_DISK,
            "the file changed after it was read");

        assertTrue(outcome.isRefused());
        assertEquals("changedOnDisk", outcome.getCode()); //$NON-NLS-1$
        assertNull(outcome.getMarker());
        assertEquals(0, outcome.getChangedAssignments());
        assertEquals("the file changed after it was read", outcome.getDetail()); //$NON-NLS-1$
    }

    /** A refusal without a code or without a sentence is a programming error, not an answer. */
    @Test
    public void aRefusalNeedsACodeAndADetail()
    {
        try
        {
            MarkerWriteOutcome.refused(null, "sentence");
            fail("a null code must be refused");
        }
        catch (IllegalArgumentException expected)
        {
            // the outcome is built wrong, not answered
        }
        try
        {
            MarkerWriteOutcome.refused(MarkerWriteOutcome.TAG_EXISTS, null);
            fail("a null detail must be refused");
        }
        catch (IllegalArgumentException expected)
        {
            // the outcome is built wrong, not answered
        }
    }
}
