/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What the parameter descriptions alone weigh in {@code tools/list}, per tool and in total.
 * <p>
 * The sibling budget weighs each tool entry whole - names, types, required, the tool's own
 * description and the prose of its parameters together. That total is the right thing to hold
 * against creep, and the wrong thing to measure a cut by: the prose is the only part a cut may
 * touch, and a tool entry that grew a parameter while losing a sentence reads as unchanged.
 * </p>
 * <p>
 * Measured 12.09 on a running server: the descriptions of parameters are the largest single part of
 * the document, and they sit in the middle of the length distribution rather than in a few long
 * ones. Moving the longest into per-operation help reaches a fraction of them; the rest is a
 * revision of several hundred sentences. Which of those is worth doing is a decision, and this
 * exists so the decision is taken against a number and its effect can be seen afterwards.
 * </p>
 * <p>
 * A number here only ever goes down. Raising one is a decision with its reason in the commit that
 * does it.
 * </p>
 */
public class TheProseOfParameterDescriptionsIsWeighedTest
{
    /**
     * Not zero: a sentence is rewritten for clarity all the time, and a check that fires on a
     * rewrite is a check somebody switches off.
     */
    private static final int HEADROOM = 400;

    private LiveServer server;

    @After
    public void stopTheServer()
    {
        if (server != null)
        {
            server.close();
        }
    }

    @Test
    public void noToolsParameterProseIsHeavierThanItWas() throws IOException
    {
        Map<String, Integer> prose = weighProse();
        assertFalse("tools/list answered with no tools at all", prose.isEmpty());

        List<String> grown = new ArrayList<>();
        int document = 0;
        for (Map.Entry<String, Integer> entry : prose.entrySet())
        {
            document += entry.getValue().intValue();
            Integer budget = PROSE.get(entry.getKey());
            if (budget == null)
            {
                grown.add(entry.getKey() + " is not weighed yet (" + entry.getValue()
                    + " bytes of parameter prose) - add it");
            }
            else if (entry.getValue().intValue() > budget.intValue() + HEADROOM)
            {
                grown.add(entry.getKey() + " grew to " + entry.getValue() + " bytes of parameter "
                    + "prose, past " + budget + " + " + HEADROOM);
            }
        }
        assertTrue(String.join("\n", grown), grown.isEmpty());
        assertTrue("the parameter descriptions weigh " + document + " bytes, past "
            + DOCUMENT_PROSE, document <= DOCUMENT_PROSE);
    }

    @Test
    public void theProseIsAMeasurablePartOfTheDocument()
    {
        // Without this the test above could pass over a measurement that had quietly become zero -
        // a schema shape this stopped recognising reads exactly like prose that went away.
        int weighed = 0;
        for (Integer bytes : PROSE.values())
        {
            weighed += bytes.intValue();
        }
        assertTrue("the recorded prose adds up to " + weighed + " bytes, which is too little to "
            + "be the parameter descriptions of " + PROSE.size() + " tools", weighed > 50000);
    }

