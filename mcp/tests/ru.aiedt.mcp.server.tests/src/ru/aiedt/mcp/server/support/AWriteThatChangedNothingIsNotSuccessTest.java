/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A schema write that left the file byte-identical is refused, not reported as done.
 * <p>
 * Measured on the stand: nine fields added in a row reach the model as five, and the answer
 * called all nine a success. The file size stopped growing on exactly the lost calls, which is
 * the signal this guard reads. It does not repair the loss - it stops the loss being silent.
 * </p>
 */
public class AWriteThatChangedNothingIsNotSuccessTest
{
    private static BmDcsHelper.Result resultWith(DcsExtensionExportHelper.Result save)
    {
        BmDcsHelper.Result r = new BmDcsHelper.Result();
        r.ok = true;
        r.directSave = save;
        return r;
    }

    private static DcsExtensionExportHelper.Result save(boolean ok, boolean unchanged)
    {
        DcsExtensionExportHelper.Result s = new DcsExtensionExportHelper.Result();
        s.ok = ok;
        s.contentUnchanged = unchanged;
        s.filePath = "/P/Reports/R/Templates/T/Template.dcs";
        s.bytesWritten = 1761;
        return s;
    }

    @Test
    public void aFileThatDidNotChangeIsRefused()
    {
        BmDcsHelper.Result r = resultWith(save(true, true));
        BmDcsHelper.noteDiskSave(r);

        assertFalse("a write that changed nothing must not stay ok", r.ok);
        assertNotNull("the refusal has to say why", r.error);
        assertTrue("the refusal names both readings of an identical file",
            r.error.contains("already set") && r.error.contains("did not reach the model"));
        assertNotNull("the caller gets the tag too", r.tags.get("schemaUnchanged"));
    }

    @Test
    public void aWriteThatVanishedFromTheFileIsRefused()
    {
        DcsExtensionExportHelper.Result s = save(true, false);
        s.declared = "Bt3";
        s.declaredMissing = true;
        BmDcsHelper.Result r = resultWith(s);
        BmDcsHelper.noteDiskSave(r);

        assertFalse("a change that did not survive must not stay ok", r.ok);
        assertNotNull(r.error);
        assertTrue("the refusal names what went missing", r.error.contains("Bt3"));
        assertNotNull(r.tags.get("declaredContentMissing"));
        assertNull("and it is not also called unchanged", r.tags.get("schemaUnchanged"));
    }

    @Test
    public void anAddThatDidNotGrowTheFileIsRefused()
    {
        DcsExtensionExportHelper.Result s = save(true, false);
        s.declared = "Bt4";
        s.declaredMissing = false;
        s.bytesBefore = 1761;
        s.bytesWritten = 1761;
        BmDcsHelper.Result r = resultWith(s);
        BmDcsHelper.noteDiskSave(r);

        assertFalse("an add that did not grow the file must not stay ok", r.ok);
        assertNotNull(r.tags.get("schemaDidNotGrow"));
        assertTrue("the refusal gives both sizes",
            r.error.contains("1761 to 1761"));
    }

    @Test
    public void anAddThatGrewTheFileIsNotRefused()
    {
        DcsExtensionExportHelper.Result s = save(true, false);
        s.declared = "Bt4";
        s.bytesBefore = 1761;
        s.bytesWritten = 1869;
        BmDcsHelper.Result r = resultWith(s);
        BmDcsHelper.noteDiskSave(r);

        assertTrue("a growing add stays a success", r.ok);
        assertNull(r.tags.get("schemaDidNotGrow"));
    }

    @Test
    public void aWriteWithNoClaimIsNotSizeJudged()
    {
        DcsExtensionExportHelper.Result s = save(true, false);
        s.declared = null;
        s.bytesBefore = 1761;
        s.bytesWritten = 1700;
        BmDcsHelper.Result r = resultWith(s);
        BmDcsHelper.noteDiskSave(r);

        assertTrue("a remove shrinks the file and claims nothing", r.ok);
        assertNull(r.tags.get("schemaDidNotGrow"));
    }

    @Test
    public void aClaimThatHoldsIsNotRefused()
    {
        DcsExtensionExportHelper.Result s = save(true, false);
        s.declared = "Bt3";
        s.declaredMissing = false;
        BmDcsHelper.Result r = resultWith(s);
        BmDcsHelper.noteDiskSave(r);

        assertTrue("a claim the file carries stays a success", r.ok);
        assertNull(r.tags.get("declaredContentMissing"));
    }

    @Test
    public void aFileThatGrewIsLeftAlone()
    {
        BmDcsHelper.Result r = resultWith(save(true, false));
        BmDcsHelper.noteDiskSave(r);

        assertTrue("a real write stays a success", r.ok);
        assertNull(r.error);
        assertNull(r.tags.get("schemaUnchanged"));
    }

    @Test
    public void aFailedSaveKeepsItsOwnTagAndIsNotJudgedTwice()
    {
        DcsExtensionExportHelper.Result s = save(false, true);
        s.error = "cannot resolve .dcs";
        BmDcsHelper.Result r = resultWith(s);
        BmDcsHelper.noteDiskSave(r);

        assertNotNull("a failed save keeps its own tag", r.tags.get("diskSaveFailed"));
        assertNull("and is not also accused of changing nothing", r.tags.get("schemaUnchanged"));
    }

    @Test
    public void noSaveAtAllIsNotAccused()
    {
        BmDcsHelper.Result r = resultWith(null);
        BmDcsHelper.noteDiskSave(r);

        assertTrue("a dry run never exports, and must not be refused for it", r.ok);
        assertNull(r.tags.get("schemaUnchanged"));
    }
}
