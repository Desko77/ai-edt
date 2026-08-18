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
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Tests for the {@link ToolCategory} enumeration - the catalogue every preset and the preference tree
 * are built from. A group is the only handle anything has on a tool, so the invariants these tests
 * enforce (every group populated, no tool claimed twice, the reverse index complete) are what makes
 * it possible to switch a tool off at all.
 */
public class ToolCategoryTest
{
    // ---- cardinality and per-group sanity -------------------------------------------------

    @Test
    public void thereAreThirteenGroups()
    {
        // 1.43 grouping: core/problems/code-intel/tags/applications/debug/bsl-code/refactoring/
        // constructors/forms-and-help/analysis/security/ai-helpers.
        assertEquals(13, ToolCategory.values().length);
    }

    @Test
    public void everyGroupIsPopulated()
    {
        for (ToolCategory group : ToolCategory.values())
        {
            assertFalse("group " + group + " advertises no tools", //$NON-NLS-1$ //$NON-NLS-2$
                group.getToolNames().isEmpty());
        }
    }

    @Test
    public void everyGroupHasNonBlankDisplayName()
    {
        for (ToolCategory group : ToolCategory.values())
        {
            assertNotNull(group.getDisplayName());
            assertFalse(group.getId(), group.getDisplayName().isEmpty());
        }
    }

    @Test
    public void everyGroupHasNonBlankDescription()
    {
        for (ToolCategory group : ToolCategory.values())
        {
            assertNotNull(group.getDescription());
            assertFalse(group.getId(), group.getDescription().isEmpty());
        }
    }

    @Test
    public void groupIdsAreUnique()
    {
        Set<String> seen = new HashSet<>();
        for (ToolCategory group : ToolCategory.values())
        {
            assertTrue("duplicate id " + group.getId(), seen.add(group.getId())); //$NON-NLS-1$
        }
    }

    // ---- the reverse index ----------------------------------------------------------------

    @Test
    public void noToolAppearsInTwoGroups()
    {
        // The static index is a Map, so a name listed twice would silently keep the later one;
        // this sweep is the only thing that catches it.
        Set<String> claimed = new HashSet<>();
        for (ToolCategory group : ToolCategory.values())
        {
            for (String name : group.getToolNames())
            {
                assertTrue("tool " + name + " is declared by more than one group", //$NON-NLS-1$ //$NON-NLS-2$
                    claimed.add(name));
            }
        }
    }

    @Test
    public void totalToolCountEqualsSumOfGroupSizes()
    {
        int summed = 0;
        for (ToolCategory group : ToolCategory.values())
        {
            summed += group.getToolNames().size();
        }
        assertEquals(summed, ToolCategory.getTotalToolCount());
        assertTrue("there must be at least one tool", summed > 0); //$NON-NLS-1$
    }

    @Test
    public void totalToolCountIsOneHundredTwentySix()
    {
        // Recounted directly from the group table. F3d facades landed on 118; the 2026-07-28
        // data_access removal dropped execute_query, browse_data and data_access (-3 -> 115) and
        // find_dead_code joined ANALYSIS (+1 -> 116). On 2026-07-30 the L64 reconciliation found
        // create_project and get_outgoing_structures still ungrouped (so the Read-only preset could
        // not switch off the mutating create_project); both were added (+2 -> 118), closing the
        // declared-vs-grouped gap. On 2026-08-03 self_upkeep joined APPLICATIONS (+1 -> 119).
        // On 2026-08-05 start_client and unpack_external_binary joined APPLICATIONS (+2 -> 121).
        // On 2026-08-09 marker_corrections joined REFACTORING (+1 -> 122): it reads like a
        // diagnostic but writes to the sources, so it sits where Read-only can switch it off.
        // Both reach a live infobase - one launches a client against it, the other runs the
        // Designer on it - so the group matters: APPLICATIONS is what Read-only switches off.
        // On 2026-08-10 copy_object joined REFACTORING (+1 -> 123): it creates a whole object
        // in the target project, which is as plainly a write as a rename.
        // On 2026-08-14 describe_db_tables joined CODE_INTELLIGENCE (+1 -> 124): it reads the
        // tables the platform derives from an object and writes nothing.
        // On 2026-08-15 import_configuration_from_binary joined APPLICATIONS (+1 -> 125): it
        // creates an infobase and a project, so Read-only has to be able to switch it off.
        // On 2026-08-19 read_event_log joined APPLICATIONS (+1 -> 126): it reads a file beside
        // the infobase and writes nothing, but it belongs with the infobase group because that
        // is what a preset limiting access to a live base is expected to cover.
        // A drift here means a tool was added or removed without the group
        // table being told, which is exactly what the coverage test elsewhere is built to catch.
        assertEquals(126, ToolCategory.getTotalToolCount());
    }

    @Test
    public void reverseLookupResolvesEveryDeclaredTool()
    {
        for (ToolCategory group : ToolCategory.values())
        {
            for (String name : group.getToolNames())
            {
                assertEquals("tool " + name + " did not resolve back to its group", //$NON-NLS-1$ //$NON-NLS-2$
                    group, ToolCategory.getGroupForTool(name));
            }
        }
    }

