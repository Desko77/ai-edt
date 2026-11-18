/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import ru.aiedt.mcp.oform.layout.ElementKinds;
import ru.aiedt.mcp.oform.session.RenderKinds;

/**
 * Every kind the reader names has exactly one branch of the drawing: the mapping covers the
 * registry - a kind without a branch would draw no figure, a kind under two would be drawn twice -
 * every branch it names is one of the constants, and a kind outside the registry answers none.
 */
public class RenderKindsCoverTheRegistryTest
{
    /** Every kind of the registry is mapped exactly once, to a branch the class itself names. */
    @Test
    public void everyKindHasExactlyOneBranch()
    {
        Set<String> branches = Set.of(RenderKinds.PANEL, RenderKinds.GROUP, RenderKinds.TABLE,
            RenderKinds.COMMAND_PANEL, RenderKinds.LABEL, RenderKinds.BUTTON, RenderKinds.CHECK_BOX,
            RenderKinds.RADIO_BUTTON, RenderKinds.SEPARATOR, RenderKinds.INPUT_FIELD, RenderKinds.PLACEHOLDER);
        assertEquals("the branches named are the constants there are", branches, RenderKinds.branchNames()); //$NON-NLS-1$
        for (String kind : ElementKinds.names())
        {
            String branch = RenderKinds.branchOf(kind);
            assertNotNull(kind + " has a branch", branch); //$NON-NLS-1$
            assertTrue(kind + " names a branch the class declares", branches.contains(branch)); //$NON-NLS-1$
            assertEquals(kind + " is registered once", branch, RenderKinds.branchesByKind().get(kind)); //$NON-NLS-1$
        }
        assertEquals("the registry and the mapping are one set", ElementKinds.names(), //$NON-NLS-1$
            RenderKinds.branchesByKind().keySet());
    }

    /** The ten kinds no imitation is drawn of take the placeholder branch, the rest one of their own. */
    @Test
    public void theUnmappedContentsTakeThePlaceholder()
    {
        assertEquals(RenderKinds.PLACEHOLDER, RenderKinds.branchOf("Image")); //$NON-NLS-1$
        assertEquals(RenderKinds.PLACEHOLDER, RenderKinds.branchOf("TableField")); //$NON-NLS-1$
        assertEquals(RenderKinds.PLACEHOLDER, RenderKinds.branchOf("Chart")); //$NON-NLS-1$
        assertEquals(RenderKinds.INPUT_FIELD, RenderKinds.branchOf("Field")); //$NON-NLS-1$
        assertEquals(RenderKinds.COMMAND_PANEL, RenderKinds.branchOf("CommandPanel")); //$NON-NLS-1$
        assertNull("a kind outside the registry has no branch", RenderKinds.branchOf("НетТакогоВида")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
