/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server.toolkit.ops;
import static org.junit.Assert.*;
import org.junit.Test;
import ru.aiedt.mcp.server.support.BmObjectHelper;
public class ATemplateExportMustBeAcknowledgedTest
{
    @Test public void exportFailureAfterCommitIsNotSuccessOrRetryable()
    {
        BmObjectHelper.Result result = new BmObjectHelper.Result();
        result.ok = true;
        result.tags.put("forceExportWarning", "export did not settle");
        MxlWorkshopTool.requireExportAcknowledgement(result);
        assertFalse(result.ok);
        assertEquals(Boolean.TRUE, result.tags.get("mutationStarted"));
        assertEquals(Boolean.FALSE, result.tags.get("retryable"));
        assertEquals("committed_unconfirmed", result.tags.get("status"));
    }
    @Test public void pendingFlushPreservesTheResyncRecoveryHint()
    {
        BmObjectHelper.Result result = new BmObjectHelper.Result();
        result.ok = true;
        result.tags.put("diskFlushPending", true);
        result.tags.put("diskFlushHint", "Use resync_to_disk; do not re-add");
        MxlWorkshopTool.requireExportAcknowledgement(result);
        assertFalse(result.ok);
        assertEquals("Use resync_to_disk; do not re-add", result.tags.get("diskFlushHint"));
        assertEquals(Boolean.FALSE, result.tags.get("retryable"));
    }

    @Test public void failedMutationMustNotBeRelabeledAsCommitted()
    {
        BmObjectHelper.Result result = new BmObjectHelper.Result();
        result.ok = false;
        result.error = "serialization failed";
        result.tags.put("forceExportWarning", "not acknowledged");
        MxlWorkshopTool.requireExportAcknowledgement(result);
        assertEquals("serialization failed", result.error);
        assertFalse(result.tags.containsKey("status"));
    }

    @Test public void actualFormatResultClassifiesWriteAttemptAsUnknown() throws Exception
    {
        java.lang.reflect.Field slot = MxlWorkshopTool.class.getDeclaredField("EXPECTED_IDENTITY");
        slot.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<MxlWorkshopTool.ExpectedIdentity> context =
            (ThreadLocal<MxlWorkshopTool.ExpectedIdentity>) slot.get(null);
        MxlWorkshopTool.ExpectedIdentity expected = new MxlWorkshopTool.ExpectedIdentity(1, 2, "Print", false);
        java.lang.reflect.Constructor<ru.aiedt.mcp.server.support.BmTemplateHelper.PersistOutcome> ctor =
            ru.aiedt.mcp.server.support.BmTemplateHelper.PersistOutcome.class
                .getDeclaredConstructor(String.class, String.class, boolean.class);
        ctor.setAccessible(true);
        expected.persistence = ctor.newInstance("Template file write failed: bytes may be partial", null, true);
        BmObjectHelper.Result failed = new BmObjectHelper.Result();
        failed.error = expected.persistence.error;
        java.lang.reflect.Method format = MxlWorkshopTool.class.getDeclaredMethod("formatResult",
            BmObjectHelper.Result.class, String.class);
        format.setAccessible(true);
        context.set(expected);
        try
        {
            com.google.gson.JsonObject answer = com.google.gson.JsonParser.parseString(
                (String) format.invoke(new MxlWorkshopTool(), failed, "set_cell")).getAsJsonObject();
            assertEquals("outcome_unknown", answer.get("status").getAsString());
            assertTrue(answer.get("mutationStarted").getAsBoolean());
            assertFalse(answer.get("retryable").getAsBoolean());
            assertTrue(answer.get("recoveryHint").getAsString().contains("do not replay"));
        }
        finally { context.remove(); }
    }

    @Test public void successfulExportKeepsSuccess()
    {
        BmObjectHelper.Result result = new BmObjectHelper.Result();
        result.ok = true;
        MxlWorkshopTool.requireExportAcknowledgement(result);
        assertTrue(result.ok);
    }
}
