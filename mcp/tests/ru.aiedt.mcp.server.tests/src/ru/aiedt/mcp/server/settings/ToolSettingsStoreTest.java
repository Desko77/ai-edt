/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for the static parse/serialize helpers of {@link ToolSettingsStore}.
 * The instance methods route through {@link ru.aiedt.mcp.server.Activator#getDefault()} and need a
 * live preference store, so they are out of scope here; the wire format is what these tests pin.
 * The persisted form is a comma-separated, sorted list of tool names, and parse is its inverse.
 */
public class ToolSettingsStoreTest
{
    private static final String TOOL_ALPHA = "get_edt_version"; //$NON-NLS-1$
    private static final String TOOL_BETA = "list_projects"; //$NON-NLS-1$
    private static final String TOOL_GAMMA = "set_breakpoint"; //$NON-NLS-1$

    // ---- parseDisabledTools: empty inputs -------------------------------------------------

    @Test
    public void parseNullYieldsEmpty()
    {
        assertEmpty(ToolSettingsStore.parseDisabledTools(null));
    }

    @Test
    public void parseEmptyStringYieldsEmpty()
    {
        assertEmpty(ToolSettingsStore.parseDisabledTools("")); //$NON-NLS-1$
    }

    @Test
    public void parseAllWhitespaceYieldsEmpty()
    {
        assertEmpty(ToolSettingsStore.parseDisabledTools("   ")); //$NON-NLS-1$
        assertEmpty(ToolSettingsStore.parseDisabledTools("\t\n ")); //$NON-NLS-1$
    }

    // ---- parseDisabledTools: populated inputs ---------------------------------------------

    @Test
    public void parseSingleName()
    {
        Set<String> result = ToolSettingsStore.parseDisabledTools(TOOL_ALPHA);
        assertEquals(1, result.size());
        assertTrue(result.contains(TOOL_ALPHA));
    }

    @Test
    public void parseSeveralNames()
    {
        Set<String> result = ToolSettingsStore.parseDisabledTools(TOOL_ALPHA + "," + TOOL_BETA + "," + TOOL_GAMMA); //$NON-NLS-1$//$NON-NLS-2$
        assertEquals(3, result.size());
        assertTrue(result.contains(TOOL_ALPHA));
        assertTrue(result.contains(TOOL_BETA));
        assertTrue(result.contains(TOOL_GAMMA));
    }

    @Test
    public void parseTrimsWhitespaceAroundEachName()
    {
        Set<String> result = ToolSettingsStore.parseDisabledTools(" " + TOOL_ALPHA + " , " + TOOL_BETA + " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(2, result.size());
        assertTrue(result.contains(TOOL_ALPHA));
        assertTrue(result.contains(TOOL_BETA));
    }

    @Test
    public void parseDropsBlankEntriesFromLeadingTrailingAndDoubleCommas()
    {
        Set<String> result = ToolSettingsStore.parseDisabledTools(
            TOOL_ALPHA + ",," + TOOL_BETA + ","); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, result.size());
        assertTrue(result.contains(TOOL_ALPHA));
        assertTrue(result.contains(TOOL_BETA));
    }

    @Test
    public void parseResultIsUnmodifiable()
    {
        Set<String> result = ToolSettingsStore.parseDisabledTools(TOOL_ALPHA);
        try
        {
            result.add(TOOL_BETA);
            fail("expected the parsed set to reject mutation"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            // the returned set is a shared empty or an immutable snapshot
        }
    }

    // ---- serializeDisabledTools: empty inputs ---------------------------------------------

    @Test
    public void serializeNullYieldsEmptyString()
    {
        assertEquals("", ToolSettingsStore.serializeDisabledTools(null)); //$NON-NLS-1$
    }

    @Test
    public void serializeEmptySetYieldsEmptyString()
    {
        assertEquals("", ToolSettingsStore.serializeDisabledTools(Collections.emptySet())); //$NON-NLS-1$
    }

    // ---- serializeDisabledTools: populated inputs -----------------------------------------

    @Test
    public void serializeSingleName()
    {
        assertEquals(TOOL_ALPHA, ToolSettingsStore.serializeDisabledTools(Set.of(TOOL_ALPHA)));
    }

    @Test
    public void serializeSortsNamesAscendingBeforeJoining()
    {
        // The point of sorting is that saving the same choice twice produces the same line, so a
        // workspace .prefs file does not churn on every OK.
        Set<String> unsorted = new LinkedHashSet<>();
        unsorted.add(TOOL_GAMMA);
        unsorted.add(TOOL_ALPHA);
        unsorted.add(TOOL_BETA);
        assertEquals(TOOL_ALPHA + "," + TOOL_BETA + "," + TOOL_GAMMA, //$NON-NLS-1$ //$NON-NLS-2$
            ToolSettingsStore.serializeDisabledTools(unsorted));
    }

    @Test
    public void serializeIsIdempotent()
    {
        // serializing, parsing and serializing again must yield the same string.
        Set<String> original = Set.of(TOOL_ALPHA, TOOL_BETA, TOOL_GAMMA);
        String once = ToolSettingsStore.serializeDisabledTools(original);
        String twice = ToolSettingsStore.serializeDisabledTools(
            ToolSettingsStore.parseDisabledTools(once));
        assertEquals(once, twice);
    }

    // ---- round-trip -----------------------------------------------------------------------

    @Test
    public void emptySetRoundTripsThroughSerializeAndParse()
    {
        Set<String> original = Set.of();
        String wire = ToolSettingsStore.serializeDisabledTools(original);
        assertEquals(original, ToolSettingsStore.parseDisabledTools(wire));
    }

    @Test
    public void populatedSetRoundTripsThroughSerializeAndParse()
    {
        Set<String> original = Set.of(TOOL_ALPHA, TOOL_BETA, TOOL_GAMMA);
        String wire = ToolSettingsStore.serializeDisabledTools(original);
        assertEquals(original, ToolSettingsStore.parseDisabledTools(wire));
    }

    @Test
    public void everyPresetDisabledSetRoundTrips()
    {
        // The persisted form of a preset is exactly what serialize produces from its set, and
        // matchPreset reads what parse produces - so every preset's set must survive the wire.
        for (ToolProfile preset : ToolProfile.values())
        {
            Set<String> disabled = preset.getDisabledTools();
            if (disabled == null)
            {
                continue;
            }
            String wire = ToolSettingsStore.serializeDisabledTools(disabled);
            Set<String> parsed = ToolSettingsStore.parseDisabledTools(wire);
            assertEquals("round-trip broke for " + preset, disabled, parsed); //$NON-NLS-1$
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private static void assertEmpty(Set<String> result)
    {
        assertNotNull(result);
        assertTrue("expected an empty set, got " + result, result.isEmpty()); //$NON-NLS-1$
    }
}
