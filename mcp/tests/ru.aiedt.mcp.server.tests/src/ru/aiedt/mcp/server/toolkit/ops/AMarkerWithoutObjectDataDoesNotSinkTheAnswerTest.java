/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * One marker whose object data is not computed does not take the whole answer down with it.
 */
public class AMarkerWithoutObjectDataDoesNotSinkTheAnswerTest
{
    /** A read that throws, as a marker without object data does, is given the placeholder. */
    @Test
    public void aReadThatThrowsIsGivenThePlaceholder()
    {
        assertEquals(ProjectProblemsReader.OBJECT_NOT_COMPUTED, ProjectProblemsReader.presentationFrom(() -> {
            throw new NullPointerException();
        }));
    }

    /** A read that answers keeps its own presentation. */
    @Test
    public void aReadThatAnswersKeepsItsPresentation()
    {
        assertEquals("Catalog.Goods", ProjectProblemsReader.presentationFrom(() -> "Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The note is added only when such markers were met, and names the remedy. */
    @Test
    public void theNoteNamesTheCountAndTheRemedy()
    {
        assertEquals("", ProjectProblemsReader.uncomputedNote(0)); //$NON-NLS-1$
        String note = ProjectProblemsReader.uncomputedNote(3);
        assertTrue(note, note.contains("3 marker(s)")); //$NON-NLS-1$
        assertTrue(note, note.contains("clean_project")); //$NON-NLS-1$
    }

    /** A failure without a message is named by its class, never by the word null. */
    @Test
    public void aFailureWithoutTextIsNamedByItsClass()
    {
        assertEquals("NullPointerException", ProjectProblemsReader.failureText(new NullPointerException())); //$NON-NLS-1$
        assertEquals("no model", ProjectProblemsReader.failureText(new IllegalStateException("no model"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(ProjectProblemsReader.failureText(new RuntimeException((String)null)).contains("null")); //$NON-NLS-1$
    }
}
