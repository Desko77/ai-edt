/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When a write owes the owner its export.
 * <p>
 * The export after a write exists to carry a mutation the BM model holds that the owner file does
 * not. A write whose action answered {@link BmObjectHelper.Unchanged} holds no such mutation, so
 * the export would rewrite the owner from a model that did not move and could leave its warnings
 * on an answer that promised nothing was written.
 * </p>
 */
public class AnUnchangedWriteOwesNoOwnerExportTest
{
    private static BmObjectHelper.Result aWrite(boolean ok, boolean unchanged)
    {
        BmObjectHelper.Result r = new BmObjectHelper.Result();
        r.ok = ok;
        r.modelUnchanged = unchanged;
        return r;
    }

    /** An unchanged write owes no export, whatever the caller asked for. */
    @Test
    public void anUnchangedWriteOwesNoExport()
    {
        assertFalse(BmObjectHelper.exportOwed(aWrite(true, true), false));
    }

    /** A write that changed the model still owes the owner its export. */
    @Test
    public void aChangedWriteStillOwesTheExport()
    {
        assertTrue(BmObjectHelper.exportOwed(aWrite(true, false), false));
    }

    /** A preview rolls its transaction back and a failed write committed nothing: neither exports. */
    @Test
    public void aPreviewAndAFailureOweNothing()
    {
        assertFalse(BmObjectHelper.exportOwed(aWrite(true, false), true));
        assertFalse(BmObjectHelper.exportOwed(aWrite(false, false), false));
    }

    /** The marker keeps the operation's own wording, so the answer reads as it always did. */
    @Test
    public void theMarkerKeepsTheOperationsOwnWording()
    {
        assertEquals("no drawing with id 7 (idempotent skip)", //$NON-NLS-1$
            BmObjectHelper.Unchanged.of("no drawing with id 7 (idempotent skip)").toString()); //$NON-NLS-1$
    }
}
