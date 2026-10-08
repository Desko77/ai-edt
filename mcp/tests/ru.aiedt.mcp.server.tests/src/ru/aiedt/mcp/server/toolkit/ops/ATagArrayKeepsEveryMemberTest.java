/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * The {@code tags} argument of {@code tag_admin} reaches validation with every member it carried.
 */
public class ATagArrayKeepsEveryMemberTest
{
    /** A JSON null, an object and a nested array each stay in the list as the empty name. */
    @Test
    public void aMemberThatIsNotTextBecomesTheEmptyName()
    {
        assertEquals(Arrays.asList("One", ""), //$NON-NLS-1$ //$NON-NLS-2$
            TagAdminFacadeTool.tagNames("[\"One\", null]", Arrays.asList("One"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("One", ""), //$NON-NLS-1$ //$NON-NLS-2$
            TagAdminFacadeTool.tagNames("[\"One\", {\"a\": 1}]", Arrays.asList("One"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("One", ""), //$NON-NLS-1$ //$NON-NLS-2$
            TagAdminFacadeTool.tagNames("[\"One\", [\"Two\"]]", Arrays.asList("One"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Arrays.asList("One", ""), //$NON-NLS-1$ //$NON-NLS-2$
            TagAdminFacadeTool.tagNames("[\"One\", 5]", Arrays.asList("One", "5"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Text members keep their order, lose surrounding blanks and are named once. */
    @Test
    public void textMembersAreKeptInOrder()
    {
        assertEquals(Arrays.asList("Two", "One"), //$NON-NLS-1$ //$NON-NLS-2$
            TagAdminFacadeTool.tagNames("[\" Two \", \"One\", \"Two\"]", null)); //$NON-NLS-1$
    }

    /** An argument that is not a JSON array falls back to the shared reading; none gives no names. */
    @Test
    public void aPlainArgumentUsesTheSharedReading()
    {
        assertEquals(Arrays.asList("One", "Two"), //$NON-NLS-1$ //$NON-NLS-2$
            TagAdminFacadeTool.tagNames("One,Two", Arrays.asList("One", " Two"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(List.of(), TagAdminFacadeTool.tagNames(null, null));
    }
}
