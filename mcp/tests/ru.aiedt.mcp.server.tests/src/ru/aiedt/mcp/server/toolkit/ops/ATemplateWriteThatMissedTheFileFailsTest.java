/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmObjectHelper;

/**
 * What a template write answers when it changed the model and missed the file.
 * <p>
 * The mutation lands in the in-memory moxel model first and is then written to
 * {@code Template.mxlx}. When that write failed the model still carried the change, so all eight
 * mutating operations answered success and carried the reason as a note beside it. The file is what
 * survives a restart and what reaches version control.
 * </p>
 */
public class ATemplateWriteThatMissedTheFileFailsTest
{
    private static BmObjectHelper.Result succeeded()
    {
        BmObjectHelper.Result r = new BmObjectHelper.Result();
        r.ok = true;
        r.message = "(1,1)=Header"; //$NON-NLS-1$
        return r;
    }

    @Test
    public void aFailedFileWriteDemotesTheAnswer()
    {
        BmObjectHelper.Result r = succeeded();
        MxlWorkshopTool.failOnPersist(r, "disk full"); //$NON-NLS-1$
        assertFalse("a write that missed the file is not a success", r.ok); //$NON-NLS-1$
        assertNotNull(r.error);
    }

    @Test
    public void theAnswerSaysWhatWasLostAndWhy()
    {
        BmObjectHelper.Result r = succeeded();
        MxlWorkshopTool.failOnPersist(r, "disk full"); //$NON-NLS-1$
        assertTrue(r.error, r.error.contains("Template.mxlx")); //$NON-NLS-1$
        assertTrue(r.error, r.error.contains("disk full")); //$NON-NLS-1$
    }

    @Test
    public void theReasonIsAlsoTagged()
    {
        BmObjectHelper.Result r = succeeded();
        MxlWorkshopTool.failOnPersist(r, "disk full"); //$NON-NLS-1$
        assertEquals("disk full", r.tags.get("templateMutationPersistFailed")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void aWriteThatReachedTheFileIsLeftAlone()
    {
        BmObjectHelper.Result r = succeeded();
        MxlWorkshopTool.failOnPersist(r, null);
        assertTrue("nothing failed, so nothing is demoted", r.ok); //$NON-NLS-1$
        assertNull(r.error);
        assertTrue(r.tags.isEmpty());
    }

    @Test
    public void noResultIsNotAnError()
    {
        MxlWorkshopTool.failOnPersist(null, "disk full"); //$NON-NLS-1$
    }
}
