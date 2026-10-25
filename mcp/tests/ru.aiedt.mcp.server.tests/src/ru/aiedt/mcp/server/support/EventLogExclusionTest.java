/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins what the event log leaves out when a query names events to drop.
 * <p>
 * The log here is built rather than taken from a base, like the one the reader test uses, and for
 * the same reason: a real log is somebody's logins and machines. What it carries that the reader's
 * fixture does not is a run of transaction events - the events that fill most of a real answer -
 * beside ordinary ones, so an exclusion can be checked to keep what it did not name.
 * </p>
 */
public class EventLogExclusionTest
{
    private static final String DICTIONARY = "1CV8LOG(ver 2.0)\n"
        + "e9cf90cc-ef43-4405-8422-ccd0250ccb6c\n\n"
        + "{1,071523a4-516f-4fce-ba4b-0d11ab7a1893,\"Auditor\",1},\n"
        + "{2,\"BUILDHOST\",1},\n"
        + "{3,\"Designer\",1},\n"
        + "{4,\"_$Transaction$_.Begin\",1},\n"
        + "{4,\"_$Transaction$_.Commit\",2},\n"
        + "{4,\"_$Transaction$_.Rollback\",3},\n"
        + "{4,\"_$Session$_.Start\",4},\n"
        + "{4,\"_$InfoBase$_.DBConfigUpdate\",5}";

    /** Five records in the order the log holds them: three of a transaction, two of anything else. */
    private static final String RECORDS = "1CV8LOG(ver 2.0)\n"
        + "e9cf90cc-ef43-4405-8422-ccd0250ccb6c\n\n"
        + "{20260301090000,U,\n{0,0},1,1,1,11,1,I,\"\",0,\n{\"U\"},\"\",0,0,0,11,0,\n{0}\n},\n"
        + "{20260301090001,U,\n{0,0},1,1,1,11,2,I,\"\",0,\n{\"U\"},\"\",0,0,0,11,0,\n{0}\n},\n"
        + "{20260301090002,N,\n{0,0},1,1,1,11,4,I,\"\",0,\n{\"U\"},\"\",0,0,0,11,0,\n{0}\n},\n"
        + "{20260301090003,N,\n{0,0},1,1,1,11,5,I,\"\",0,\n{\"U\"},\"\",0,0,0,11,0,\n{0}\n},\n"
        + "{20260301090004,U,\n{0,0},1,1,1,11,3,I,\"\",0,\n{\"U\"},\"\",0,0,0,11,0,\n{0}\n}";

    private Path infobase;

