/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.Test;

/**
 * What Restore Defaults does to the two Vanessa fields.
 * <p>
 * Every other path on the tab goes back to its shipped value, and these two were left holding
 * whatever they had. The gap reads as a partial restore: the user presses the button, the page looks
 * reset, and the next save quietly keeps an executable and a feature file that are nowhere to be
 * seen on a page that has just been restored.
 * </p>
 * <p>
 * The check is the round trip the user makes: restore the fields, then save, and read the store.
 * </p>
 */
public class RestoringDefaultsClearsTheVanessaFieldsTest
{
    private static final String CHOSEN_EPF = "C:/example/vanessa-automation.epf"; //$NON-NLS-1$

    private static final String CHOSEN_EXE = "C:/example/1cv8.exe"; //$NON-NLS-1$

    @Test
    public void theTwoVanessaFieldsGoBackToTheirShippedValues()
    {
        try (AGeneralTabOnAShell tab = AGeneralTabOnAShell.open(PrefKeys.PREF_VANESSA_EPF,
            CHOSEN_EPF, PrefKeys.PREF_VANESSA_1C_EXE, CHOSEN_EXE))
        {
            tab.restoreDefaults();
            assertNull("the restored values have to be savable, or the round trip is not the " //$NON-NLS-1$
                + "user's own", tab.save());

            IPreferenceStore store = tab.store();
            assertEquals("the Vanessa feature file goes back to the shipped value", //$NON-NLS-1$
                store.getDefaultString(PrefKeys.PREF_VANESSA_EPF),
                store.getString(PrefKeys.PREF_VANESSA_EPF));
            assertEquals("and so does the platform executable", //$NON-NLS-1$
                store.getDefaultString(PrefKeys.PREF_VANESSA_1C_EXE),
                store.getString(PrefKeys.PREF_VANESSA_1C_EXE));
        }
    }
}
