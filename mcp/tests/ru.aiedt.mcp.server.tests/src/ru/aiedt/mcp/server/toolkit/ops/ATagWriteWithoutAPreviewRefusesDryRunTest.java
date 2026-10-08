/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A {@code tag_admin} write that has no preview does not run when the call asks for a dry run.
 */
public class ATagWriteWithoutAPreviewRefusesDryRunTest
{
    /** The four writes without a preview refuse dryRun=true and say nothing was written. */
    @Test
    public void aDryRunOfAWriteWithoutAPreviewIsRefused()
    {
        for (String operation : new String[] {"create_tag", "update_tag", "assign_tag", "unassign_tag"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String refusal = TagAdminFacadeTool.previewRefusal(operation, true);
            assertNotNull(operation, refusal);
            assertTrue(refusal, refusal.contains("Nothing was written")); //$NON-NLS-1$
            assertTrue(refusal, refusal.contains(operation));
        }
    }

    /** delete_tag has a preview, and a call without dryRun is never refused here. */
    @Test
    public void thePreviewOfDeleteAndPlainCallsProceed()
    {
        assertNull(TagAdminFacadeTool.previewRefusal("delete_tag", true)); //$NON-NLS-1$
        assertNull(TagAdminFacadeTool.previewRefusal("create_tag", false)); //$NON-NLS-1$
        assertNull(TagAdminFacadeTool.previewRefusal("assign_tag", false)); //$NON-NLS-1$
    }
}
