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

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


/**
 * What {@code tools/list} costs, per tool, and a ratchet so it does not creep back.
 * <p>
 * The document is sent to a client at the start of every session, before a single tool is called,
 * so its size is a fixed toll on every conversation. Measured on a running server: 46 tools,
 * roughly 160 KB, of which around 60 per cent is the prose describing parameters rather than the
 * names, types and required flags a strict client actually needs.
 * </p>
 * <p>
 * The budget is per tool and not only a total, because one total hides the thing worth catching: a
 * tool that grows behind a tool that shrank. A tool over its budget fails here; a tool under it
 * asks for the budget to be lowered, so ground that was won is held rather than quietly given back.
 * </p>
 */
public class TheToolListHasAWeightBudgetTest
{
    /**
     * Room above the measured weight, in bytes, before a tool is called grown.
     * <p>
     * Not zero: a description is edited for clarity all the time, and a check that fires on a
     * comma is a check people route around. Wide enough for wording, narrow enough that a new
     * parameter with a paragraph of prose does not fit.
     * </p>
     */
    private static final int HEADROOM = 600;

    /**
     * What the whole document may weigh. Measured at 160766 bytes; the allowance is the per-tool
     * headroom spent a few times over, not a free hand.
     */
    private static final int DOCUMENT_BUDGET = 168000;

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
    public void noToolIsHeavierThanItsBudgetAndTheDocumentFitsToo() throws IOException
    {
        Map<String, Integer> weights = weighEveryTool();
        assertFalse("tools/list answered with no tools at all", weights.isEmpty()); //$NON-NLS-1$

        List<String> grown = new ArrayList<>();
        int document = 0;
        for (Map.Entry<String, Integer> entry : weights.entrySet())
        {
            document += entry.getValue().intValue();
            Integer budget = BUDGETS.get(entry.getKey());
            if (budget == null)
            {
                // A tool nobody weighed yet: recorded rather than waved through, so the budget
                // file keeps up with the catalogue.
                grown.add(entry.getKey() + " is not in the budget (" + entry.getValue() //$NON-NLS-1$
                    + " bytes) - add it"); //$NON-NLS-1$
            }
            else if (entry.getValue().intValue() > budget.intValue() + HEADROOM)
            {
                grown.add(entry.getKey() + " grew to " + entry.getValue() + " bytes, past " //$NON-NLS-1$ //$NON-NLS-2$
                    + budget + " + " + HEADROOM); //$NON-NLS-1$
            }
        }
        assertTrue(String.join("\n", grown), grown.isEmpty()); //$NON-NLS-1$
        assertTrue("tools/list weighs " + document + " bytes, past " + DOCUMENT_BUDGET, //$NON-NLS-1$ //$NON-NLS-2$
            document <= DOCUMENT_BUDGET);
    }

    @Test
    public void everyBudgetedToolIsStillAdvertised() throws IOException
    {
        // The other half of the ratchet. Weight that falls because a tool disappeared from the
        // catalogue is not weight that was saved, and it must not be read as progress.
        Map<String, Integer> weights = weighEveryTool();
        List<String> gone = new ArrayList<>();
        for (String name : BUDGETS.keySet())
        {
            if (!weights.containsKey(name))
            {
                gone.add(name);
            }
        }
        assertTrue("budgeted but no longer advertised: " + String.join(", ", gone), //$NON-NLS-1$ //$NON-NLS-2$
            gone.isEmpty());
    }

