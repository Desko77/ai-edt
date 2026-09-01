/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What {@code remove_item} answers, given that it removes nothing anywhere.
 * <p>
 * All three of its branches used to come back {@code success: true} carrying a sentence that named
 * another operation. A caller reads the success flag, so the item was taken for removed; measured
 * on a stand 31.08, the element stayed on the form and the marker EDT had raised on it stayed with
 * it, through two identical calls.
 * </p>
 * <p>
 * The branch count matters as much as the branch: three contexts answered this way, and only one of
 * them was measured.
 * </p>
 */
public class ARemovalThatRemovedNothingSaysSoTest
{
    private static JsonObject removeFrom(String containerFqn)
    {
        Map<String, String> params = new HashMap<>();
        params.put("containerFqn", containerFqn);
        params.put("name", "Строки");
        params.put("projectName", "AnyProject");
        return JsonParser.parseString(new MiscOps().opRemoveItem(params)).getAsJsonObject();
    }

    private static void isRefusal(JsonObject answered)
    {
        assertFalse("remove_item removed nothing and must not answer success",
            answered.has("success") && answered.get("success").getAsBoolean());
        assertTrue("the refusal has to say that nothing was removed",
            answered.toString().contains("Nothing was removed"));
    }

    @Test
    public void aFormIsRefusedAndTheFormOperationIsNamed()
    {
        JsonObject answered = removeFrom("Catalog.Товары.Form.ФормаСписка.Form");
        isRefusal(answered);
        assertTrue("the caller needs the operation that does remove from a form",
            answered.toString().contains("edit_form operation=remove_item"));
    }

    /** The spelling the neighbouring operation accepted and this one did not. */
    @Test
    public void aCommonFormIsRefusedAsAForm()
    {
        JsonObject answered = removeFrom("CommonForm.ПодборТоваров");
        isRefusal(answered);
        assertTrue("a common form is a form, whichever way its FQN is written",
            answered.toString().contains("edit_form operation=remove_item"));
    }

    /**
     * A template is reached through a different workshop depending on what it holds, so naming one
     * of them would be a guess presented as an instruction.
     */
    @Test
    public void aTemplateIsRefusedAndBothWorkshopsAreNamed()
    {
        JsonObject answered = removeFrom("Catalog.Товары.Template.Печать");
        isRefusal(answered);
        String said = answered.toString();
        assertTrue("a composition schema is reached through its own workshop",
            said.contains("dcs_workshop"));
        assertTrue("a spreadsheet document is reached through another",
            said.contains("mxl_workshop"));
    }

    /**
     * The name alone does not say whether it is an attribute or a tabular section, and the two have
     * separate operations. Both are named, with what tells them apart.
     */
    @Test
    public void aMetadataObjectIsRefusedAndBothOperationsAreNamed()
    {
        JsonObject answered = removeFrom("Catalog.Товары");
        isRefusal(answered);
        String said = answered.toString();
        assertTrue(said.contains("remove_object_attribute"));
        assertTrue(said.contains("remove_tabular_section"));
    }

    /**
     * A catalogue whose name contains a kind word is a catalogue. The substring search read this
     * one as a composition schema and named the wrong workshop.
     */
    @Test
    public void anObjectNamedAfterAKindGetsTheObjectAnswer()
    {
        JsonObject answered = removeFrom("Catalog.TemplateSettings");
        isRefusal(answered);
        String said = answered.toString();
        assertTrue("this is a catalogue, not a template",
            said.contains("remove_object_attribute"));
        assertFalse("naming a workshop here sends the caller nowhere",
            said.contains("dcs_workshop"));
    }

    /** A missing argument was already a refusal, and stays one. */
    @Test
    public void aCallWithoutAContainerIsStillRefused()
    {
        Map<String, String> params = new HashMap<>();
        params.put("name", "Строки");
        JsonObject answered =
            JsonParser.parseString(new MiscOps().opRemoveItem(params)).getAsJsonObject();
        assertFalse(answered.has("success") && answered.get("success").getAsBoolean());
    }
}
