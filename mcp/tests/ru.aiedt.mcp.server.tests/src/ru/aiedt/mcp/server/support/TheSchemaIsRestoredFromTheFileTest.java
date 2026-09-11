/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import ru.aiedt.mcp.server.support.DcsSchemaRestorer.Outcome;
import ru.aiedt.mcp.server.support.DcsSchemaRestorer.Step;

/**
 * The file is the source and is never written; what the model holds decides what happens to it.
 * <p>
 * The previous restoration began by writing the model's schema over the very file it was asked to
 * restore from, and compared the two by length. Both are pinned here: the model is read once and
 * serialized once, the same bytes serve the comparison and the backup, and two schemas of one
 * length are two schemas.
 * </p>
 */
public class TheSchemaIsRestoredFromTheFileTest
{
    private static final byte[] FILE = bytes("<schema>from the file</schema>"); //$NON-NLS-1$

    private static final Object FROM_FILE = new Object();

    // ---- 1: an empty model takes the file's schema -----------------------------------------

    @Test
    public void anEmptyModelTakesTheSchemaFromTheFile()
    {
        FakeModel model = new FakeModel(new Object(), null, null);
        Step step = DcsSchemaRestorer.restoreWithin(FILE, FROM_FILE, false, model);
        assertEquals(Outcome.RESTORED, step.outcome);
        assertSame(FROM_FILE, model.attached);
        assertSame(FROM_FILE, model.templateSetTo);
        assertNull(model.detached);
        assertEquals("nothing to serialize when the model holds nothing", 0, model.serializations); //$NON-NLS-1$
        assertEquals(0, model.backups.size());
    }

    // ---- 2: the same length is not the same schema -------------------------------------------

    @Test
    public void twoSchemasOfOneLengthAreTwoSchemas()
    {
        byte[] sameLength = bytes("<schema>from the fils</schema>"); //$NON-NLS-1$
        assertEquals(FILE.length, sameLength.length);
        FakeModel model = new FakeModel(new Object(), new Object(), sameLength);
        Step step = DcsSchemaRestorer.restoreWithin(FILE, FROM_FILE, false, model);
        assertEquals(Outcome.REFUSED_MODEL_DIFFERS, step.outcome);
        assertEquals(sameLength.length, step.modelBytes);
        assertEquals("<schema>from the fil".length(), step.firstDifferenceAt); //$NON-NLS-1$
    }

    @Test
    public void theSameBytesMeanNothingToDo()
    {
        FakeModel model = new FakeModel(new Object(), new Object(), FILE.clone());
        Step step = DcsSchemaRestorer.restoreWithin(FILE, FROM_FILE, true, model);
        assertEquals(Outcome.MATCHED, step.outcome);
        assertNull(model.attached);
        assertNull(model.detached);
        assertNull(model.templateSetTo);
        assertEquals(0, model.backups.size());
    }

    // ---- 3: a differing model is left alone unless allowed ---------------------------------

    @Test
    public void aDifferingModelIsRefusedAndLeftAlone()
    {
        FakeModel model = new FakeModel(new Object(), new Object(), bytes("<schema>edited</schema>")); //$NON-NLS-1$
        Step step = DcsSchemaRestorer.restoreWithin(FILE, FROM_FILE, false, model);
        assertEquals(Outcome.REFUSED_MODEL_DIFFERS, step.outcome);
        assertNull(model.attached);
        assertNull(model.detached);
        assertNull(model.templateSetTo);
        assertEquals(0, model.backups.size());
        assertNull(step.backup);
    }

    // ---- 4: allowed, the model's schema is kept aside first -----------------------------------

    @Test
    public void whenAllowedTheModelsSchemaIsWrittenAsideAndReplaced()
    {
        Object existing = new Object();
        byte[] modelBytes = bytes("<schema>edited</schema>"); //$NON-NLS-1$
        FakeModel model = new FakeModel(new Object(), existing, modelBytes);
        Step step = DcsSchemaRestorer.restoreWithin(FILE, FROM_FILE, true, model);
        assertEquals(Outcome.REPLACED, step.outcome);
        assertEquals(1, model.backups.size());
        assertArrayEquals(modelBytes, model.backups.get(0));
        assertNotNull(step.backup);
        assertSame(existing, model.detached);
        assertSame(FROM_FILE, model.attached);
        assertSame(FROM_FILE, model.templateSetTo);
        assertTrue("the backup is written before the model is touched", model.backupBeforeDetach); //$NON-NLS-1$
    }

