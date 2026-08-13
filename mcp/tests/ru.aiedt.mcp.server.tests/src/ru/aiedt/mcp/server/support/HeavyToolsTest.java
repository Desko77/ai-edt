/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Verifies the heavy-tool membership: the genuinely expensive tools are counted, the common fast reads
 * that were deliberately left out are not, and lookups tolerate null and unknown names.
 */
public class HeavyToolsTest
{
    @Test
    public void expensiveToolsAreHeavy()
    {
        assertTrue(HeavyTools.isHeavy("update_database")); //$NON-NLS-1$
        assertTrue(HeavyTools.isHeavy("export_configuration_to_xml")); //$NON-NLS-1$
        assertTrue(HeavyTools.isHeavy("find_references")); //$NON-NLS-1$
        assertTrue(HeavyTools.isHeavy("clean_project")); //$NON-NLS-1$
        assertTrue(HeavyTools.isHeavy("vanessa")); //$NON-NLS-1$
        assertTrue("the code_search facade delegates to heavy searches", //$NON-NLS-1$
            HeavyTools.isHeavy("code_search")); //$NON-NLS-1$
        assertTrue("the old standalone name reaches the same expensive work as the facade", //$NON-NLS-1$
            HeavyTools.isHeavy("get_content_assist")); //$NON-NLS-1$
    }

    @Test
    public void commonFastReadsAreNotHeavy()
    {
        // Deliberately excluded so ordinary use is not throttled.
        assertFalse(HeavyTools.isHeavy("get_metadata_objects")); //$NON-NLS-1$
        assertFalse(HeavyTools.isHeavy("list_modules")); //$NON-NLS-1$
        assertFalse(HeavyTools.isHeavy("read_module_source")); //$NON-NLS-1$
        assertFalse(HeavyTools.isHeavy("get_edt_version")); //$NON-NLS-1$
        assertFalse(HeavyTools.isHeavy("validate_query")); //$NON-NLS-1$
        // A common mutator whose slow path (batch) has its own async handling; not throttled here.
        assertFalse(HeavyTools.isHeavy("edit_metadata")); //$NON-NLS-1$
    }

    @Test
    public void nullAndUnknownAreNotHeavy()
    {
        assertFalse(HeavyTools.isHeavy(null));
        assertFalse(HeavyTools.isHeavy("unknown")); //$NON-NLS-1$
        assertFalse(HeavyTools.isHeavy("")); //$NON-NLS-1$
    }

    @Test
    public void setIsNonTrivial()
    {
        assertTrue("heavy set should be a substantial list", HeavyTools.count() > 20); //$NON-NLS-1$
    }
}
