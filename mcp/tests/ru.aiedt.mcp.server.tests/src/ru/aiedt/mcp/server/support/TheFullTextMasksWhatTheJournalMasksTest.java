/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.settings.HistorySettings;
import ru.aiedt.mcp.server.settings.PrefKeys;

/**
 * The full-text store masks personal data the way the journal does.
 * <p>
 * The journal masks on the way out and the tests for that do not look here, so a store that wrote
 * the raw text would not be caught by any of them. The flag is the same one the journal obeys:
 * one switch decides what any on-disk copy of a call may carry.
 * </p>
 */
public class TheFullTextMasksWhatTheJournalMasksTest
{
    private Path root;

    /**
     * Gives each test a store directory of its own.
     *
     * @throws IOException when the directory cannot be made
     */
    @Before
    public void createStoreDirectory() throws IOException
    {
        root = Files.createTempDirectory("aiedt-fulltext"); //$NON-NLS-1$
    }

    /**
     * Removes the directory and everything written into it.
     *
     * @throws IOException when it cannot be walked
     */
    @After
    public void removeStoreDirectory() throws IOException
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
    public void personalDataInTheResultIsMaskedOnTheWayToTheDisk() throws IOException
    {
        HistoryFullText.write("entry1", "send_mail", "projectName=Demo", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Comment: INN 7707083893, phone +7 495 123-45-67, mail ivanov@example.com", //$NON-NLS-1$
            false, settings(true), HistoryFullText.generation());

        String written = Files.readString(root.resolve(HistoryFullText.FILE_NAME));
        assertFalse(written.contains("7707083893")); //$NON-NLS-1$
        assertFalse(written.contains("+7 495 123-45-67")); //$NON-NLS-1$
        assertFalse(written.contains("ivanov@example.com")); //$NON-NLS-1$
        assertTrue(written.contains("INN")); //$NON-NLS-1$
        assertTrue(written.contains("PHONE")); //$NON-NLS-1$
        assertTrue(written.contains("EMAIL")); //$NON-NLS-1$
    }

    @Test
    public void personalDataInTheArgumentsIsMaskedToo() throws IOException
    {
        HistoryFullText.write("entry2", "send_mail", //$NON-NLS-1$ //$NON-NLS-2$
            "address=ivanov@example.com", "ok", //$NON-NLS-1$ //$NON-NLS-2$
            false, settings(true), HistoryFullText.generation());

        String written = Files.readString(root.resolve(HistoryFullText.FILE_NAME));
        assertFalse(written.contains("ivanov@example.com")); //$NON-NLS-1$
        assertTrue(written.contains("EMAIL")); //$NON-NLS-1$
        // The tool name is not free text and must survive untouched, or the store stops being
        // searchable by the one field anybody searches it by.
        assertTrue(written.contains("send_mail")); //$NON-NLS-1$
    }

    @Test
    public void withRedactionOffTheTextIsWrittenAsItStands() throws IOException
    {
        HistoryFullText.write("entry3", "send_mail", "projectName=Demo", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "wrote to ivanov@example.com", //$NON-NLS-1$
            false, settings(false), HistoryFullText.generation());

        assertTrue(Files.readString(root.resolve(HistoryFullText.FILE_NAME))
            .contains("ivanov@example.com")); //$NON-NLS-1$
    }

    /**
     * Settings that keep the full text in this test's directory.
     * <p>
     * The store is seeded with the shipped defaults first, the way the workspace is: storing a
     * value equal to the store's own default deletes the entry, and on an unseeded store that would
     * leave the redaction flag reading as shipped while the test believes it was switched off.
     * </p>
     *
     * @param redact whether personal data is masked on the way out
     * @return the settings to record under
     */
    private HistorySettings settings(boolean redact)
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PrefKeys.PREF_HISTORY_ENABLED, PrefKeys.DEFAULT_HISTORY_ENABLED);
        store.setDefault(PrefKeys.PREF_HISTORY_DISK_ENABLED, PrefKeys.DEFAULT_HISTORY_DISK_ENABLED);
        store.setDefault(PrefKeys.PREF_HISTORY_FILE_REDACT, PrefKeys.DEFAULT_HISTORY_FILE_REDACT);
        store.setValue(PrefKeys.PREF_HISTORY_DISK_ENABLED, true);
        store.setValue(PrefKeys.PREF_HISTORY_DISK_PATH, root.toString());
        store.setValue(PrefKeys.PREF_HISTORY_FILE_REDACT, redact);
        return HistorySettings.read(store);
    }
}
