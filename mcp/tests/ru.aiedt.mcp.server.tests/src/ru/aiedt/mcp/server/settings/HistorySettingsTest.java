/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/**
 * Covers what the call history keeps, and what it does with a setting it cannot use.
 * <p>
 * The unset cases carry the weight here. An absent boolean reads as <code>false</code> from any
 * preference store, so a settings reader that trusts that answer turns recording off wherever the
 * default initializer has not run - and an empty history is indistinguishable from a quiet session,
 * so nothing would ever report it.
 * </p>
 */
public class HistorySettingsTest
{
    @Test
    public void withNoStoreAtAllTheShippedValuesApply()
    {
        HistorySettings settings = HistorySettings.read(null);

        assertTrue(settings.isEnabled());
        assertEquals(PrefKeys.DEFAULT_HISTORY_DEPTH, settings.depth());
        assertEquals(PrefKeys.DEFAULT_HISTORY_ARG_CHARS, settings.argChars());
        assertEquals(PrefKeys.DEFAULT_HISTORY_RESULT_CHARS, settings.resultChars());
        assertFalse(settings.isFileEnabled());
        assertTrue(settings.isFileRedacted());
    }

    @Test
    public void anEmptyStoreDoesNotReadAsRecordingSwitchedOff()
    {
        HistorySettings settings = HistorySettings.read(new PreferenceStore());

        assertTrue(settings.isEnabled());
        assertTrue(settings.isFileRedacted());
        assertEquals(PrefKeys.DEFAULT_HISTORY_DEPTH, settings.depth());
    }

    @Test
    public void storedValuesAreUsed()
    {
        PreferenceStore store = seeded();
        store.setValue(PrefKeys.PREF_HISTORY_ENABLED, false);
        store.setValue(PrefKeys.PREF_HISTORY_DEPTH, 42);
        store.setValue(PrefKeys.PREF_HISTORY_ARG_CHARS, 1000);
        store.setValue(PrefKeys.PREF_HISTORY_RESULT_CHARS, 2000);
        store.setValue(PrefKeys.PREF_HISTORY_FILE_ENABLED, true);
        store.setValue(PrefKeys.PREF_HISTORY_FILE_REDACT, false);

        HistorySettings settings = HistorySettings.read(store);

        assertFalse(settings.isEnabled());
        assertEquals(42, settings.depth());
        assertEquals(1000, settings.argChars());
        assertEquals(2000, settings.resultChars());
        assertTrue(settings.isFileEnabled());
        assertFalse(settings.isFileRedacted());
    }

    @Test
    public void anImpossibleDepthIsBroughtIntoRange()
    {
        PreferenceStore store = seeded();
        store.setValue(PrefKeys.PREF_HISTORY_DEPTH, 0);
        assertEquals(1, HistorySettings.read(store).depth());

        store.setValue(PrefKeys.PREF_HISTORY_DEPTH, -5);
        assertEquals(1, HistorySettings.read(store).depth());

        store.setValue(PrefKeys.PREF_HISTORY_DEPTH, PrefKeys.MAX_HISTORY_DEPTH * 100);
        assertEquals(PrefKeys.MAX_HISTORY_DEPTH, HistorySettings.read(store).depth());
    }

    @Test
    public void keepingNoTextIsAllowedButKeepingNegativeTextIsNot()
    {
        PreferenceStore store = seeded();
        store.setValue(PrefKeys.PREF_HISTORY_ARG_CHARS, 0);
        store.setValue(PrefKeys.PREF_HISTORY_RESULT_CHARS, -1);

        HistorySettings settings = HistorySettings.read(store);

        // Recording that a call happened without recording what was in it is a real choice.
        assertEquals(0, settings.argChars());
        assertEquals(0, settings.resultChars());
    }

    @Test
    public void anExtentBeyondTheOfferedMaximumIsCappedRatherThanTaken()
    {
        PreferenceStore store = seeded();
        // Shallow, so that the per-extent cap is what this measures and not the total budget.
        store.setValue(PrefKeys.PREF_HISTORY_DEPTH, 10);
        store.setValue(PrefKeys.PREF_HISTORY_RESULT_CHARS, Integer.MAX_VALUE);

        assertEquals(PrefKeys.MAX_HISTORY_CHARS, HistorySettings.read(store).resultChars());
    }

    @Test
    public void theBudgetBitesAtTheShippedDepthToo()
    {
        PreferenceStore store = seeded();
        store.setValue(PrefKeys.PREF_HISTORY_RESULT_CHARS, PrefKeys.MAX_HISTORY_CHARS);

        HistorySettings settings = HistorySettings.read(store);

        // 200 calls of 200 000 characters is forty million characters - eighty megabytes of char
        // for a list of recent calls. The guard is not reserved for absurd settings; it engages at
        // the shipped depth as soon as the extent is taken to its maximum.
        assertTrue(settings.resultChars() < PrefKeys.MAX_HISTORY_CHARS);
        assertTrue(settings.resultChars() > 0);
        long total = (long)settings.depth() * (settings.argChars() + settings.resultChars());
        assertTrue("total " + total, total <= PrefKeys.MAX_HISTORY_TOTAL_CHARS); //$NON-NLS-1$
    }

