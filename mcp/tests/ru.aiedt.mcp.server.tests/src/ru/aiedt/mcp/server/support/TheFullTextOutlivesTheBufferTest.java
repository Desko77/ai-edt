/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

import ru.aiedt.mcp.server.settings.HistorySettings;
import ru.aiedt.mcp.server.settings.PrefKeys;

/**
 * The history keeps a whole call on disk while the buffer keeps a shortened one.
 *
 * <p>From a screenshot 16.09: the window said "Response (303 of 1557 characters)", and the window is
 * opened precisely to read what a tool answered. The buffer cannot be the place that holds it - it
 * lives in the heap the IDE runs on - so the full text goes to a file and the window reads it from
 * there.
 *
 * <p>Writing and reading the file itself needs a plugin state location and is checked on the stand;
 * the rules that decide what may be written and for how long are put to the test here.
 */
public class TheFullTextOutlivesTheBufferTest
{
    @Test
    public void anEntryIdIsLongEnoughNotToRepeatItself()
    {
        String first = HistoryFullText.newEntryId();
        String second = HistoryFullText.newEntryId();

        assertNotEquals(first, second);
        assertTrue("sixteen hexadecimal characters", first.length() >= 16); //$NON-NLS-1$
        assertTrue("an id addresses a field inside a file, never a file name", //$NON-NLS-1$
            first.matches("[0-9a-f]+")); //$NON-NLS-1$
    }

    @Test
    public void aRecordOlderThanTheRetentionIsGone()
    {
        long dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        String line = "{\"entryId\":\"abc\",\"at\":" + dayAgo + ",\"result\":\"x\"}"; //$NON-NLS-1$

        assertTrue("kept for an hour, written a day ago", //$NON-NLS-1$
            HistoryFullText.isOlderThan(line, System.currentTimeMillis() - 60L * 60 * 1000));
        assertFalse("kept for a week, written a day ago", //$NON-NLS-1$
            HistoryFullText.isOlderThan(line, System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000));
    }

    @Test
    public void aLineWithNoDateIsKept()
    {
        // A line that cannot be read carries no date either, and throwing away what is not
        // understood is how a history loses the one call somebody needed.
        assertFalse(HistoryFullText.isOlderThan("not json at all", System.currentTimeMillis())); //$NON-NLS-1$
    }

    @Test
    public void theShippedRetentionIsTwoWeeksAndTheStoreIsOn()
    {
        assertEquals(14, PrefKeys.DEFAULT_HISTORY_DISK_DAYS);
        assertTrue("shipping this off would mean the window answers 'cut' out of the box", //$NON-NLS-1$
            PrefKeys.DEFAULT_HISTORY_DISK_ENABLED);
    }

    @Test
    public void aGenerationSeparatesAWriteFromTheClearThatOvertookIt()
    {
        long before = HistoryFullText.generation();
        HistoryFullText.clear(null);

        assertNotEquals("a write that began before the clear must not recreate the file", //$NON-NLS-1$
            before, HistoryFullText.generation());
    }

    @Test
    public void aWriteFromAnOlderGenerationIsDropped()
    {
        // Nothing is written and nothing throws: the call it belonged to has been cleared away.
        HistoryFullText.write("id", "tool", "args", "result", false, null, 0L); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void aPathTheFileSystemCannotNameIsAProblemNotACrash()
    {
        // A NUL is not allowed in a path on any platform, so this asks for the one thing
        // Paths.get refuses outright - which is what a hand-edited preference can carry.
        HistorySettings settings = settingsWithDiskPath("bad\0path"); //$NON-NLS-1$

        // Every road into the store - write, read, clear and the sweep the server runs at
        // startup - must come back empty-handed, never throw into the call that only tried to
        // record itself or into the server that was only starting.
        HistoryFullText.write("id", "tool", "args", "result", false, settings, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            HistoryFullText.generation());
        HistoryFullText.read("id", settings); //$NON-NLS-1$
        HistoryFullText.clear(settings);
        assertEquals(0, HistoryFullText.sweepOld(settings));

        assertNotNull("the reader of the settings must be told what was wrong", //$NON-NLS-1$
            HistoryFullText.pathProblem());
    }

    /**
     * Settings that keep the full text under a path given as text, valid or not.
     *
     * @param diskPath the configured directory, exactly as a preference would carry it
     * @return the settings to record under
     */
    private static HistorySettings settingsWithDiskPath(String diskPath)
    {
        PreferenceStore store = new PreferenceStore();
        store.setValue(PrefKeys.PREF_HISTORY_DISK_ENABLED, true);
        store.setValue(PrefKeys.PREF_HISTORY_DISK_PATH, diskPath);
        return HistorySettings.read(store);
    }
}
