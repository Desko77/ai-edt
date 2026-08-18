/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;

/**
 * Covers the optional column carrying what a check attached to its marker.
 * <p>
 * A finding's message is sometimes all a caller gets, and sometimes it is not enough:
 * "property (method) of object not found" does not say what was looked for. EDT hangs a
 * {@code Map<String, String>} off every marker, of which only the position was ever
 * read. Whatever else a check put there is the difference between reproducing its rule
 * and guessing - so it can now be asked for.
 * </p>
 */
public class ProjectProblemsExtraInfoTest
{
    @Test
    public void thePositionKeysAreLeftToTheirOwnColumns()
    {
        Map<String, String> extra = new HashMap<>();
        extra.put(StandardExtraInfo.TEXT_LINE.getKey(), "42"); //$NON-NLS-1$
        extra.put(StandardExtraInfo.TEXT_OFFSET.getKey(), "1024"); //$NON-NLS-1$
        extra.put(StandardExtraInfo.TEXT_LENGTH.getKey(), "7"); //$NON-NLS-1$

        // Nothing but position: the column would repeat the Line column on every row.
        assertNull(ProjectProblemsReader.renderExtraInfo(extra));
    }

    @Test
    public void anythingElseIsHandedOverAsWritten()
    {
        Map<String, String> extra = new HashMap<>();
        extra.put(StandardExtraInfo.TEXT_LINE.getKey(), "42"); //$NON-NLS-1$
        extra.put(StandardExtraInfo.MODEL_FEATURE_ID.getKey(), "attributes"); //$NON-NLS-1$

        String rendered = ProjectProblemsReader.renderExtraInfo(extra);

        assertTrue(rendered.contains("attributes")); //$NON-NLS-1$
        // The position is still not repeated, even alongside something worth showing.
        assertFalse(rendered.contains("42")); //$NON-NLS-1$
    }

    @Test
    public void aCheckSOwnKeysSurviveUninterpreted()
    {
        // The keys past the standard ones belong to the check, not to us. Reading them
        // is the caller's job; ours is not to lose them.
        Map<String, String> extra = new HashMap<>();
        extra.put("unknownMember", "ЗначениеРеквизитаОбъекта"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("unknownMember=ЗначениеРеквизитаОбъекта", //$NON-NLS-1$
            ProjectProblemsReader.renderExtraInfo(extra));
    }

    @Test
    public void theOrderIsTheKeysAndNotTheMapS()
    {
        // A caller diffing our answer against its own must not see a change that is
        // only map iteration order.
        Map<String, String> one = new LinkedHashMap<>();
        one.put("b", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        one.put("a", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> other = new LinkedHashMap<>();
        other.put("a", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        other.put("b", "2"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("a=1; b=2", ProjectProblemsReader.renderExtraInfo(one)); //$NON-NLS-1$
        assertEquals(ProjectProblemsReader.renderExtraInfo(other),
            ProjectProblemsReader.renderExtraInfo(one));
    }

    @Test
    public void nothingToShowIsNotAnEmptyCellWaitingToBeParsed()
    {
        assertNull(ProjectProblemsReader.renderExtraInfo(null));
        assertNull(ProjectProblemsReader.renderExtraInfo(new HashMap<>()));
    }
}
