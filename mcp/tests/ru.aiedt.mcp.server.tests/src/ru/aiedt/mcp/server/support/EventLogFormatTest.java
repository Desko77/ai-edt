/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Pins how the event-log brace format is taken apart.
 * <p>
 * The shape of these files is a fact, and a fact worth pinning separately from what the fields
 * mean: a record read one field short shifts everything after it by one and produces a row that
 * looks entirely reasonable and is entirely wrong. The cases below are the ones a real log
 * actually contains - a nested group as a value, an empty group, an empty quoted string, a
 * doubled quote, and a file cut off mid-record because it is being written to.
 * </p>
 */
public class EventLogFormatTest
{
    /** A record shaped like the real ones, with the values a session event carries. */
    private static final String LOG = "1CV8LOG(ver 2.0)\n"
        + "e9cf90cc-ef43-4405-8422-ccd0250ccb6c\n\n"
        + "{20260225174514,N,\n{0,0},1,1,1,1,1,I,\"\",0,\n"
        + "{\"P\",\n{518,\n{\"S\",\"\"},\n{\"S\",\"HOST\\\\user\"}\n}\n},\"\",0,0,0,1,0,\n{0}\n},\n"
        + "{20260225174530,N,\n{0,0},1,1,1,1,3,I,\"\",0,\n{\"U\"},\"\",0,0,0,2,0,\n{0}\n}";

    /** The header is read for what it declares, not guessed at. */
    @Test
    public void theHeaderIsRecognisedAndVersioned()
    {
        assertTrue("a log was not recognised as one", EventLogFormat.looksLikeLog(LOG)); //$NON-NLS-1$
        assertEquals("2.0", EventLogFormat.declaredVersion(LOG)); //$NON-NLS-1$
        assertFalse("arbitrary text was taken for a log", //$NON-NLS-1$
            EventLogFormat.looksLikeLog("just some text")); //$NON-NLS-1$
    }

    /**
     * Every record has the same number of fields, and a nested group counts as ONE of them.
     * <p>
     * This is the assertion that catches a field shift. A group followed by a comma used to add
     * the empty text buffer as a second value, which pushed the session number into the slot after
     * it and turned every row into a plausible lie.
     * </p>
     */
    @Test
    public void everyRecordHasTheSameFieldCountAndAGroupIsOneField()
    {
        List<List<Object>> records = EventLogFormat.groups(LOG);

        assertEquals("both records should be read", 2, records.size()); //$NON-NLS-1$
        assertEquals("a record is 19 fields", 19, records.get(0).size()); //$NON-NLS-1$
        assertEquals("both records are the same width", //$NON-NLS-1$
            records.get(0).size(), records.get(1).size());
    }

    /** The fields land where they are read from, scalars and groups alike. */
    @Test
    public void fieldsAreReadableByPosition()
    {
        List<Object> first = EventLogFormat.groups(LOG).get(0);

        assertEquals("20260225174514", EventLogFormat.text(first, 0)); //$NON-NLS-1$
        assertEquals("N", EventLogFormat.text(first, 1)); //$NON-NLS-1$
        assertNotNull("the transaction group was not read as a group", //$NON-NLS-1$
            EventLogFormat.nested(first, 2));
        assertEquals(1, EventLogFormat.number(first, 3));
        assertEquals(1, EventLogFormat.number(first, 7));
        assertEquals("I", EventLogFormat.text(first, 8)); //$NON-NLS-1$
        assertEquals("the comment is an empty string, not a missing field", //$NON-NLS-1$
            "", EventLogFormat.text(first, 9)); //$NON-NLS-1$
        assertNotNull("the data group was not read as a group", EventLogFormat.nested(first, 11)); //$NON-NLS-1$
        assertEquals("the session number is in its own slot", 1, EventLogFormat.number(first, 16)); //$NON-NLS-1$
        assertEquals("the second record carries a different session", //$NON-NLS-1$
            2, EventLogFormat.number(EventLogFormat.groups(LOG).get(1), 16));
    }

    /** An empty group is a value, and does not swallow the field after it. */
    @Test
    public void anEmptyGroupIsOneValue()
    {
        List<List<Object>> records = EventLogFormat.groups("1CV8LOG(ver 2.0)\nid\n{1,{},2}"); //$NON-NLS-1$

        assertEquals(1, records.size());
        assertEquals("an empty group must not vanish or split", 3, records.get(0).size()); //$NON-NLS-1$
        assertNotNull(EventLogFormat.nested(records.get(0), 1));
        assertEquals(2, EventLogFormat.number(records.get(0), 2));
    }

    /** A doubled quote inside a quoted string stands for one quote. */
    @Test
    public void aDoubledQuoteIsOneQuote()
    {
        List<List<Object>> records =
            EventLogFormat.groups("1CV8LOG(ver 2.0)\nid\n{1,\"say \"\"hello\"\" now\",2}"); //$NON-NLS-1$

        assertEquals("say \"hello\" now", EventLogFormat.text(records.get(0), 1)); //$NON-NLS-1$
        assertEquals("the field after a quoted string is still in place", //$NON-NLS-1$
            2, EventLogFormat.number(records.get(0), 2));
    }

    /**
     * A file cut off mid-record gives up the whole records and drops the fragment.
     * <p>
     * The log is appended to while it is read, so this is the normal case rather than corruption.
     * Half a record parses into a record that looks whole, which is why the fragment goes.
     * </p>
     */
    @Test
    public void aTruncatedTailIsDroppedNotHalfRead()
    {
        String cut = "1CV8LOG(ver 2.0)\nid\n{20260225174514,N,{0,0},1},\n{20260225174530,N,{0,"; //$NON-NLS-1$

        List<List<Object>> records = EventLogFormat.groups(cut);

        assertEquals("only the whole record should survive", 1, records.size()); //$NON-NLS-1$
        assertEquals("20260225174514", EventLogFormat.text(records.get(0), 0)); //$NON-NLS-1$
    }

    /** Asking past the end answers with absence rather than an exception. */
    @Test
    public void readingPastTheEndIsNotAFailure()
    {
        List<Object> record = EventLogFormat.groups(LOG).get(0);

        assertEquals("", EventLogFormat.text(record, 99)); //$NON-NLS-1$
        assertEquals(-1, EventLogFormat.number(record, 99));
        assertNull(EventLogFormat.nested(record, 99));
        assertEquals("", EventLogFormat.text(null, 0)); //$NON-NLS-1$
    }
}