    @Test
    public void bothMaximaAtTheGreatestDepthAreBroughtInsideTheMemoryBudget()
    {
        PreferenceStore store = seeded();
        store.setValue(PrefKeys.PREF_HISTORY_DEPTH, PrefKeys.MAX_HISTORY_DEPTH);
        store.setValue(PrefKeys.PREF_HISTORY_ARG_CHARS, PrefKeys.MAX_HISTORY_CHARS);
        store.setValue(PrefKeys.PREF_HISTORY_RESULT_CHARS, PrefKeys.MAX_HISTORY_CHARS);

        HistorySettings settings = HistorySettings.read(store);

        // The depth is what was asked for; the extents are what the buffer can afford. Taken at
        // face value these three settings ask for some two billion characters, which does not
        // present as a rejected setting but as the IDE running out of heap.
        assertEquals(PrefKeys.MAX_HISTORY_DEPTH, settings.depth());
        long total = (long)settings.depth() * (settings.argChars() + settings.resultChars());
        assertTrue("total " + total, total <= PrefKeys.MAX_HISTORY_TOTAL_CHARS); //$NON-NLS-1$
        assertTrue(settings.argChars() > 0);
        assertTrue(settings.resultChars() > 0);
    }

    @Test
    public void settingsThatFitAreLeftExactlyAsAsked()
    {
        PreferenceStore store = seeded();
        store.setValue(PrefKeys.PREF_HISTORY_DEPTH, 500);
        store.setValue(PrefKeys.PREF_HISTORY_ARG_CHARS, 2000);
        store.setValue(PrefKeys.PREF_HISTORY_RESULT_CHARS, 4000);

        HistorySettings settings = HistorySettings.read(store);

        assertEquals(500, settings.depth());
        assertEquals(2000, settings.argChars());
        assertEquals(4000, settings.resultChars());
    }

    @Test
    public void theShippedSettingsAreNowhereNearTheBudget()
    {
        HistorySettings settings = HistorySettings.read(seeded());

        assertEquals(PrefKeys.DEFAULT_HISTORY_ARG_CHARS, settings.argChars());
        assertEquals(PrefKeys.DEFAULT_HISTORY_RESULT_CHARS, settings.resultChars());
    }

    @Test
    public void anExtentSetToZeroStaysZeroEvenWhenTheBudgetBites()
    {
        PreferenceStore store = seeded();
        store.setValue(PrefKeys.PREF_HISTORY_DEPTH, PrefKeys.MAX_HISTORY_DEPTH);
        store.setValue(PrefKeys.PREF_HISTORY_ARG_CHARS, 0);
        store.setValue(PrefKeys.PREF_HISTORY_RESULT_CHARS, PrefKeys.MAX_HISTORY_CHARS);

        HistorySettings settings = HistorySettings.read(store);

        // Keeping no text is a deliberate choice and scaling must not turn it into keeping a little.
        assertEquals(0, settings.argChars());
        assertTrue(settings.resultChars() > 0);
    }

    /**
     * A store set up the way the workspace is, with the shipped values as its defaults.
     * <p>
     * Seeding matters more than it looks. {@code setValue} DELETES the entry when the new value
     * equals the default, so on a store with no defaults at all - where every default is the type's
     * zero - storing <code>false</code> or <code>0</code> stores nothing, and the test would be
     * reading an absent key while believing it had written one. That is the shape of the workspace
     * too: {@link DefaultPreferences} seeds these six before anything reads them.
     * </p>
     *
     * @return the store to write test values into
     */
    private static PreferenceStore seeded()
    {
        PreferenceStore store = new PreferenceStore();
        store.setDefault(PrefKeys.PREF_HISTORY_ENABLED, PrefKeys.DEFAULT_HISTORY_ENABLED);
        store.setDefault(PrefKeys.PREF_HISTORY_DEPTH, PrefKeys.DEFAULT_HISTORY_DEPTH);
        store.setDefault(PrefKeys.PREF_HISTORY_ARG_CHARS, PrefKeys.DEFAULT_HISTORY_ARG_CHARS);
        store.setDefault(PrefKeys.PREF_HISTORY_RESULT_CHARS, PrefKeys.DEFAULT_HISTORY_RESULT_CHARS);
        store.setDefault(PrefKeys.PREF_HISTORY_FILE_ENABLED, PrefKeys.DEFAULT_HISTORY_FILE_ENABLED);
        store.setDefault(PrefKeys.PREF_HISTORY_FILE_REDACT, PrefKeys.DEFAULT_HISTORY_FILE_REDACT);
        return store;
    }
}