    @Test
    public void reverseLookupReturnsNullForUnknownName()
    {
        assertNull(ToolCategory.getGroupForTool("no_such_tool_ever")); //$NON-NLS-1$
    }

    @Test
    public void reverseLookupReturnsNullForNullInput()
    {
        assertNull(ToolCategory.getGroupForTool(null));
    }

    @Test
    public void knownAnchorToolsResolveToTheirExpectedGroups()
    {
        assertEquals(ToolCategory.CORE, ToolCategory.getGroupForTool("get_edt_version")); //$NON-NLS-1$
        assertEquals(ToolCategory.PROBLEMS, ToolCategory.getGroupForTool("get_project_errors")); //$NON-NLS-1$
        assertEquals(ToolCategory.APPLICATIONS, ToolCategory.getGroupForTool("list_configurations")); //$NON-NLS-1$
        assertEquals(ToolCategory.DEBUG, ToolCategory.getGroupForTool("set_breakpoint")); //$NON-NLS-1$
        assertEquals(ToolCategory.BSL_CODE, ToolCategory.getGroupForTool("read_module_source")); //$NON-NLS-1$
        assertEquals(ToolCategory.REFACTORING, ToolCategory.getGroupForTool("rename_metadata_object")); //$NON-NLS-1$
        assertEquals(ToolCategory.PROBLEMS, ToolCategory.getGroupForTool("diagnostics")); //$NON-NLS-1$
        assertEquals(ToolCategory.CORE, ToolCategory.getGroupForTool("project_admin")); //$NON-NLS-1$
        assertEquals(ToolCategory.APPLICATIONS, ToolCategory.getGroupForTool("infobase_admin")); //$NON-NLS-1$
        assertEquals(ToolCategory.FORMS_AND_HELP, ToolCategory.getGroupForTool("config_io")); //$NON-NLS-1$
        assertEquals(ToolCategory.ANALYSIS, ToolCategory.getGroupForTool("insights")); //$NON-NLS-1$
        assertEquals(ToolCategory.SECURITY, ToolCategory.getGroupForTool("security_audit")); //$NON-NLS-1$
        assertEquals(ToolCategory.TAGS, ToolCategory.getGroupForTool("workspace_marks")); //$NON-NLS-1$
        assertEquals(ToolCategory.CODE_INTELLIGENCE, ToolCategory.getGroupForTool("docs_lookup")); //$NON-NLS-1$
    }

    // ---- immutability ---------------------------------------------------------------------

    @Test
    public void toolNamesListCannotBeMutated()
    {
        List<String> names = ToolCategory.CORE.getToolNames();
        try
        {
            names.add("injected"); //$NON-NLS-1$
            fail("expected the tool-names list to reject mutation"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            // the catalogue is shared; it must be unmodifiable
        }
    }

    // ---- spot checks of individual groups -------------------------------------------------

    @Test
    public void coreHoldsTheSevenProjectAndConfigTools()
    {
        List<String> core = ToolCategory.CORE.getToolNames();
        assertEquals(7, core.size());
        assertTrue(core.contains("get_edt_version")); //$NON-NLS-1$
        assertTrue(core.contains("list_projects")); //$NON-NLS-1$
        assertTrue(core.contains("get_configuration_properties")); //$NON-NLS-1$
        assertTrue(core.contains("clean_project")); //$NON-NLS-1$
        assertTrue(core.contains("revalidate_objects")); //$NON-NLS-1$
        assertTrue(core.contains("get_check_description")); //$NON-NLS-1$
        assertTrue(core.contains("project_admin")); //$NON-NLS-1$
    }

    @Test
    public void debugHoldsTheSeventeenSessionTools()
    {
        List<String> debug = ToolCategory.DEBUG.getToolNames();
        assertEquals(17, debug.size());
        assertTrue(debug.contains("set_breakpoint")); //$NON-NLS-1$
        assertTrue(debug.contains("get_variables")); //$NON-NLS-1$
        assertTrue(debug.contains("resume")); //$NON-NLS-1$
        assertTrue(debug.contains("set_variable")); //$NON-NLS-1$
        assertTrue(debug.contains("terminate_launch")); //$NON-NLS-1$
    }

    @Test
    public void refactoringHoldsTheToolsThatEditMetadata()
    {
        List<String> refactoring = ToolCategory.REFACTORING.getToolNames();
        assertEquals(5, refactoring.size());
        assertTrue(refactoring.contains("rename_metadata_object")); //$NON-NLS-1$
        assertTrue(refactoring.contains("delete_metadata_object")); //$NON-NLS-1$
        assertTrue(refactoring.contains("add_metadata_attribute")); //$NON-NLS-1$
        // Applying EDT's own correction changes the sources like any other edit here.
        assertTrue(refactoring.contains("marker_corrections")); //$NON-NLS-1$
        // Copying creates a whole object in the target project - the largest write in this group.
        assertTrue(refactoring.contains("copy_object")); //$NON-NLS-1$
    }
}
