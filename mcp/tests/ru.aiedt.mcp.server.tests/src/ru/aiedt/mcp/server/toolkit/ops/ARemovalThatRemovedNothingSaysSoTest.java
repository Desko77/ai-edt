/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.support.UnreadArguments;

/**
 * What {@code remove_item} answers in each context it can be pointed at.
 * <p>
 * A form item is removed: the form comes from {@code formFqn}, or from {@code containerFqn} as its
 * alias, and the call reaches the form model. A template and a metadata object are refused with the
 * operation that removes from them, and never answer {@code success: true} for a removal that did
 * not happen.
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

    /**
     * Asserts that a form removal went past the argument reading and the scope decision to the
     * project lookup, which is the first step that needs a workspace.
     *
     * @param answered the answer of a removal against a project that does not exist
     */
    private static void reachedTheProjectLookup(JsonObject answered)
    {
        String said = answered.toString();
        assertFalse("formFqn is an argument of remove_item", said.contains("is not read"));
        assertFalse("a form is removed from, not refused", said.contains("does not touch one"));
        assertFalse(said.contains("Nothing was removed"));
        assertTrue("the form branch resolves the project next: " + said,
            said.contains("Project not found: 'AnyProject'"));
    }

    @Test
    public void aFormItemIsRemovedFromTheFormNamedByFormFqn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("formFqn", "Catalog.Товары.Form.ФормаСписка.Form");
        params.put("name", "КнопкаПроверки");
        params.put("projectName", "AnyProject");
        reachedTheProjectLookup(
            JsonParser.parseString(new MiscOps().opRemoveItem(params)).getAsJsonObject());
    }

    /** containerFqn names the form too, the same way move_item reads it. */
    @Test
    public void containerFqnIsAnAliasOfFormFqn()
    {
        reachedTheProjectLookup(removeFrom("Catalog.Товары.Form.ФормаСписка.Form"));
    }

    /** A common form is a form, whichever way its FQN is written. */
    @Test
    public void aCommonFormIsRemovedFromAsAForm()
    {
        reachedTheProjectLookup(removeFrom("CommonForm.ПодборТоваров"));
    }

    /** The facade refuses a supplied argument the operation's parameter table does not list. */
    @Test
    public void formFqnIsAParameterOfRemoveItem()
    {
        Map<String, String> params = new HashMap<>();
        params.put("formFqn", "Catalog.Товары.Form.ФормаСписка.Form");
        params.put("name", "КнопкаПроверки");
        params.put("projectName", "AnyProject");
        for (String operation : new String[] { "remove_item", "remove_item_universal" })
        {
            List<String> unread = UnreadArguments.of("EditMetadataTool", operation, params);
            assertTrue(operation + " leaves unread: " + unread, unread.isEmpty());
        }
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
