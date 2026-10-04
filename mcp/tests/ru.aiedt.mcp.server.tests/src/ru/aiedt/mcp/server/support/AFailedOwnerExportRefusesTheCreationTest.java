/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A created schema is not "created" while the owner .mdo never received it.
 * <p>
 * The creation writes the schema template and the .dcs file, but the owner's
 * mainDataCompositionSchema reference reaches disk only through the owner export. When that
 * export fails, answering success leaves the platform opening the report without a schema while
 * the caller holds a "created" answer.
 * </p>
 */
public class AFailedOwnerExportRefusesTheCreationTest
{
    private static BmDcsHelper.Result createdSchema()
    {
        BmDcsHelper.Result r = new BmDcsHelper.Result();
        r.ok = true;
        r.schemaFqn = "Report.R.Template.Main.Template"; //$NON-NLS-1$
        return r;
    }

    @Test
    public void aFailedOwnerExportRefusesTheCreation()
    {
        BmExportHelper.Result export = new BmExportHelper.Result();
        export.forceExportOk = false;
        export.error = "the project did not resolve"; //$NON-NLS-1$
        BmDcsHelper.Result r = createdSchema();
        BmDcsHelper.noteOwnerExport(r, export, "Report.R"); //$NON-NLS-1$

        assertFalse("a creation whose owner never reached disk must not stay ok", r.ok); //$NON-NLS-1$
        assertNotNull("the refusal has to say why", r.error); //$NON-NLS-1$
        assertTrue("the refusal names the export failure", //$NON-NLS-1$
            r.error.contains("the project did not resolve")); //$NON-NLS-1$
        assertTrue("the refusal says what did reach disk", //$NON-NLS-1$
            r.error.contains("the schema template and the .dcs file were written")); //$NON-NLS-1$
        assertNotNull("the caller gets the tag too", r.tags.get("ownerExportFailed")); //$NON-NLS-1$
    }

    /** Both the .dcs save and the owner export failed: the answer names both, and not a written file. */
    @Test
    public void aFailedSaveIsKeptBesideAFailedExport()
    {
        BmExportHelper.Result export = new BmExportHelper.Result();
        export.forceExportOk = false;
        export.error = "the project did not resolve"; //$NON-NLS-1$
        BmDcsHelper.Result r = createdSchema();
        r.ok = false;
        r.error = "the schema was not written to disk"; //$NON-NLS-1$
        BmDcsHelper.noteOwnerExport(r, export, "Report.R"); //$NON-NLS-1$

        assertFalse(r.ok);
        assertTrue("the save failure is kept", //$NON-NLS-1$
            r.error.startsWith("the schema was not written to disk")); //$NON-NLS-1$
        assertTrue("the export failure is added", r.error.contains("the project did not resolve")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("no claim that the file was written", //$NON-NLS-1$
            r.error.contains(".dcs file were written")); //$NON-NLS-1$
    }

    @Test
    public void anExportWithoutAnErrorTextStillRefuses()
    {
        BmExportHelper.Result export = new BmExportHelper.Result();
        export.forceExportOk = false;
        BmDcsHelper.Result r = createdSchema();
        BmDcsHelper.noteOwnerExport(r, export, "Report.R"); //$NON-NLS-1$

        assertFalse("forceExportOk=false with no error text is still a failed export", r.ok); //$NON-NLS-1$
        assertNotNull(r.tags.get("ownerExportFailed")); //$NON-NLS-1$
    }

    @Test
    public void aPendingFlushStaysASuccess()
    {
        BmExportHelper.Result export = new BmExportHelper.Result();
        export.forceExportOk = true;
        export.syncFlushPending = true;
        BmDcsHelper.Result r = createdSchema();
        BmDcsHelper.noteOwnerExport(r, export, "Report.R"); //$NON-NLS-1$

        assertTrue("committed to BM with a pending flush is not a refusal", r.ok); //$NON-NLS-1$
        assertNotNull(r.tags.get("diskFlushPending")); //$NON-NLS-1$
        assertNull(r.tags.get("ownerExportFailed")); //$NON-NLS-1$
    }

    @Test
    public void aCleanExportIsLeftAlone()
    {
        BmExportHelper.Result export = new BmExportHelper.Result();
        export.forceExportOk = true;
        BmDcsHelper.Result r = createdSchema();
        BmDcsHelper.noteOwnerExport(r, export, "Report.R"); //$NON-NLS-1$

        assertTrue("a clean export stays a success", r.ok); //$NON-NLS-1$
        assertNull(r.error);
        assertTrue("and no tags are added", r.tags.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void noExportAtAllIsNotAccused()
    {
        BmDcsHelper.Result r = createdSchema();
        BmDcsHelper.noteOwnerExport(r, null, "Report.R"); //$NON-NLS-1$

        assertTrue("no export was taken, so nothing is refused", r.ok); //$NON-NLS-1$
        assertEquals("Report.R.Template.Main.Template", r.schemaFqn); //$NON-NLS-1$
    }
}
