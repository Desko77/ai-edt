/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.settings;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.swt.widgets.TreeItem;
import org.junit.Test;

/**
 * What a tool's box shows once a query has brought it back into the tree.
 * <p>
 * The query hides groups and then shows them again, and an item that was dropped is built afresh
 * when it returns. Its box is painted from the disabled set - a tool nobody switched off shows
 * ticked - and an item that comes back unticked reads as switched off, which is the opposite of what
 * the server would answer.
 * </p>
 */
public class AQueryBringsBackTheTicksItShowsTest
{
    /** A fragment that hides part of the tree and leaves the rest, so groups are both dropped and kept. */
    private static final String QUERY = "delete"; //$NON-NLS-1$

    @Test
    public void anEnabledToolComesBackTickedWhenTheQueryReachesIt()
    {
        try (AToolsTabOnAShell tab = AToolsTabOnAShell.open(PrefKeys.PREF_TOOL_PRESET,
            ToolProfile.ALL_TOOLS.name()))
        {
            String shown = AToolsTabOnAShell.firstNameContaining(QUERY);

            tab.searchBox().setText(QUERY);

            TreeItem item = tab.itemFor(shown);
            assertNotNull("the query has to leave " + shown + " in the tree", item); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(shown + " is enabled, so its box has to come back ticked", item.getChecked()); //$NON-NLS-1$
        }
    }
}
