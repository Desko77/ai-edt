/**
 * AI-EDT - 1C AI tools for EDT - Tests
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
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The record beside a stored dump-info copy keeps what it knows when a load mark comes off it: a
 * record count without content survives the clear, and a rebuild that leaves a copy without
 * records still takes the mark off, or names why it could not.
 */
public class AnOutsideChangeRecordKeepsWhatItKnowsTest
{
    private static final String BASE = "file:e:/bases/demo"; //$NON-NLS-1$

    private static final String LOAD = "E:/snaps/before.dt"; //$NON-NLS-1$

    private static final String WHEN = "2026-09-29T10:00:00Z"; //$NON-NLS-1$

    private Path dir;

    /**
     * @throws IOException when the directory cannot be made
     */
    @Before
    public void aStoreDirectory() throws IOException
    {
        dir = Files.createTempDirectory("outside-change-record-"); //$NON-NLS-1$
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

    /**
     * Writes a copy without a single record, and a record beside it carrying an older content and
     * a load mark.
     *
     * @return the copy
     * @throws IOException when a file cannot be written
     */
    private Path anEmptyCopyUnderALoadMark() throws IOException
    {
        Path copy = dir.resolve(DumpInfoProbe.FILE_NAME);
        Files.write(copy, "<ConfigDumpInfo/>".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
        InfobaseOutsideChange.of(BASE, "content-then", 4).withLoad(LOAD, WHEN) //$NON-NLS-1$
            .writeTo(InfobaseOutsideChange.recordFileOf(copy));
        return copy;
    }

    /** A record count written without content is kept, through the clear of a load mark too. */
    @Test
    public void clearingALoadKeepsTheRecordCount() throws IOException
    {
        Path record = dir.resolve(InfobaseOutsideChange.FILE_NAME);
        InfobaseOutsideChange.of(BASE, null, 7).withLoad(LOAD, WHEN).writeTo(record);
        assertEquals(7, InfobaseOutsideChange.read(record).records);

        assertNull(InfobaseOutsideChange.clearTheLoad(record));

        InfobaseOutsideChange left = InfobaseOutsideChange.read(record);
        assertFalse(left.replacedByLoad());
        assertEquals(7, left.records);
        assertEquals(BASE, left.identity);
    }

    /** A rebuild whose copy carries no record takes the load mark off. */
    @Test
    public void aRebuildFromACopyWithoutRecordsClearsTheLoadMark() throws IOException
    {
        Path copy = anEmptyCopyUnderALoadMark();
        String[] failure = new String[1];

        DumpInfoRebuilder.rememberPairAndCopy(BASE, "2.7", "8.3.27.2214", null, copy, failure); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(failure[0]);
        assertFalse(InfobaseOutsideChange.read(InfobaseOutsideChange.recordFileOf(copy)).replacedByLoad());
    }

    /** A load mark such a rebuild cannot take off stays, and the rebuild is told why. */
    @Test
    public void aLoadMarkTheRebuildCannotClearIsNamed() throws IOException
    {
        Path copy = anEmptyCopyUnderALoadMark();
        Path record = InfobaseOutsideChange.recordFileOf(copy);
        String[] failure = new String[1];

        try (UnwritableRecord locked = UnwritableRecord.of(record))
        {
            DumpInfoRebuilder.rememberPairAndCopy(BASE, "2.7", "8.3.27.2214", null, copy, failure); //$NON-NLS-1$ //$NON-NLS-2$
        }

        assertNotNull(failure[0]);
        assertTrue(InfobaseOutsideChange.read(record).replacedByLoad());
    }
}
