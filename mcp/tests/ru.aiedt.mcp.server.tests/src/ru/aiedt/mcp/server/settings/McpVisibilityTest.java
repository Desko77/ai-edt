/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for the third tool-visibility state - "callable-but-unlisted" - and the {@link
 * ToolProfile#CANONICAL} preset that uses it.
 * <p>
 * The pure logic that needs no workbench lives in {@link ToolProfile}: what CANONICAL hides, and how
 * {@link ToolProfile#matchPreset} tells it apart from {@link ToolProfile#ALL_TOOLS} - which it must,
 * because both disable nothing and only the unlisted set separates them. That is what these tests
 * pin.
 * </p>
 * <p>
 * The catalogue-level union - that {@code McpToolCatalog.getEnabledTools} drops the disabled set
 * <em>and</em> the unlisted set from {@code tools/list}, while {@code isToolEnabled} (the call gate)
 * subtracts the disabled set alone so an unlisted tool stays callable and a disabled one never does -
 * reads the live preference store through {@code Activator.getDefault()}, which is null headless.
 * That branch is therefore covered by code inspection, not exercised here.
 * </p>
 */
public class McpVisibilityTest
{
    // ---- what CANONICAL is -----------------------------------------------------------------

    @Test
    public void canonicalDisablesNothing()
    {
        Set<String> disabled = ToolProfile.CANONICAL.getDisabledTools();
        assertNotNull(disabled);
        assertTrue("CANONICAL must disable no tool - it only hides them from the list", //$NON-NLS-1$
            disabled.isEmpty());
    }

    @Test
    public void canonicalUnlistsTheFacadeCoveredStandalones()
    {
        Set<String> unlisted = ToolProfile.CANONICAL.getUnlistedTools();
        assertNotNull(unlisted);
        assertFalse("CANONICAL must hide something, or it is just ALL_TOOLS", unlisted.isEmpty()); //$NON-NLS-1$

        // The exact membership: 7 from code_search, 16 from launch_debugger, 2 from yaxunit_tests,
        // 7 from extension_workshop, 6 from diagnostics, 9 from project_admin, 7 from
        // infobase_admin, 4 from config_io, 8 from insights, 3 from security_audit, 4 from
        // workspace_marks, 2 from docs_lookup, 3 from edit_metadata. Pinning the
        // size catches an accidental add or drop.
        assertEquals(78, unlisted.size());

        // A standalone from each facade is hidden.
        assertTrue(unlisted.contains("search_in_code")); //$NON-NLS-1$ // code_search
        assertTrue(unlisted.contains("get_outgoing_structures")); //$NON-NLS-1$ // code_search (ungrouped)
        assertTrue(unlisted.contains("set_breakpoint")); //$NON-NLS-1$ // launch_debugger
        assertTrue(unlisted.contains("terminate_launch")); //$NON-NLS-1$ // launch_debugger
        assertTrue(unlisted.contains("run_yaxunit_tests")); //$NON-NLS-1$ // yaxunit_tests
        assertTrue(unlisted.contains("install_extension")); //$NON-NLS-1$ // extension_workshop
        assertTrue(unlisted.contains("get_project_errors")); //$NON-NLS-1$ // diagnostics
        assertTrue(unlisted.contains("get_check_description")); //$NON-NLS-1$ // diagnostics
        assertTrue(unlisted.contains("list_projects")); //$NON-NLS-1$ // project_admin
        assertTrue(unlisted.contains("restart_edt")); //$NON-NLS-1$ // project_admin
        assertTrue(unlisted.contains("get_applications")); //$NON-NLS-1$ // infobase_admin
        assertTrue(unlisted.contains("update_database")); //$NON-NLS-1$ // infobase_admin
        assertTrue(unlisted.contains("export_configuration_to_xml")); //$NON-NLS-1$ // config_io
        assertTrue(unlisted.contains("export_object")); //$NON-NLS-1$ // config_io
        assertTrue(unlisted.contains("project_metrics")); //$NON-NLS-1$ // insights
        assertTrue(unlisted.contains("impact_analysis")); //$NON-NLS-1$ // insights
        assertTrue(unlisted.contains("audit_role_rights")); //$NON-NLS-1$ // security_audit
        assertTrue(unlisted.contains("sensitive_data_scan")); //$NON-NLS-1$ // security_audit
        assertTrue(unlisted.contains("get_tags")); //$NON-NLS-1$ // workspace_marks
        assertTrue(unlisted.contains("get_bookmarks")); //$NON-NLS-1$ // workspace_marks
        assertTrue(unlisted.contains("get_platform_documentation")); //$NON-NLS-1$ // docs_lookup
        assertTrue(unlisted.contains("get_object_help")); //$NON-NLS-1$ // docs_lookup
        assertTrue(unlisted.contains("delete_metadata_object")); //$NON-NLS-1$ // edit_metadata
        assertTrue(unlisted.contains("rename_metadata_object")); //$NON-NLS-1$ // edit_metadata
        assertTrue(unlisted.contains("add_metadata_attribute")); //$NON-NLS-1$ // edit_metadata

        // The facades themselves stay listed - hiding them would defeat the point.
        assertFalse(unlisted.contains("code_search")); //$NON-NLS-1$
        assertFalse(unlisted.contains("launch_debugger")); //$NON-NLS-1$
        assertFalse(unlisted.contains("yaxunit_tests")); //$NON-NLS-1$
        assertFalse(unlisted.contains("extension_workshop")); //$NON-NLS-1$
        assertFalse(unlisted.contains("diagnostics")); //$NON-NLS-1$
        assertFalse(unlisted.contains("project_admin")); //$NON-NLS-1$
        assertFalse(unlisted.contains("infobase_admin")); //$NON-NLS-1$
        assertFalse(unlisted.contains("config_io")); //$NON-NLS-1$
        assertFalse(unlisted.contains("insights")); //$NON-NLS-1$
        assertFalse(unlisted.contains("security_audit")); //$NON-NLS-1$
        assertFalse(unlisted.contains("workspace_marks")); //$NON-NLS-1$
        assertFalse(unlisted.contains("docs_lookup")); //$NON-NLS-1$

        // edit_metadata is itself a facade - for delete_metadata_object / rename_metadata_object /
        // add_metadata_attribute, asserted above - so it stays listed like every other facade name,
        // never hidden behind itself.
        assertFalse(unlisted.contains("edit_metadata")); //$NON-NLS-1$
    }

    @Test
    public void canonicalUnlistedSetIsUnmodifiable()
    {
        Set<String> unlisted = ToolProfile.CANONICAL.getUnlistedTools();
        try
        {
            unlisted.add("injected"); //$NON-NLS-1$
            fail("the unlisted set of a shared preset constant must be immutable"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            // presets are shared constants; their sets must reject mutation
        }
    }

    @Test
    public void canonicalDoesNotBothDisableAndUnlistTheSameTool()
    {
        // The call gate consults the disabled set only, so a name in both sets would be a hidden tool
        // that is also rejected - unlisting it would buy nothing. CANONICAL keeps the two disjoint (its
        // disabled set is simply empty), so every name it hides is still callable.
        Set<String> both = new HashSet<>(ToolProfile.CANONICAL.getDisabledTools());
        both.retainAll(ToolProfile.CANONICAL.getUnlistedTools());
        assertTrue("CANONICAL must not both disable and unlist a tool: " + both, both.isEmpty()); //$NON-NLS-1$
    }

    // ---- matchPreset keys on the pair ------------------------------------------------------

    @Test
    public void matchPresetTellsCanonicalFromAllToolsByTheUnlistedSetAlone()
    {
        // Both presets disable nothing. If matchPreset keyed on the disabled set alone it could never
        // return CANONICAL; the pair (disabled, unlisted) is what separates them.
        assertNotEquals(ToolProfile.ALL_TOOLS, ToolProfile.CANONICAL);

        assertEquals(ToolProfile.ALL_TOOLS, ToolProfile.matchPreset(Set.of(), Set.of()));
        assertEquals(ToolProfile.CANONICAL,
            ToolProfile.matchPreset(Set.of(), ToolProfile.CANONICAL.getUnlistedTools()));
    }

    @Test
    public void matchPresetRoundTripsCanonicalDespiteAnUngroupedName()
    {
        // CANONICAL's unlisted set carries get_outgoing_structures, which belongs to no ToolCategory
        // group. matchPreset drops ungrouped names from both the stored preset set and the compared
        // input, so the ungrouped name falls out of both sides and the round-trip still lands on
        // CANONICAL rather than CUSTOM.
        Set<String> disabled = new HashSet<>(ToolProfile.CANONICAL.getDisabledTools());
        Set<String> unlisted = new HashSet<>(ToolProfile.CANONICAL.getUnlistedTools());
        assertEquals(ToolProfile.CANONICAL, ToolProfile.matchPreset(disabled, unlisted));
    }

    @Test
    public void unlistingSomethingWithoutDisablingItIsCanonicalNotCustom()
    {
        // A configuration that hides the CANONICAL standalones but disables nothing is exactly the
        // CANONICAL preset - confirming the two sets are independent inputs and an empty disabled set
        // does not force ALL_TOOLS once the unlisted set is non-empty.
        assertEquals(ToolProfile.CANONICAL,
            ToolProfile.matchPreset(new HashSet<>(), ToolProfile.CANONICAL.getUnlistedTools()));
    }
}
