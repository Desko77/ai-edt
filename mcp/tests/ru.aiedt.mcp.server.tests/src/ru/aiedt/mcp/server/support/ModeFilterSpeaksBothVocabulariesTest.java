/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Holds the two spellings of a support mode together.
 * <p>
 * <b>A request and an answer name the same mode differently.</b> The schema asks for
 * CHANGES_NOT_ALLOWED and every answer carries ChangesNotAllowed. Compared as they stand the two
 * never matched, and a filtered listing came back as an empty page with success on it - measured
 * on a live configuration: 9088 objects unfiltered, 0 under any filter, including a filter for a
 * mode two objects actually held. An empty page is the answer for "no object is in that mode", so
 * the failure was invisible in the shape of a correct reply.
 * </p>
 */
public class ModeFilterSpeaksBothVocabulariesTest
{
    @Test
    public void theRequestSpellingMatchesTheAnswerSpelling()
    {
        assertTrue("this is the pair that never matched", //$NON-NLS-1$
            BmSupportRegistryHelper.sameMode("CHANGES_NOT_ALLOWED", "ChangesNotAllowed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(BmSupportRegistryHelper.sameMode("CHANGES_ALLOWED", "ChangesAllowed")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void eitherSpellingIsAcceptedFromTheRequest()
    {
        // Whichever way a caller writes it, including the way our own answers write it back.
        assertTrue(BmSupportRegistryHelper.sameMode("ChangesAllowed", "ChangesAllowed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(BmSupportRegistryHelper.sameMode("changes-allowed", "ChangesAllowed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(BmSupportRegistryHelper.sameMode("changesallowed", "ChangesAllowed")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void differentModesStillDoNotMatch()
    {
        // Reducing both sides must not reduce them so far that everything matches everything.
        assertFalse(BmSupportRegistryHelper.sameMode("CHANGES_ALLOWED", "ChangesNotAllowed")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(BmSupportRegistryHelper.sameMode("CANCELLED", "ChangesAllowed")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void anItemHoldingNoModeMatchesNothing()
    {
        assertFalse(BmSupportRegistryHelper.sameMode("CHANGES_ALLOWED", null)); //$NON-NLS-1$
    }

    @Test
    public void separatorsAndCaseAreWhatGetsReduced()
    {
        assertEquals("changesnotallowed", //$NON-NLS-1$
            BmSupportRegistryHelper.normaliseMode("CHANGES_NOT_ALLOWED")); //$NON-NLS-1$
        assertEquals("changesnotallowed", //$NON-NLS-1$
            BmSupportRegistryHelper.normaliseMode("ChangesNotAllowed")); //$NON-NLS-1$
        assertEquals("", BmSupportRegistryHelper.normaliseMode("___")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
