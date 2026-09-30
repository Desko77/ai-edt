/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.DumpInfoProbe;
import ru.aiedt.mcp.server.support.InfobaseOutsideChange;
import ru.aiedt.mcp.server.support.UnwritableRecord;

/**
 * After an update, recording the store's copy can fail in two ways that leave different records
 * behind: a load mark that could not be cleared, or a fresh record that could not replace the one
 * the previous update left. The answer names which one stayed.
 */
public class TheCopyRecordFailureSaysWhichRecordStayedTest
{
    private static final String BASE = "file:e:/bases/demo"; //$NON-NLS-1$

    private static final String DUMP = "<ConfigDumpInfo><ConfigVersions>" //$NON-NLS-1$
        + "<Metadata name=\"Catalog.Banks\" configVersion=\"1\"/>" //$NON-NLS-1$
        + "</ConfigVersions></ConfigDumpInfo>"; //$NON-NLS-1$

    private Path dir;

    private Path stored;

    private Path record;

    /**
     * @throws IOException when the directory cannot be made
     */
    @Before
    public void aStore() throws IOException
    {
        dir = Files.createTempDirectory("copy-record-failure-"); //$NON-NLS-1$
        stored = dir.resolve(DumpInfoProbe.FILE_NAME);
        record = InfobaseOutsideChange.recordFileOf(stored);
    }

    /**
     * @throws IOException when the directory cannot be removed
     */
    @After
    public void theStoreGoes() throws IOException
    {
        try (Stream<Path> walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** A copy that does not read leaves a load mark to clear; one that stays is named as kept. */
    @Test
    public void aLoadMarkThatCouldNotBeClearedIsNamedAsKept() throws IOException
    {
        InfobaseOutsideChange.of(BASE, "content-then", 4) //$NON-NLS-1$
            .withLoad("E:/snaps/before.dt", "2026-09-29T10:00:00Z").writeTo(record); //$NON-NLS-1$ //$NON-NLS-2$

        String sentence;
        try (UnwritableRecord locked = UnwritableRecord.of(record))
        {
            sentence = DatabaseUpdater.recordTheCopy(stored, BASE);
        }

        assertNotNull(sentence);
        assertTrue(sentence, sentence.startsWith("the load mark")); //$NON-NLS-1$
        assertTrue(sentence, sentence.contains("still refuses")); //$NON-NLS-1$
        assertTrue(InfobaseOutsideChange.read(record).replacedByLoad());
    }

    /** A copy that reads but cannot be recorded names the older record that stayed. */
    @Test
    public void aCopyThatCouldNotBeRecordedNamesThePreviousRecord() throws IOException
    {
        Files.write(stored, DUMP.getBytes(StandardCharsets.UTF_8));
        InfobaseOutsideChange.of(BASE, "content-then", 4).writeTo(record); //$NON-NLS-1$

        String sentence;
        try (UnwritableRecord locked = UnwritableRecord.of(record))
        {
            sentence = DatabaseUpdater.recordTheCopy(stored, BASE);
        }

        assertNotNull(sentence);
        assertTrue(sentence, sentence.contains("the record the previous update left stays")); //$NON-NLS-1$
        assertFalse(sentence, sentence.startsWith("the load mark")); //$NON-NLS-1$
        assertEquals("content-then", InfobaseOutsideChange.read(record).fingerprint); //$NON-NLS-1$
    }

    /** A copy that reads and is recorded says nothing, and the record is the copy's. */
    @Test
    public void aRecordedCopySaysNothing() throws IOException
    {
        Files.write(stored, DUMP.getBytes(StandardCharsets.UTF_8));

        assertNull(DatabaseUpdater.recordTheCopy(stored, BASE));

        InfobaseOutsideChange left = InfobaseOutsideChange.read(record);
        assertTrue(left.known());
        assertEquals(1, left.records);
    }
}
