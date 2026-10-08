/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * What Restore Defaults does to the selection of tools.
 * <p>
 * A workspace that has never been opened on the page advertises the Canonical preset, and the page
 * has to be able to bring that back. Handing back every tool instead is a wider surface than the
 * plugin ships with, and the only place to notice is a client, where the catalogue has grown by the
 * legacy names the preset exists to hide.
 * </p>
 * <p>
 * The check is the round trip the user makes: restore, save, and read what the server would serve.
 * </p>
 */
public class RestoringToolDefaultsGivesTheShippedPresetTest
{
    @Test
    public void theToolsGoBackToThePresetThePluginShipsWith()
    {
        try (AToolsTabOnAShell tab = AToolsTabOnAShell.open(PrefKeys.PREF_TOOL_PRESET,
            ToolProfile.ALL_TOOLS.name()))
        {
            tab.restoreDefaults();
            tab.save();

            assertEquals("the tools hidden from the catalogue go back to the shipped set", //$NON-NLS-1$
                ToolProfile.CANONICAL.getUnlistedTools(),
                ToolSettingsStore.getInstance().getUnlistedTools());
            assertEquals("and the combo names the preset they amount to", //$NON-NLS-1$
                ToolProfile.CANONICAL.ordinal(), tab.presetCombo().getSelectionIndex());
        }
    }
}
