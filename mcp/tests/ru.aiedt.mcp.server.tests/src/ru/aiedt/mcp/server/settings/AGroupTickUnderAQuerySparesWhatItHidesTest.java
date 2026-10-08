/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.eclipse.swt.widgets.TreeItem;
import org.junit.Test;

/**
 * How far a tick on a group header reaches while a query is narrowing the tree.
 * <p>
 * The header is the group's own row, and the list under it is what the query left. Unticking it is
 * read by the user as "not these", and a group's worth of tools disappearing behind the query is
 * both wider than the row they clicked and invisible on the page that just did it.
 * </p>
 */
public class AGroupTickUnderAQuerySparesWhatItHidesTest
{
    /** A fragment that leaves part of one group in the tree and hides the rest of it. */
    private static final String QUERY = "delete"; //$NON-NLS-1$

    @Test
    public void untickingAGroupHeaderLeavesTheToolsTheQueryHidAlone()
    {
        ToolCategory group = AToolsTabOnAShell.groupPartlyMatchedBy(QUERY);
        String shown = AToolsTabOnAShell.nameContaining(group, QUERY, true);
        String hidden = AToolsTabOnAShell.nameContaining(group, QUERY, false);

        try (AToolsTabOnAShell tab = AToolsTabOnAShell.open(PrefKeys.PREF_TOOL_PRESET,
            ToolProfile.ALL_TOOLS.name()))
        {
            tab.searchBox().setText(QUERY);
            TreeItem header = tab.itemFor(group);
            assertTrue("the query has to leave " + group + " in the tree", header != null); //$NON-NLS-1$ //$NON-NLS-2$

            tab.tick(header, false);
            tab.save();

            Set<String> disabled = ToolSettingsStore.getInstance().getDisabledTools();
            assertTrue(shown + " was in the list that was unticked", disabled.contains(shown)); //$NON-NLS-1$
            assertFalse("but " + hidden + " was not on the page at all", disabled.contains(hidden)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
