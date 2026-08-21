/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guards the boundary of the platform verdict on an extension.
 * <p>
 * The verdict itself needs a platform, a delivery and a staging infobase, and is measured on a
 * stand. What is pinned here is the part that decides how the answer READS: a run that never
 * happened must not come back looking like a refusal. Both are "the extension did not go in", and
 * only one of them is about the extension.
 * </p>
 */
public class BinaryVerdictTest
{
    private static final Path NOWHERE = Paths.get("no-such-file.cf"); //$NON-NLS-1$

    @Test
    public void aRunThatCannotBeMadeIsNotAVerdict()
    {
        // applies stays null. false would say "the platform refused this extension" about an
        // extension the platform never saw - the one misreading that would send somebody back to
        // change working code.
        BmBinaryImportHelper.Verdict v =
            BmBinaryImportHelper.verdict(NOWHERE, Paths.get("nor-this.cfe"), null, null); //$NON-NLS-1$
        assertFalse(v.ok);
        assertNull(v.applies);
        assertNotNull(v.error);
    }

    @Test
    public void aFileOfTheWrongKindIsRefusedByKindRatherThanTried()
    {
        // A .cfe handed in as the delivery would create an infobase and fail obscurely halfway.
        BmBinaryImportHelper.Verdict v = BmBinaryImportHelper.verdict(
            Paths.get("swapped.cfe"), Paths.get("swapped.cf"), null, null); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(v.ok);
        assertTrue(v.error, v.error.contains(".cf")); //$NON-NLS-1$
        assertFalse("nothing should have been created", v.stagingCreated); //$NON-NLS-1$
    }

    @Test
    public void nothingIsCreatedWhenTheInputIsRefused()
    {
        // Whatever else goes wrong, a refused call must not leave an infobase registered. There is
        // no name to report for one that was never made, so a caller could not clean it up.
        BmBinaryImportHelper.Verdict v =
            BmBinaryImportHelper.verdict(NOWHERE, Paths.get("nor-this.cfe"), null, null); //$NON-NLS-1$
        assertFalse(v.stagingCreated);
        assertFalse(v.stagingRemoved);
    }

    /**
     * A refused input reaches no stage, so it names none.
     * <p>
     * refusedAt exists because the two ways an extension can be turned away read the same in an
     * answer and mean different things: the file would not load at all, or it loaded and does not
     * fit. Leaving it set on a call that never ran would invent a stage.
     * </p>
     */
    @Test
    public void aRunThatNeverStartedNamesNoStage()
    {
        assertNull(BmBinaryImportHelper.verdict(NOWHERE, Paths.get("nor-this.cfe"), null, null) //$NON-NLS-1$
            .refusedAt);
    }

    @Test
    public void theKindOfAFileIsReadFromItsName()
    {
        assertEquals(BmBinaryImportHelper.BinaryKind.CONFIGURATION,
            BmBinaryImportHelper.kindOf(Paths.get("delivery.cf"))); //$NON-NLS-1$
        assertEquals(BmBinaryImportHelper.BinaryKind.EXTENSION,
            BmBinaryImportHelper.kindOf(Paths.get("tweak.cfe"))); //$NON-NLS-1$
        assertNull(BmBinaryImportHelper.kindOf(Paths.get("notes.txt"))); //$NON-NLS-1$
    }
}
