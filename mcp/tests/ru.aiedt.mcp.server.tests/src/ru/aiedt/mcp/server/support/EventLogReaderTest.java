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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pins what the event-log reader answers, against a log built here rather than a real one.
 * <p>
 * Built here on purpose: a real log is somebody's names, machines and logins, and this file goes
 * into a public repository. The shapes below are the shapes a real log has - verified against one
 * before this was written - with the content replaced.
 * </p>
 * <p>
 * The half worth most is what it refuses. A base whose log is in the single-file SQLite form, and
 * a base with no log at all, must come back as two different, named answers. Both used to be the
 * same empty list, and an empty list over a base full of events is the failure this whole reader
 * exists to avoid.
 * </p>
 */
public class EventLogReaderTest
{
    private static final String DICTIONARY = "1CV8LOG(ver 2.0)\n"
        + "e9cf90cc-ef43-4405-8422-ccd0250ccb6c\n\n"
        + "{1,071523a4-516f-4fce-ba4b-0d11ab7a1893,\"Auditor\",1},\n"
        + "{1,181523a4-516f-4fce-ba4b-0d11ab7a1894,\"\",2},\n"
        + "{2,\"BUILDHOST\",1},\n"
        + "{3,\"Designer\",1},\n"
        + "{4,\"_$Session$_.Start\",1},\n"
        + "{4,\"_$Session$_.Finish\",2},\n"
        + "{4,\"_$InfoBase$_.DBConfigUpdate\",3}";

    private static final String RECORDS = "1CV8LOG(ver 2.0)\n"
        + "e9cf90cc-ef43-4405-8422-ccd0250ccb6c\n\n"
        + "{20260225174514,N,\n{0,0},1,1,1,7,1,I,\"\",0,\n{\"U\"},\"\",0,0,0,11,0,\n{0}\n},\n"
        + "{20260226080648,U,\n{0,0},1,1,1,7,3,W,\"Structure changed\",0,\n"
        + "{\"P\",\n{2,\n{\"B\",0}\n}\n},\"\",0,0,0,11,0,\n{0}\n},\n"
        + "{20260227090000,N,\n{0,0},2,1,1,8,2,I,\"\",0,\n{\"U\"},\"\",0,0,0,12,0,\n{0}\n}";

    private Path infobase;

