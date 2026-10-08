/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * What the tree says about a tool whose place in the catalogue has just changed.
 * <p>
 * A tool hidden from <code>tools/list</code> but still callable carries a badge, and the badge is
 * drawn from the unlisted set. Choosing another preset moves names in and out of that set, and a row
 * left wearing the mark of the preset before it tells the user the opposite of what the server now
 * answers.
 * </p>
 */
public class APresetChangeRepaintsTheHiddenMarksTest
{
    @Test
    public void theMarkFollowsThePresetThatWasChosen()
    {
        String marked = AToolsTabOnAShell.firstNameTheShippedPresetHides();

        try (AToolsTabOnAShell tab = AToolsTabOnAShell.open(PrefKeys.PREF_TOOL_PRESET,
            ToolProfile.ALL_TOOLS.name()))
        {
            // Typed in rather than browsed to: a collapsed group has no rows under it, and the row is
            // what carries the mark.
            tab.searchBox().setText(marked);
            assertNull("nothing is hidden under the preset the tab was opened on", //$NON-NLS-1$
                tab.itemFor(marked).getImage());

            tab.choosePreset(ToolProfile.CANONICAL);
            assertNotNull(marked + " is hidden from tools/list now, so its row carries the mark", //$NON-NLS-1$
                tab.itemFor(marked).getImage());

            tab.choosePreset(ToolProfile.ALL_TOOLS);
            assertNull("and it loses it again when the catalogue lists everything", //$NON-NLS-1$
                tab.itemFor(marked).getImage());
        }
    }
}
