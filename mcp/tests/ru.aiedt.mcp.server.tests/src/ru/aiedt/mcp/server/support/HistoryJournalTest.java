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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the on-disk copy of the call history: one line per call, masked when asked, and bounded.
 * <p>
 * The bound is the part worth holding down. This file grows on every tool call for as long as the
 * setting is on, which may be months, and it lives in a state location nobody looks at - so if the
 * cap ever stopped working nothing would say so until the disk was full.
 * </p>
 */
public class HistoryJournalTest
{
    private Path root;

    private Path file;

    private Path rotated;

    /**
     * Gives each test a directory of its own, so nothing reads back another test's journal.
     *
     * @throws IOException when the directory cannot be made
     */
    @Before
    public void createJournalDirectory() throws IOException
    {
        root = Files.createTempDirectory("aiedt-history"); //$NON-NLS-1$
        file = root.resolve(HistoryJournal.FILE_NAME);
        rotated = root.resolve(HistoryJournal.ROTATED_NAME);
    }

    /**
     * Removes the directory and everything written into it.
     *
     * @throws IOException when it cannot be walked
     */
    @After
    public void removeJournalDirectory() throws IOException
    {
        if (root == null || !Files.exists(root))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(root))
        {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void eachCallIsOneLineOfJson() throws IOException
    {
        HistoryJournal.appendTo(file, call("read_module_source", "ok"), false); //$NON-NLS-1$ //$NON-NLS-2$
        HistoryJournal.appendTo(file, call("validate_query", "ok"), false); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("{")); //$NON-NLS-1$
        assertTrue(lines.get(0).contains("read_module_source")); //$NON-NLS-1$
        assertTrue(lines.get(1).contains("validate_query")); //$NON-NLS-1$
    }

    @Test
    public void personalDataIsMaskedWhenAsked() throws IOException
    {
        HistoryJournal.appendTo(file, call("send_mail", "wrote to ivanov@example.com"), true); //$NON-NLS-1$ //$NON-NLS-2$

        String written = Files.readString(file);
        assertFalse(written.contains("ivanov@example.com")); //$NON-NLS-1$
        assertTrue(written.contains("EMAIL")); //$NON-NLS-1$
        // The tool name is not free text and must survive untouched, or the journal stops being
        // searchable by the one field anybody searches it by.
        assertTrue(written.contains("send_mail")); //$NON-NLS-1$
    }

    @Test
    public void withoutMaskingTheTextIsWrittenAsItStands() throws IOException
    {
        HistoryJournal.appendTo(file, call("send_mail", "wrote to ivanov@example.com"), false); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(Files.readString(file).contains("ivanov@example.com")); //$NON-NLS-1$
    }

    @Test
    public void atTheCapTheJournalIsMovedAsideRatherThanEmptied() throws IOException
    {
        HistoryJournal.appendTo(file, call("first_call", "ok"), false, 10_000L); //$NON-NLS-1$ //$NON-NLS-2$
        long afterOne = Files.size(file);
        // A cap no larger than what is already written is what the next append rotates on.
        HistoryJournal.appendTo(file, call("second_call", "ok"), false, afterOne); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(Files.exists(rotated));
        // The older call is kept, not discarded: that is the difference between rotating and
        // truncating, and it is the whole reason the file exists.
        assertTrue(Files.readString(rotated).contains("first_call")); //$NON-NLS-1$
        assertTrue(Files.readString(file).contains("second_call")); //$NON-NLS-1$
        assertFalse(Files.readString(file).contains("first_call")); //$NON-NLS-1$
    }

    @Test
    public void aSecondRotationReplacesTheOneSetAsideBefore() throws IOException
    {
        HistoryJournal.appendTo(file, call("oldest", "ok"), false, 10_000L); //$NON-NLS-1$ //$NON-NLS-2$
        HistoryJournal.appendTo(file, call("middle", "ok"), false, 1L); //$NON-NLS-1$ //$NON-NLS-2$
        HistoryJournal.appendTo(file, call("newest", "ok"), false, 1L); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(Files.readString(rotated).contains("middle")); //$NON-NLS-1$
        assertTrue(Files.readString(file).contains("newest")); //$NON-NLS-1$
    }

    @Test
    public void nothingToWriteToIsNotAFailure()
    {
        // No path (no plugin state location) and no entry: both are ordinary during shutdown, and
        // neither may throw into the tool call that was only trying to record itself.
        HistoryJournal.appendTo(null, call("any_tool", "ok"), true); //$NON-NLS-1$ //$NON-NLS-2$
        HistoryJournal.appendTo(file, null, true);

        assertFalse(Files.exists(file));
    }

    @Test
    public void anUnwritableTargetIsSwallowed() throws IOException
    {
        // A directory where the journal should be: every write fails, and the caller must not know.
        Path blocked = Files.createDirectory(root.resolve("blocked")); //$NON-NLS-1$

        HistoryJournal.appendTo(blocked, call("any_tool", "ok"), false); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(Files.isDirectory(blocked));
    }

    private static Map<String, Object> call(String tool, String result)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("tool", tool); //$NON-NLS-1$
        entry.put("args", "projectName=Demo"); //$NON-NLS-1$ //$NON-NLS-2$
        entry.put("result", result); //$NON-NLS-1$
        entry.put("timestamp", 1_800_000_000_000L); //$NON-NLS-1$
        entry.put("durationMs", 12L); //$NON-NLS-1$
        entry.put("success", Boolean.TRUE); //$NON-NLS-1$
        entry.put("argsCut", Boolean.FALSE); //$NON-NLS-1$
        entry.put("resultChars", Integer.valueOf(result.length())); //$NON-NLS-1$
        return entry;
    }
}