    /**
     * Lays out an infobase directory with a log in it.
     *
     * @throws IOException when the files cannot be written.
     */
    @Before
    public void writeLog() throws IOException
    {
        infobase = Files.createTempDirectory("aiedt-eventlog-test"); //$NON-NLS-1$
        Path logDir = infobase.resolve(EventLogReader.LOG_DIRECTORY);
        Files.createDirectories(logDir);
        Files.write(logDir.resolve("1Cv8.lgf"), DICTIONARY.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        Files.write(logDir.resolve("20260225174514.lgp"), RECORDS.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
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

    /** The records come back resolved against the dictionaries, in the order written. */
    @Test
    public void recordsAreResolvedAgainstTheDictionaries()
    {
        EventLogReader.Result r = EventLogReader.read(infobase, new EventLogReader.Query());

        assertTrue("the log was not read: " + r.error, r.ok); //$NON-NLS-1$
        assertEquals("all three records should be reported", 3, r.rows.size()); //$NON-NLS-1$
        Map<String, Object> first = r.rows.get(0);
        assertEquals("2026-02-25 17:45:14", first.get("moment")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("_$Session$_.Start", first.get("event")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Information", first.get("severity")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Auditor", first.get("user")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("BUILDHOST", first.get("computer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Designer", first.get("application")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the session number must not drift into a neighbouring field", //$NON-NLS-1$
            11, first.get("session")); //$NON-NLS-1$
        assertEquals(7, first.get("connection")); //$NON-NLS-1$
    }

    /** A user with no name falls back to the identifier rather than coming back blank. */
    @Test
    public void aNamelessUserIsReportedByIdentifier()
    {
        EventLogReader.Result r = EventLogReader.read(infobase, new EventLogReader.Query());

        Object user = r.rows.get(2).get("user"); //$NON-NLS-1$
        assertNotNull("a nameless user came back as nothing at all", user); //$NON-NLS-1$
        assertTrue("the identifier should stand in for the missing name: " + user, //$NON-NLS-1$
            String.valueOf(user).startsWith("181523a4")); //$NON-NLS-1$
    }

    /** Severity, comment and transaction status are spelled out rather than left as letters. */
    @Test
    public void theSecondRecordCarriesItsCommentAndStatus()
    {
        EventLogReader.Result r = EventLogReader.read(infobase, new EventLogReader.Query());

        Map<String, Object> second = r.rows.get(1);
        assertEquals("Warning", second.get("severity")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Structure changed", second.get("comment")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Committed", second.get("transaction")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Each filter narrows the answer, and a date may be written short. */
    @Test
    public void filtersNarrowTheAnswer()
    {
        EventLogReader.Query byEvent = new EventLogReader.Query();
        byEvent.event = "dbconfigupdate"; //$NON-NLS-1$
        assertEquals("the event filter should leave one row", //$NON-NLS-1$
            1, EventLogReader.read(infobase, byEvent).rows.size());

        EventLogReader.Query byUser = new EventLogReader.Query();
        byUser.user = "Auditor"; //$NON-NLS-1$
        assertEquals("the user filter should leave two rows", //$NON-NLS-1$
            2, EventLogReader.read(infobase, byUser).rows.size());

        EventLogReader.Query bySeverity = new EventLogReader.Query();
        bySeverity.severity = "Warning"; //$NON-NLS-1$
        assertEquals("the severity filter should leave one row", //$NON-NLS-1$
            1, EventLogReader.read(infobase, bySeverity).rows.size());

        EventLogReader.Query fromDay = new EventLogReader.Query();
        fromDay.from = "2026-02-26"; //$NON-NLS-1$
        assertEquals("a short date should mean that day from midnight", //$NON-NLS-1$
            2, EventLogReader.read(infobase, fromDay).rows.size());
    }

    /** The limit stops the answer and says it did, rather than looking complete. */
    @Test
    public void theLimitSaysItCutTheAnswerShort()
    {
        EventLogReader.Query q = new EventLogReader.Query();
        q.limit = 2;

        EventLogReader.Result r = EventLogReader.read(infobase, q);

        assertEquals(2, r.rows.size());
        assertTrue("a cut answer must say it was cut", r.truncated); //$NON-NLS-1$
    }

    /**
     * A base with no log at all is told apart from one whose log this cannot read.
     *
     * @throws IOException when the directories cannot be arranged.
     */
    @Test
    public void aMissingLogAndAnUnreadableOneAreDifferentAnswers() throws IOException
    {
        Path bare = Files.createTempDirectory("aiedt-eventlog-bare"); //$NON-NLS-1$
        try
        {
            EventLogReader.Result none = EventLogReader.read(bare, new EventLogReader.Query());
            assertFalse("a base with no log should not report a successful empty read", none.ok); //$NON-NLS-1$
            assertNull("nothing there is not an unsupported format", none.unsupported); //$NON-NLS-1$
            assertTrue("the answer should name the missing directory: " + none.error, //$NON-NLS-1$
                none.error.contains(EventLogReader.LOG_DIRECTORY));

            Path logDir = bare.resolve(EventLogReader.LOG_DIRECTORY);
            Files.createDirectories(logDir);
            Files.write(logDir.resolve("1Cv8.lgd"), new byte[]{ 'S', 'Q', 'L' }); //$NON-NLS-1$

            EventLogReader.Result sqlite = EventLogReader.read(bare, new EventLogReader.Query());
            assertFalse("an unreadable format is not a successful read", sqlite.ok); //$NON-NLS-1$
            assertEquals("the format should be named, not merely refused", //$NON-NLS-1$
                "1Cv8.lgd", sqlite.unsupported); //$NON-NLS-1$
        }
        finally
        {
            Path logDir = bare.resolve(EventLogReader.LOG_DIRECTORY);
            if (Files.isDirectory(logDir))
            {
                Files.deleteIfExists(logDir.resolve("1Cv8.lgd")); //$NON-NLS-1$
                Files.deleteIfExists(logDir);
            }
            Files.deleteIfExists(bare);
        }
    }

    /**
     * An empty log directory reads successfully with nothing in it - that is a real answer.
     *
     * @throws IOException when the record file cannot be removed.
     */
    @Test
    public void anEmptyLogReadsAsSuccessWithNoRows() throws IOException
    {
        Path logDir = infobase.resolve(EventLogReader.LOG_DIRECTORY);
        Files.deleteIfExists(logDir.resolve("20260225174514.lgp")); //$NON-NLS-1$

        EventLogReader.Result r = EventLogReader.read(infobase, new EventLogReader.Query());

        assertTrue("a dictionary with no records is still a readable log", r.ok); //$NON-NLS-1$
        assertTrue("there is nothing to report", r.rows.isEmpty()); //$NON-NLS-1$
    }
}
