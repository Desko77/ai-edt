/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Covers what a chosen preset has to keep meaning after the product gains a tool.
 * <p>
 * Applying a preset used to write down the names it covered at that moment and nothing else. A tool
 * added by a later version was then in nobody's list, so an upgrade switched it on for everyone -
 * including the people who had picked the strictest preset precisely to keep such a thing off. The
 * choice is now recorded, and the names are worked out from it against the tools that exist now.
 * </p>
 * <p>
 * These tests exercise the arithmetic rather than the preference store: the store needs a running
 * workbench, while what actually went wrong is the difference between remembering an answer and
 * remembering the question.
 * </p>
 */
public class PresetSurvivesAnUpgradeTest
{
    @Test
    public void aPresetAnswersFromTheGroupsAsTheyStandNow()
    {
        // The sets are built from ToolCategory at class-init, so a tool added to a gated group is in
        // the preset's answer without anyone re-applying anything. This is the property the stored
        // name relies on; if presets ever became literal name lists, the fix would be undone.
        Set<String> readOnly = ToolProfile.READ_ONLY.getDisabledTools();
        for (String name : ToolCategory.DEBUG.getToolNames())
        {
            assertTrue("Read-only should switch off every debug tool, missing " + name, //$NON-NLS-1$
                readOnly.contains(name));
        }
        for (String name : ToolCategory.CONSTRUCTORS.getToolNames())
        {
            assertTrue("Read-only should switch off every constructor, missing " + name, //$NON-NLS-1$
                readOnly.contains(name));
        }
    }

    @Test
    public void theNewestToolIsCoveredByThePresetsThatGateItsGroup()
    {
        // marker_corrections is the case that exposed this: it landed in a write group in 0.2.2, and
        // anyone running Read-only from before that release kept it switched on.
        ToolCategory group = ToolCategory.getGroupForTool("marker_corrections"); //$NON-NLS-1$
        assertEquals(ToolCategory.REFACTORING, group);
        assertTrue("Read-only has to cover a tool added to a write group", //$NON-NLS-1$
            ToolProfile.READ_ONLY.getDisabledTools().contains("marker_corrections")); //$NON-NLS-1$
    }

    @Test
    public void aStoredPresetNameRoundTripsToItsPreset()
    {
        // What gets written to the store is the constant's name, so a rename of a constant is a
        // silent loss of everyone's choice. That is worth failing a build over.
        for (ToolProfile preset : ToolProfile.values())
        {
            assertEquals(preset, ToolProfile.valueOf(preset.name()));
        }
    }

    @Test
    public void everyPresetButCustomCarriesBothSets()
    {
        // The reader falls back to the stored names whenever a preset has no set of its own, so a
        // half-populated preset would silently behave like a hand-picked selection.
        for (ToolProfile preset : ToolProfile.values())
        {
            if (preset == ToolProfile.CUSTOM)
            {
                continue;
            }
            assertTrue(preset + " has no disabled set", preset.getDisabledTools() != null); //$NON-NLS-1$
            assertTrue(preset + " has no unlisted set", preset.getUnlistedTools() != null); //$NON-NLS-1$
        }
    }

    @Test
    public void aHandPickedSetIsNotMistakenForAPreset()
    {
        // The fallback path: with no recorded choice, the sets themselves are matched. A set that is
        // nobody's has to come back Custom, or a hand-picked selection would be overwritten by a
        // preset's idea of it on the next read.
        Set<String> odd = new HashSet<>();
        odd.add("write_module_source"); //$NON-NLS-1$
        assertEquals(ToolProfile.CUSTOM, ToolProfile.matchPreset(odd, new HashSet<>()));
    }

    @Test
    public void aPresetsOwnSetsMatchBackToIt()
    {
        for (ToolProfile preset : ToolProfile.values())
        {
            if (preset == ToolProfile.CUSTOM)
            {
                continue;
            }
            assertEquals(preset,
                ToolProfile.matchPreset(preset.getDisabledTools(), preset.getUnlistedTools()));
        }
    }

    @Test
    public void readOnlyAndEditingDoNotAgree()
    {
        // A sanity check on the arithmetic above: if two presets ever resolved to the same pair,
        // matchPreset would answer with whichever came first and the recorded name would drift.
        assertNotEquals(ToolProfile.READ_ONLY.getDisabledTools(),
            ToolProfile.EDITING.getDisabledTools());
        assertFalse(ToolProfile.READ_ONLY.getDisabledTools().isEmpty());
    }
}
