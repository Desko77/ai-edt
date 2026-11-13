/* Copyright (c) 2026 Desko77. SPDX-License-Identifier: AGPL-3.0-or-later */
package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.*;
import org.junit.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Only server-prefixed form failures receive the bounded machine classification. */
public class AFormRefusalKeepsItsErrorCodeTest
{
    private JsonObject convert(String text)
    {
        return JsonParser.parseString(FormItemsOps.convertEditFormMarkdownToJson(text,
            "add_field", "Catalog.Sample.Form.ItemForm")).getAsJsonObject();
    }

    @Test public void canonicalRefusalsKeepTheirCodeInWriteAndPreview()
    {
        for (String code : new String[] { "FORM_PLACEMENT_INVALID_SIBLING",
            "FORM_PLACEMENT_AMBIGUOUS_SIBLING", "EXTENSION_BINDING_UNSUPPORTED",
            "EXTENSION_BINDING_UNVERIFIED", "EXTENSION_BINDING_UNRESOLVED" })
        {
            for (String prefix : new String[] { "Error: ", "Error: BM API error: ",
                "Error: dry run - action would FAIL: ",
                "Error: dry run - action would FAIL: Error: BM API error: " })
            {
                JsonObject result = convert(prefix + code + ": refused");
                assertFalse(result.get("success").getAsBoolean());
                assertTrue(result.toString(), result.has("errorCode"));
                assertEquals(code, result.get("errorCode").getAsString());
                assertFalse(result.has("mutationStarted"));
                assertFalse(result.has("unavailableChecks"));
            }
        }
    }

    @Test public void directPropertyHelperErrorsKeepTheirCode()
    {
        for (String prefix : new String[] { "Error: ",
            "Error: dry run - action would FAIL: " })
        {
            JsonObject result = JsonParser.parseString(EditMetadataTool.formatFormResult(
                prefix + "EXTENSION_BINDING_UNVERIFIED: ownership unavailable",
                "set_property", "Catalog.Sample.Form.ItemForm")).getAsJsonObject();
            assertFalse(result.get("success").getAsBoolean());
            assertTrue(result.toString(), result.has("errorCode"));
            assertEquals("EXTENSION_BINDING_UNVERIFIED", result.get("errorCode").getAsString());
            assertFalse(result.has("mutationStarted"));
        }
    }

    @Test public void arbitraryUserTextAndSuccessfulMessagesDoNotAcquireACode()
    {
        for (String text : new String[] {
            "Error: invalid name 'EXTENSION_BINDING_UNSUPPORTED: user input'",
            "Error: UNKNOWN_CODE: FORM_PLACEMENT_INVALID_SIBLING: text",
            "---\nstatus: success\n---\nEXTENSION_BINDING_UNVERIFIED: not a refusal",
            "Error: FORM_PLACEMENT_INVALID_SIBLING_extra: text" })
        {
            assertFalse(convert(text).toString(), convert(text).has("errorCode"));
        }
    }
}