    @Test
    public void aBackupThatCannotBeWrittenCancelsTheReplacement()
    {
        Object existing = new Object();
        FakeModel model = new FakeModel(new Object(), existing, bytes("<schema>edited</schema>")); //$NON-NLS-1$
        model.backupFails = true;
        Step step = DcsSchemaRestorer.restoreWithin(FILE, FROM_FILE, true, model);
        assertEquals(Outcome.FAILED, step.outcome);
        assertTrue(step.error, step.error.contains("disk is full")); //$NON-NLS-1$
        assertNull(model.detached);
        assertNull(model.attached);
        assertNull(model.templateSetTo);
    }

    // ---- 5: one read, one serialization ------------------------------------------------------

    @Test
    public void theModelIsReadOnceAndSerializedOnce()
    {
        FakeModel model = new FakeModel(new Object(), new Object(), bytes("<schema>edited</schema>")); //$NON-NLS-1$
        DcsSchemaRestorer.restoreWithin(FILE, FROM_FILE, true, model);
        assertEquals(1, model.existingReads);
        assertEquals(1, model.serializations);
        assertArrayEquals("the backup is the serialization that was compared", //$NON-NLS-1$
            model.lastSerialization, model.backups.get(0));
    }

    // ---- 6: no template, no restoration -----------------------------------------------------

    @Test
    public void aMissingTemplateIsItsOwnRefusal()
    {
        FakeModel model = new FakeModel(null, null, null);
        Step step = DcsSchemaRestorer.restoreWithin(FILE, FROM_FILE, true, model);
        assertEquals(Outcome.NO_TEMPLATE, step.outcome);
        assertEquals(0, model.existingReads);
        assertNull(model.attached);
    }

    // ---- 8: a serializer that refuses is a failure, not a match -------------------------------

    @Test
    public void aSerializerThatRefusesIsAFailureNotAMatch()
    {
        FakeModel model = new FakeModel(new Object(), new Object(), null);
        model.serializeFails = true;
        Step step = DcsSchemaRestorer.restoreWithin(FILE, FROM_FILE, true, model);
        assertEquals(Outcome.FAILED, step.outcome);
        assertFalse(step.error.isEmpty());
        assertNull(model.detached);
    }

    private static byte[] bytes(String text)
    {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A model that remembers what was done to it. */
    private static final class FakeModel
        implements DcsSchemaRestorer.ModelAccess
    {
        private final Object template;

        private final Object existing;

        private final byte[] existingBytes;

        boolean backupFails;

        boolean serializeFails;

        int existingReads;

        int serializations;

        byte[] lastSerialization;

        Object attached;

        Object detached;

        Object templateSetTo;

        final List<byte[]> backups = new ArrayList<>();

        boolean backupBeforeDetach;

        FakeModel(Object template, Object existing, byte[] existingBytes)
        {
            this.template = template;
            this.existing = existing;
            this.existingBytes = existingBytes;
        }

        @Override
        public Object template()
        {
            return template;
        }

        @Override
        public Object existingSchema()
        {
            existingReads++;
            return existing;
        }

        @Override
        public byte[] serialize(Object schema) throws Exception
        {
            serializations++;
            if (serializeFails)
            {
                throw new IllegalStateException("the serializer refused"); //$NON-NLS-1$
            }
            assertSame(existing, schema);
            lastSerialization = existingBytes.clone();
            return lastSerialization;
        }

        @Override
        public void detach(Object schema)
        {
            detached = schema;
        }

        @Override
        public void attach(Object schema)
        {
            attached = schema;
        }

        @Override
        public void setTemplate(Object aTemplate, Object schema)
        {
            assertSame(template, aTemplate);
            templateSetTo = schema;
        }

        @Override
        public Path writeBackup(byte[] bytes) throws IOException
        {
            if (backupFails)
            {
                throw new IOException("disk is full"); //$NON-NLS-1$
            }
            backups.add(bytes);
            backupBeforeDetach = detached == null;
            return Paths.get("Template.dcs.model-20260911-000000.bak"); //$NON-NLS-1$
        }
    }
}
