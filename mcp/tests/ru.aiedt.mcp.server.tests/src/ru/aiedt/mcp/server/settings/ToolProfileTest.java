/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for {@link ToolProfile}. A preset <em>is</em> its pair of sets - what it disables and what it
 * hides from the tool list: only the pair is persisted, and {@link ToolProfile#matchPreset} works out
 * which preset to show by comparing pairs. Two presets with the same pair can never be told apart, so
 * these tests insist that each non-CUSTOM preset has a distinct pair and that matchPreset round-trips
 * it back.
 */
public class ToolProfileTest
{
    // ---- catalogue shape ------------------------------------------------------------------

    @Test
    public void sevenPresetsAreShipped()
    {
        // ALL_TOOLS, CANONICAL, READ_ONLY, EDITING, DEBUG_AND_TEST, CODE_REVIEW, CUSTOM.
        // ANALYSIS_ONLY and DEVELOPMENT were dropped because their sets duplicated READ_ONLY and
        // EDITING, so matchPreset could never return them - keeping this count exact prevents a
        // silent reintroduction of the same collision. CANONICAL is the compact surface: it disables
        // nothing but unlists the facade-covered standalone tools.
        assertEquals(7, ToolProfile.values().length);
    }

    @Test
    public void everyPresetHasNonBlankDisplayName()
    {
        for (ToolProfile preset : ToolProfile.values())
        {
            assertNotNull(preset.getDisplayName());
            assertFalse(preset.name(), preset.getDisplayName().isEmpty());
        }
    }

    @Test
    public void everyPresetHasNonBlankDescription()
    {
        for (ToolProfile preset : ToolProfile.values())
        {
            assertNotNull(preset.getDescription());
            assertFalse(preset.name(), preset.getDescription().isEmpty());
        }
    }

    // ---- ALL_TOOLS ------------------------------------------------------------------------

    @Test
    public void allToolsDisablesNothing()
    {
        Set<String> disabled = ToolProfile.ALL_TOOLS.getDisabledTools();
        assertNotNull(disabled);
        assertTrue("ALL_TOOLS must disable no tools", disabled.isEmpty()); //$NON-NLS-1$
    }

    // ---- CUSTOM ---------------------------------------------------------------------------

    @Test
    public void customHasNoOpinionOfItsOwn()
    {
        // CUSTOM is the fallback when the stored set matches nobody; it has no set to apply.
        assertNull(ToolProfile.CUSTOM.getDisabledTools());
    }

    @Test
    public void mutatingFacadesAreGatedByEveryWriteForbiddingPreset()
    {
        // A facade that can route to a write (project_admin, infobase_admin, config_io) must be
        // disabled wherever its writes would be, or an agent bypasses the preset through the facade.
        for (String facade : new String[] {"project_admin", "infobase_admin", "config_io"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue(facade + " must be off under READ_ONLY", //$NON-NLS-1$
                ToolProfile.READ_ONLY.getDisabledTools().contains(facade));
            assertTrue(facade + " must be off under CODE_REVIEW", //$NON-NLS-1$
                ToolProfile.CODE_REVIEW.getDisabledTools().contains(facade));
            assertTrue(facade + " must be off under DEBUG_AND_TEST", //$NON-NLS-1$
                ToolProfile.DEBUG_AND_TEST.getDisabledTools().contains(facade));
        }
        // diagnostics only validates, so it is NOT gated; and the mutating facades stay available
        // under EDITING, where writes are allowed.
        assertFalse(ToolProfile.READ_ONLY.getDisabledTools().contains("diagnostics")); //$NON-NLS-1$
        assertFalse(ToolProfile.EDITING.getDisabledTools().contains("project_admin")); //$NON-NLS-1$
    }

    // ---- READ_ONLY ------------------------------------------------------------------------

    @Test
    public void readOnlySwitchesOffEverythingThatWritesLaunchesOrDebugs()
    {
        Set<String> disabled = ToolProfile.READ_ONLY.getDisabledTools();
        assertNotNull(disabled);

        // tools that launch, debug, write, or refactor
        assertTrue(disabled.contains("debug_launch")); //$NON-NLS-1$
        assertTrue(disabled.contains("set_breakpoint")); //$NON-NLS-1$
        assertTrue(disabled.contains("write_module_source")); //$NON-NLS-1$
        assertTrue(disabled.contains("rename_metadata_object")); //$NON-NLS-1$
        assertTrue(disabled.contains("update_database")); //$NON-NLS-1$
        assertTrue(disabled.contains("generate_event_handlers")); //$NON-NLS-1$

        // pure-read tools stay on
        assertFalse(disabled.contains("get_edt_version")); //$NON-NLS-1$
        assertFalse(disabled.contains("get_project_errors")); //$NON-NLS-1$
        assertFalse(disabled.contains("get_metadata_objects")); //$NON-NLS-1$
        assertFalse(disabled.contains("get_tags")); //$NON-NLS-1$
    }

    // ---- EDITING --------------------------------------------------------------------------

    @Test
    public void editingSwitchesOffOnlyTheDebugGroup()
    {
        Set<String> disabled = ToolProfile.EDITING.getDisabledTools();
        assertNotNull(disabled);

        // exactly the DEBUG group
        assertEquals(ToolCategory.DEBUG.getToolNames().size(), disabled.size());
        for (String name : ToolCategory.DEBUG.getToolNames())
        {
            assertTrue("editing should disable " + name, disabled.contains(name)); //$NON-NLS-1$
        }

        // writing and refactoring stay on
        assertFalse(disabled.contains("write_module_source")); //$NON-NLS-1$
        assertFalse(disabled.contains("rename_metadata_object")); //$NON-NLS-1$
    }

    // ---- CODE_REVIEW ----------------------------------------------------------------------

    @Test
    public void codeReviewKeepsReadsAndConstructorsButDropsWriteRefactorDebugLaunch()
    {
        Set<String> disabled = ToolProfile.CODE_REVIEW.getDisabledTools();
        assertNotNull(disabled);

        // write, refactor, debug, launch all off
        assertTrue(disabled.contains("write_module_source")); //$NON-NLS-1$
        assertTrue(disabled.contains("rename_metadata_object")); //$NON-NLS-1$
        assertTrue(disabled.contains("set_breakpoint")); //$NON-NLS-1$
        assertTrue(disabled.contains("debug_launch")); //$NON-NLS-1$

        // reads and the constructors stay on - reading what a constructor would produce is part of
        // a review
        assertFalse(disabled.contains("read_module_source")); //$NON-NLS-1$
        assertFalse(disabled.contains("search_in_code")); //$NON-NLS-1$
        assertFalse(disabled.contains("edit_metadata")); //$NON-NLS-1$
        assertFalse(disabled.contains("dcs_workshop")); //$NON-NLS-1$
    }

    // ---- DEBUG_AND_TEST -------------------------------------------------------------------

    @Test
    public void debugAndTestKeepsApplicationsButNamesTheDestructiveOnesIndividually()
    {
        Set<String> disabled = ToolProfile.DEBUG_AND_TEST.getDisabledTools();
        assertNotNull(disabled);

        // applications group is NOT disabled wholesale - it holds the launch tools - but the
        // destructive members are named one by one
        assertTrue(disabled.contains("update_database")); //$NON-NLS-1$
        assertTrue(disabled.contains("delete_infobase")); //$NON-NLS-1$
        assertTrue(disabled.contains("delete_project")); //$NON-NLS-1$
        assertTrue(disabled.contains("install_extension")); //$NON-NLS-1$
        assertTrue(disabled.contains("set_infobase_credentials")); //$NON-NLS-1$
        assertTrue(disabled.contains("restart_edt")); //$NON-NLS-1$

        // write/refactor/constructors off, debug + yaxunit on
        assertTrue(disabled.contains("write_module_source")); //$NON-NLS-1$
        assertTrue(disabled.contains("rename_metadata_object")); //$NON-NLS-1$
        assertTrue(disabled.contains("edit_metadata")); //$NON-NLS-1$
        assertFalse(disabled.contains("debug_launch")); //$NON-NLS-1$
        assertFalse(disabled.contains("run_yaxunit_tests")); //$NON-NLS-1$
        assertFalse(disabled.contains("set_breakpoint")); //$NON-NLS-1$
    }

    // ---- matchPreset ----------------------------------------------------------------------

    @Test
    public void matchPresetRecognisesAnEmptySetAsAllTools()
    {
        assertEquals(ToolProfile.ALL_TOOLS, ToolProfile.matchPreset(new HashSet<>(), new HashSet<>()));
        assertEquals(ToolProfile.ALL_TOOLS, ToolProfile.matchPreset(Set.of(), Set.of()));
    }

    @Test
    public void matchPresetRoundTripsEveryNonCustomPreset()
    {
        for (ToolProfile preset : ToolProfile.values())
        {
            if (preset == ToolProfile.CUSTOM)
            {
                continue;
            }
            Set<String> disabled = new HashSet<>(preset.getDisabledTools());
            Set<String> unlisted = new HashSet<>(preset.getUnlistedTools());
            assertEquals("matchPreset did not recognise its own " + preset, //$NON-NLS-1$
                preset, ToolProfile.matchPreset(disabled, unlisted));
        }
    }

    @Test
    public void matchPresetFallsBackToCustomForAnUnknownSet()
    {
        // A known tool switched off on its own matches no preset.
        assertEquals(ToolProfile.CUSTOM,
            ToolProfile.matchPreset(Set.of("get_edt_version", "list_projects"), Set.of())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void matchPresetDropsStaleNamesBeforeMatching()
    {
        // A workspace from an older build may name tools that have since been renamed away. The
        // unknown names are stripped first, so a set that is otherwise ALL_TOOLS still resolves.
        Set<String> withStale = new HashSet<>();
        withStale.add("obsolete_tool_renamed_in_1_42"); //$NON-NLS-1$
        assertEquals(ToolProfile.ALL_TOOLS, ToolProfile.matchPreset(withStale, Set.of()));

        // ... and stale noise on top of a real preset must not break the match either.
        Set<String> editingPlusStale = new HashSet<>(ToolProfile.EDITING.getDisabledTools());
        editingPlusStale.add("another_renamed_away_tool"); //$NON-NLS-1$
        assertEquals(ToolProfile.EDITING, ToolProfile.matchPreset(editingPlusStale, Set.of()));
    }

    // ---- the no-duplicate-set rule --------------------------------------------------------

    @Test
    public void noTwoNonCustomPresetsShareTheirDisabledAndUnlistedPair()
    {
        // A preset is persisted as its pair of sets and nothing else, so two presets with the same
        // pair are one preset wearing two labels - matchPreset can only ever return the first. The
        // disabled set alone is not the key: CANONICAL and ALL_TOOLS both disable nothing and are told
        // apart only by the unlisted set. Keep the pairs distinct or the combo silently relabels to
        // whichever was declared earlier.
        for (ToolProfile a : ToolProfile.values())
        {
            if (a == ToolProfile.CUSTOM)
            {
                continue;
            }
            for (ToolProfile b : ToolProfile.values())
            {
                if (b == ToolProfile.CUSTOM || a == b)
                {
                    continue;
                }
                assertFalse(a + " and " + b + " share both their disabled and unlisted sets", //$NON-NLS-1$ //$NON-NLS-2$
                    a.getDisabledTools().equals(b.getDisabledTools())
                        && a.getUnlistedTools().equals(b.getUnlistedTools()));
            }
        }
    }

    // ---- validity of the sets -------------------------------------------------------------

    @Test
    public void everyDisabledNameInEveryPresetBelongsToAGroup()
    {
        // A name no group claims can never be switched off anyway, so listing it in a preset is a
        // dead entry. Catch drift here.
        for (ToolProfile preset : ToolProfile.values())
        {
            Set<String> disabled = preset.getDisabledTools();
            if (disabled == null)
            {
                continue;
            }
            for (String name : disabled)
            {
                assertNotNull("preset " + preset + " references ungrouped tool " + name, //$NON-NLS-1$ //$NON-NLS-2$
                    ToolCategory.getGroupForTool(name));
            }
        }
    }

    @Test
    public void disabledToolSetsAreUnmodifiable()
    {
        for (ToolProfile preset : ToolProfile.values())
        {
            Set<String> disabled = preset.getDisabledTools();
            if (disabled == null)
            {
                continue;
            }
            try
            {
                disabled.add("injected"); //$NON-NLS-1$
                fail("disabled set of " + preset + " accepted mutation"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (UnsupportedOperationException expected)
            {
                // presets are shared constants; their sets must be immutable
            }
        }
    }
}