    /**
     * Lays out an infobase directory with a log in it.
     *
     * @throws IOException when the files cannot be written.
     */
    @Before
    public void writeLog() throws IOException
    {
        infobase = Files.createTempDirectory("aiedt-eventlog-exclude-test"); //$NON-NLS-1$
        Path logDir = infobase.resolve(EventLogReader.LOG_DIRECTORY);
        Files.createDirectories(logDir);
        Files.write(logDir.resolve("1Cv8.lgf"), DICTIONARY.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        Files.write(logDir.resolve("20260301090000.lgp"), RECORDS.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
    }

    /**
     * Clears the directory.
     *
     * @throws IOException when it cannot be cleared.
     */
    @After
    public void removeLog() throws IOException
    {
        if (infobase == null || !Files.isDirectory(infobase))
        {
            return;
        }
        Path logDir = infobase.resolve(EventLogReader.LOG_DIRECTORY);
        if (Files.isDirectory(logDir))
        {
            try (java.util.stream.Stream<Path> entries = Files.list(logDir))
            {
                for (Path entry : entries.toArray(Path[]::new))
                {
                    Files.deleteIfExists(entry);
                }
            }
            Files.deleteIfExists(logDir);
        }
        Files.deleteIfExists(infobase);
    }

    /**
     * The events of one answer, in the order they came back.
     *
     * @param result what the read answered.
     * @return the event names.
     */
    private static List<String> eventsOf(EventLogReader.Result result)
    {
        List<String> events = new ArrayList<>();
        for (Map<String, Object> row : result.rows)
        {
            events.add(String.valueOf(row.get("event"))); //$NON-NLS-1$
        }
        return events;
    }

    /** Read as usual, the fixture answers every record it holds. */
    @Test
    public void withoutAnExclusionEveryRecordComesBack()
    {
        EventLogReader.Result r = EventLogReader.read(infobase, new EventLogReader.Query());

        assertTrue("the log was not read: " + r.error, r.ok); //$NON-NLS-1$
        assertEquals(5, r.rows.size());
    }

    /**
     * A mask on the start of the name leaves out every event it covers and nothing else.
     */
    @Test
    public void aMaskLeavesOutTheEventsItNamesAndKeepsTheRest()
    {
        EventLogReader.Query q = new EventLogReader.Query();
        q.excludeEvents = Collections.singletonList("_$Transaction$_*"); //$NON-NLS-1$

        EventLogReader.Result r = EventLogReader.read(infobase, q);

        assertTrue("the log was not read: " + r.error, r.ok); //$NON-NLS-1$
        assertEquals(Arrays.asList("_$Session$_.Start", "_$InfoBase$_.DBConfigUpdate"), //$NON-NLS-1$ //$NON-NLS-2$
            eventsOf(r));
        for (String event : eventsOf(r))
        {
            assertFalse("a transaction event survived the mask: " + event, //$NON-NLS-1$
                event.startsWith("_$Transaction$_")); //$NON-NLS-1$
        }
    }

    /** A name without a star leaves out that event alone, whatever else it starts like. */
    @Test
    public void aWholeNameLeavesOutOneEvent()
    {
        EventLogReader.Query q = new EventLogReader.Query();
        q.excludeEvents = Collections.singletonList("_$Transaction$_.Begin"); //$NON-NLS-1$

        EventLogReader.Result r = EventLogReader.read(infobase, q);

        List<String> events = eventsOf(r);
        assertFalse("the named event came back", events.contains("_$Transaction$_.Begin")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("only the named event may leave", 4, events.size()); //$NON-NLS-1$
        assertTrue("a sibling of the named event was dropped with it: " + events, //$NON-NLS-1$
            events.contains("_$Transaction$_.Commit") && events.contains("_$Transaction$_.Rollback")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The names are matched without regard to case, in either spelling of the pattern. */
    @Test
    public void theCaseOfTheNameDoesNotMatter()
    {
        EventLogReader.Query masked = new EventLogReader.Query();
        masked.excludeEvents = Collections.singletonList("_$transaction$_*"); //$NON-NLS-1$
        assertEquals(2, EventLogReader.read(infobase, masked).rows.size());

        EventLogReader.Query whole = new EventLogReader.Query();
        whole.excludeEvents = Collections.singletonList("_$INFOBASE$_.dbconfigupdate"); //$NON-NLS-1$
        assertEquals(4, EventLogReader.read(infobase, whole).rows.size());
    }

    /**
     * The exclusion is applied before the limit, so what was left out does not fill the answer.
     */
    @Test
    public void theLimitCountsWhatIsLeftAfterTheExclusion()
    {
        EventLogReader.Query cut = new EventLogReader.Query();
        cut.excludeEvents = Collections.singletonList("_$Transaction$_*"); //$NON-NLS-1$
        cut.limit = 2;

        EventLogReader.Result withExclusion = EventLogReader.read(infobase, cut);
        assertEquals("the two events that are left must fill both rows", //$NON-NLS-1$
            Arrays.asList("_$Session$_.Start", "_$InfoBase$_.DBConfigUpdate"), //$NON-NLS-1$ //$NON-NLS-2$
            eventsOf(withExclusion));

        EventLogReader.Query withoutExclusion = new EventLogReader.Query();
        withoutExclusion.limit = 2;
        EventLogReader.Result cutShort = EventLogReader.read(infobase, withoutExclusion);
        assertEquals("without the exclusion the same limit stops on transaction events", //$NON-NLS-1$
            Arrays.asList("_$Transaction$_.Begin", "_$Transaction$_.Commit"), //$NON-NLS-1$ //$NON-NLS-2$
            eventsOf(cutShort));
        assertTrue("a cut answer must say it was cut", cutShort.truncated); //$NON-NLS-1$
    }

    /** A pattern that names nothing leaves the answer as it was, rather than emptying it. */
    @Test
    public void anEmptyPatternLeavesTheAnswerAlone()
    {
        EventLogReader.Query q = new EventLogReader.Query();
        q.excludeEvents = Arrays.asList("", "   ", "*"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(5, EventLogReader.read(infobase, q).rows.size());
    }
}