    @Test
    public void everyAdvertisedToolStillDeclaresItsParametersWithTypes() throws IOException
    {
        // What the budget must never be met by. A strict client drops a parameter that is not in
        // the schema, so cutting prose is allowed and cutting the parameter, its type or its
        // required flag is not.
        JsonArray tools = askForTools();
        List<String> bare = new ArrayList<>();
        for (JsonElement element : tools)
        {
            JsonObject tool = element.getAsJsonObject();
            JsonObject schema = tool.has("inputSchema") //$NON-NLS-1$
                ? tool.getAsJsonObject("inputSchema") : null; //$NON-NLS-1$
            if (schema == null || !schema.has("properties")) //$NON-NLS-1$
            {
                continue;
            }
            JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
            for (Map.Entry<String, JsonElement> property : properties.entrySet())
            {
                JsonObject declared = property.getValue().getAsJsonObject();
                if (!declared.has("type") && !declared.has("enum") && !declared.has("anyOf") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    && !declared.has("oneOf")) //$NON-NLS-1$
                {
                    bare.add(tool.get("name").getAsString() + "." + property.getKey()); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        assertTrue("parameters advertised without a type: " + String.join(", ", bare), //$NON-NLS-1$ //$NON-NLS-2$
            bare.isEmpty());
    }

    private JsonArray askForTools() throws IOException
    {
        if (server == null)
        {
            server = LiveServer.start();
        }
        Map<String, String> headers = LiveServer.headers(
            "Content-Type", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Accept", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Authorization", server.bearer()); //$NON-NLS-1$
        LiveServer.Response answer = server.request("POST", "/mcp", headers, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"); //$NON-NLS-1$
        JsonObject document = JsonParser.parseString(answer.body).getAsJsonObject();
        return document.getAsJsonObject("result").getAsJsonArray("tools"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Map<String, Integer> weighEveryTool() throws IOException
    {
        Map<String, Integer> weights = new HashMap<>();
        for (JsonElement element : askForTools())
        {
            JsonObject tool = element.getAsJsonObject();
            weights.put(tool.get("name").getAsString(), //$NON-NLS-1$
                Integer.valueOf(tool.toString().getBytes(StandardCharsets.UTF_8).length));
        }
        return weights;
    }

    /**
     * What each advertised tool weighed when the budget was written, in UTF-8 bytes.
     * <p>
     * Measured on a running server, not estimated. A number here only ever goes down: lowering one
     * after a cut is what holds the ground, and raising one is a decision with a reason in the
     * commit that does it.
     * </p>
     */
    private static final Map<String, Integer> BUDGETS = new HashMap<>();

    static
    {
        BUDGETS.put("edit_metadata", Integer.valueOf(24778)); //$NON-NLS-1$
        BUDGETS.put("compare_three_way", Integer.valueOf(10227)); //$NON-NLS-1$
        BUDGETS.put("insights", Integer.valueOf(9614)); //$NON-NLS-1$
        BUDGETS.put("dcs_workshop", Integer.valueOf(8523)); //$NON-NLS-1$
        BUDGETS.put("code_search", Integer.valueOf(7248)); //$NON-NLS-1$
        BUDGETS.put("infobase_admin", Integer.valueOf(7155)); //$NON-NLS-1$
        BUDGETS.put("write_module_source", Integer.valueOf(6887)); //$NON-NLS-1$
        BUDGETS.put("config_io", Integer.valueOf(5945)); //$NON-NLS-1$
        BUDGETS.put("vanessa", Integer.valueOf(5405)); //$NON-NLS-1$
        BUDGETS.put("extension_workshop", Integer.valueOf(5402)); //$NON-NLS-1$
        BUDGETS.put("launch_debugger", Integer.valueOf(5154)); //$NON-NLS-1$
        BUDGETS.put("mxl_workshop", Integer.valueOf(4636)); //$NON-NLS-1$
        BUDGETS.put("diagnostics", Integer.valueOf(3532)); //$NON-NLS-1$
        BUDGETS.put("project_admin", Integer.valueOf(3281)); //$NON-NLS-1$
        BUDGETS.put("edit_form", Integer.valueOf(3168)); //$NON-NLS-1$
        BUDGETS.put("external_data_source_workshop", Integer.valueOf(2954)); //$NON-NLS-1$
        BUDGETS.put("support_registry", Integer.valueOf(2831)); //$NON-NLS-1$
        BUDGETS.put("xdto_workshop", Integer.valueOf(2742)); //$NON-NLS-1$
        BUDGETS.put("external_object_workshop", Integer.valueOf(2602)); //$NON-NLS-1$
        BUDGETS.put("docs_lookup", Integer.valueOf(2547)); //$NON-NLS-1$
        BUDGETS.put("validate_query", Integer.valueOf(2460)); //$NON-NLS-1$
        BUDGETS.put("yaxunit_tests", Integer.valueOf(2395)); //$NON-NLS-1$
        BUDGETS.put("security_audit", Integer.valueOf(2295)); //$NON-NLS-1$
        BUDGETS.put("code_review", Integer.valueOf(2209)); //$NON-NLS-1$
        BUDGETS.put("find_dead_code", Integer.valueOf(1867)); //$NON-NLS-1$
        BUDGETS.put("diff_module", Integer.valueOf(1816)); //$NON-NLS-1$
        BUDGETS.put("get_metadata_objects", Integer.valueOf(1802)); //$NON-NLS-1$
        BUDGETS.put("workspace_marks", Integer.valueOf(1771)); //$NON-NLS-1$
        BUDGETS.put("get_form_screenshot", Integer.valueOf(1748)); //$NON-NLS-1$
        BUDGETS.put("read_event_log", Integer.valueOf(1673)); //$NON-NLS-1$
        BUDGETS.put("get_metadata_details", Integer.valueOf(1613)); //$NON-NLS-1$
        BUDGETS.put("copy_object", Integer.valueOf(1420)); //$NON-NLS-1$
        BUDGETS.put("get_form_structure", Integer.valueOf(1359)); //$NON-NLS-1$
        BUDGETS.put("marker_corrections", Integer.valueOf(1318)); //$NON-NLS-1$
        BUDGETS.put("ai_context", Integer.valueOf(1252)); //$NON-NLS-1$
        BUDGETS.put("list_modules", Integer.valueOf(1210)); //$NON-NLS-1$
        BUDGETS.put("dcs_search", Integer.valueOf(1156)); //$NON-NLS-1$
        BUDGETS.put("read_method_source", Integer.valueOf(1027)); //$NON-NLS-1$
        BUDGETS.put("generate_event_handlers", Integer.valueOf(930)); //$NON-NLS-1$
        BUDGETS.put("read_module_source", Integer.valueOf(852)); //$NON-NLS-1$
        BUDGETS.put("get_module_structure", Integer.valueOf(818)); //$NON-NLS-1$
        BUDGETS.put("get_command_interface", Integer.valueOf(790)); //$NON-NLS-1$
        BUDGETS.put("self_status", Integer.valueOf(762)); //$NON-NLS-1$
        BUDGETS.put("code_template", Integer.valueOf(746)); //$NON-NLS-1$
        BUDGETS.put("get_mcp_history", Integer.valueOf(676)); //$NON-NLS-1$
        BUDGETS.put("get_edt_version", Integer.valueOf(123)); //$NON-NLS-1$
    }
}
