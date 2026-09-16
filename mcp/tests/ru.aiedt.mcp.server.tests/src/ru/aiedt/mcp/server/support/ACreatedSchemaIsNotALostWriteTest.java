/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Creating a schema whose file already holds those bytes is a success, not a lost write.
 * <p>
 * The unchanged-file refusal is there to catch a CHANGE that never reached the model. A CREATION
 * meets the same reading for the opposite reason: the environment exports the new template itself,
 * and what it writes is the same empty schema the direct save writes. Measured on a workspace -
 * create_schema on a fresh report produced a usable schema of 927 bytes, add_dataset then worked
 * against it, and the create call had reported failure because those bytes were already on disk.
 * </p>
 */
public class ACreatedSchemaIsNotALostWriteTest
{
    private static BmDcsHelper.Result savedIdenticalBytes()
    {
        DcsExtensionExportHelper.Result save = new DcsExtensionExportHelper.Result();
        save.ok = true;
        save.contentUnchanged = true;
        save.bytesWritten = 927;
        save.bytesBefore = 927;
        save.filePath = "/Probe/src/Reports/R/Templates/Main/Template.dcs"; //$NON-NLS-1$

        BmDcsHelper.Result result = new BmDcsHelper.Result();
        result.ok = true;
        result.schemaFqn = "Report.R.Template.Main.Template"; //$NON-NLS-1$
        result.directSave = save;
        return result;
    }

    /** A change that left the file as it was is still refused - that is what the check is for. */
    @Test
    public void aChangeThatWroteNothingIsStillRefused()
    {
        BmDcsHelper.Result result = savedIdenticalBytes();
        BmDcsHelper.noteDiskSave(result, false);
        assertFalse("a mutation that changed no byte has to be refused", result.ok); //$NON-NLS-1$
        assertTrue("and it has to say which file it read", //$NON-NLS-1$
            result.tags.containsKey("schemaUnchanged")); //$NON-NLS-1$
    }

    /** The same reading, on a creation, is what success looks like. */
    @Test
    public void aCreationThatMatchesTheFileIsNotRefused()
    {
        BmDcsHelper.Result result = savedIdenticalBytes();
        BmDcsHelper.noteDiskSave(result, true);
        assertTrue("a schema that reached disk is not a failure", result.ok); //$NON-NLS-1$
        assertTrue("and the answer still carries what is on disk", //$NON-NLS-1$
            result.tags.containsKey("schemaAlreadyOnDisk")); //$NON-NLS-1$
        assertFalse("the refusal tag belongs to a change, not to a creation", //$NON-NLS-1$
            result.tags.containsKey("schemaUnchanged")); //$NON-NLS-1$
    }

    /** A save that actually failed is refused either way - creation does not excuse it. */
    @Test
    public void aFailedSaveIsRefusedOnACreationToo()
    {
        BmDcsHelper.Result result = savedIdenticalBytes();
        result.directSave.ok = false;
        result.directSave.error = "the file could not be written"; //$NON-NLS-1$
        BmDcsHelper.noteDiskSave(result, true);
        assertTrue("a failed save has to be named", //$NON-NLS-1$
            result.tags.containsKey("diskSaveFailed")); //$NON-NLS-1$
    }
}