    /** Every {@code description} inside a schema's properties, however deeply nested. */
    private static int proseOf(JsonElement element)
    {
        int bytes = 0;
        if (element.isJsonObject())
        {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> member : object.entrySet())
            {
                if ("description".equals(member.getKey()) && member.getValue().isJsonPrimitive())
                {
                    bytes += member.getValue().getAsString().getBytes(StandardCharsets.UTF_8).length;
                }
                else
                {
                    bytes += proseOf(member.getValue());
                }
            }
        }
        else if (element.isJsonArray())
        {
            for (JsonElement each : element.getAsJsonArray())
            {
                bytes += proseOf(each);
            }
        }
        return bytes;
    }

    private Map<String, Integer> weighProse() throws IOException
    {
        Map<String, Integer> weights = new TreeMap<>();
        for (JsonElement element : askForTools())
        {
            JsonObject tool = element.getAsJsonObject();
            // The schema only. A tool's own description is prose too, and it is not what a cut of
            // parameter documentation touches - counting it here would move this number for a
            // reason the number is not about.
            JsonElement schema = tool.get("inputSchema");
            weights.put(tool.get("name").getAsString(),
                Integer.valueOf(schema == null ? 0 : proseOf(schema)));
        }
        return weights;
    }

    private JsonArray askForTools() throws IOException
    {
        if (server == null)
        {
            server = LiveServer.start();
        }
        Map<String, String> headers = LiveServer.headers(
            "Content-Type", "application/json",
            "Accept", "application/json",
            "Authorization", server.bearer());
        LiveServer.Response answer = server.request("POST", "/mcp", headers,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        JsonObject document = JsonParser.parseString(answer.body).getAsJsonObject();
        return document.getAsJsonObject("result").getAsJsonArray("tools");
    }

    /**
     * What each advertised tool's parameter descriptions weighed when this was written, in UTF-8
     * bytes. Filled from the failure of the first run rather than estimated.
     */
    private static final Map<String, Integer> PROSE = new HashMap<>();
    static
    {
        PROSE.put("edit_metadata", Integer.valueOf(17610));
        PROSE.put("compare_three_way", Integer.valueOf(7967));
        PROSE.put("insights", Integer.valueOf(6894));
        PROSE.put("dcs_workshop", Integer.valueOf(5176));
        PROSE.put("write_module_source", Integer.valueOf(4985));
        PROSE.put("code_search", Integer.valueOf(4701));
        PROSE.put("infobase_admin", Integer.valueOf(4560));
        PROSE.put("config_io", Integer.valueOf(3771));
        PROSE.put("vanessa", Integer.valueOf(3607));
        PROSE.put("extension_workshop", Integer.valueOf(3269));
        PROSE.put("launch_debugger", Integer.valueOf(2710));
        PROSE.put("mxl_workshop", Integer.valueOf(2164));
        PROSE.put("diagnostics", Integer.valueOf(2058));
        PROSE.put("edit_form", Integer.valueOf(2024));
        PROSE.put("project_admin", Integer.valueOf(1767));
        PROSE.put("docs_lookup", Integer.valueOf(1500));
        PROSE.put("support_registry", Integer.valueOf(1435));
        PROSE.put("external_object_workshop", Integer.valueOf(1429));
        PROSE.put("validate_query", Integer.valueOf(1319));
        PROSE.put("external_data_source_workshop", Integer.valueOf(1265));
        PROSE.put("security_audit", Integer.valueOf(1170));
        PROSE.put("xdto_workshop", Integer.valueOf(1155));
        PROSE.put("get_form_screenshot", Integer.valueOf(1154));
        PROSE.put("code_review", Integer.valueOf(1129));
        PROSE.put("get_metadata_objects", Integer.valueOf(1090));
        PROSE.put("yaxunit_tests", Integer.valueOf(1077));
        PROSE.put("diff_module", Integer.valueOf(960));
        PROSE.put("find_dead_code", Integer.valueOf(942));
        PROSE.put("workspace_marks", Integer.valueOf(880));
        PROSE.put("get_metadata_details", Integer.valueOf(867));
        PROSE.put("read_event_log", Integer.valueOf(633));
        PROSE.put("get_form_structure", Integer.valueOf(614));
        PROSE.put("list_modules", Integer.valueOf(587));
        PROSE.put("ai_context", Integer.valueOf(581));
        PROSE.put("copy_object", Integer.valueOf(545));
        PROSE.put("marker_corrections", Integer.valueOf(434));
        PROSE.put("read_module_source", Integer.valueOf(374));
        PROSE.put("read_method_source", Integer.valueOf(359));
        PROSE.put("get_module_structure", Integer.valueOf(308));
        PROSE.put("dcs_search", Integer.valueOf(305));
        PROSE.put("generate_event_handlers", Integer.valueOf(299));
        PROSE.put("code_template", Integer.valueOf(171));
        PROSE.put("get_mcp_history", Integer.valueOf(145));
        PROSE.put("get_command_interface", Integer.valueOf(98));
        PROSE.put("get_edt_version", Integer.valueOf(0));
        PROSE.put("self_status", Integer.valueOf(0));
    }

    /** The whole document's parameter prose, from the same run. */
    private static final int DOCUMENT_PROSE = 96088;
}
