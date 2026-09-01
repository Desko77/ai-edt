/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;

import org.junit.Test;

/**
 * What decides that a schema write is run again.
 * <p>
 * The decision is taken from the file, never from a remembered count. Two earlier attempts kept
 * how many elements the last write left and refused a write that did not exceed it; the remembered
 * number comes from a commit that may itself have been overwritten, and after the first loss such
 * a guard refuses every later write for good. Measured: 2 of 9 and 3 of 9 written where 9 were due.
 * </p>
 */
public class AWriteRunsAgainWhenTheFileLostItTest
{
    private static BmDcsHelper.Result taggedWith(String tag)
    {
        BmDcsHelper.Result r = new BmDcsHelper.Result();
        if (tag != null)
        {
            r.tags.put(tag, new LinkedHashMap<String, Object>());
        }
        return r;
    }

    @Test
    public void anElementMissingFromTheFileIsWorthAnotherAttempt()
    {
        assertTrue(BmDcsHelper.theFileDidNotTakeIt(taggedWith("declaredContentMissing"))); //$NON-NLS-1$
    }

    @Test
    public void anAddThatDidNotGrowTheFileIsWorthAnotherAttempt()
    {
        assertTrue(BmDcsHelper.theFileDidNotTakeIt(taggedWith("schemaDidNotGrow"))); //$NON-NLS-1$
    }

    @Test
    public void aSchemaThatDidNotChangeAtAllIsNotRepeated()
    {
        // The value asked for may simply have been set already. Repeating that burns the whole
        // backoff to arrive at the same answer.
        assertFalse(BmDcsHelper.theFileDidNotTakeIt(taggedWith("schemaUnchanged"))); //$NON-NLS-1$
    }

    @Test
    public void aWriteTheFileTookIsNotRepeated()
    {
        assertFalse(BmDcsHelper.theFileDidNotTakeIt(taggedWith(null)));
    }

    @Test
    public void anAlreadyExistingElementIsRecognisedByItsTag()
    {
        MetadataGuards.ErrorTag tag =
            new MetadataGuards.ErrorTag(ErrorTags.ALREADY_EXISTS.wire(), null);
        MetadataGuards.BlockedGuardException blocked = new MetadataGuards.BlockedGuardException(
            MetadataGuards.Verdict.block("field already exists: X", "hint", tag)); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("a repeat has to tell this apart from a real refusal", //$NON-NLS-1$
            BmDcsHelper.becauseItIsAlreadyThere(blocked));
    }

    @Test
    public void anotherRefusalIsNotMistakenForIt()
    {
        MetadataGuards.ErrorTag tag = new MetadataGuards.ErrorTag(ErrorTags.NOT_FOUND.wire(), null);
        MetadataGuards.BlockedGuardException blocked = new MetadataGuards.BlockedGuardException(
            MetadataGuards.Verdict.block("dataSet not found: X", "hint", tag)); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(BmDcsHelper.becauseItIsAlreadyThere(blocked));
    }

    @Test
    public void aRefusalWithoutATagIsNotMistakenForIt()
    {
        MetadataGuards.BlockedGuardException blocked = new MetadataGuards.BlockedGuardException(
            MetadataGuards.Verdict.block("blocked", "hint")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(BmDcsHelper.becauseItIsAlreadyThere(blocked));
    }
}
