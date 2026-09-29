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
     * <p>
     * Raised from 168000 to 168428 for length, precision, fractionDigits, nonNegative,
     * dateFractions and allowedLength on dcs_workshop: add_parameter, set_parameter and add_field
     * write them into the value type.
     * </p>
     * <p>
     * Raised from 168428 to 168575 for writeStub on edit_metadata: add_form_event_handler and
     * add_command_handler append the handler procedure to the form module unless the call turns
     * that off.
     * </p>
     * <p>
     * Raised from 168575 to 169962 for export_database_configuration and
     * export_database_extension on config_io: two operations, their overwrite and allowOutOfSync
     * arguments, and the description sentence that an old file can never pass as the result.
     * </p>
     * <p>
     * Raised from 169962 to 170444 for valueListAllowed and denyIncompleteValues on dcs_workshop
     * and expression, valueListAllowed and denyIncompleteValues on edit_metadata: add_parameter
     * and add_schema_parameter write them into the schema parameter.
     * </p>
     * <p>
     * Raised from 173621 to 173715 for addressingRegister, addressingAttributes,
     * mainAddressingAttribute and currentPerformer on edit_metadata: set_task_addressing writes
     * the addressing of a Task.
     * </p>
     * <p>
     * Raised from 169962 to 170245 for itemNames, field and index on edit_metadata:
     * add_form_appearance_rule, list_form_appearance_rules and remove_form_appearance_rule.
     * </p>
     * <p>
     * Raised from 170444 to 170459 for format on security_audit: markdown applies to
     * audit_role_rights in mode=rights only.
     * </p>
     * <p>
     * Raised from 170459 to 170725 for the security_audit orphan mode contract and its boolean
     * apply argument.
     * </p>
     * <p>
     * Raised from 168575 to 168736 for normalizeInvalidCharacters on write_module_source: the
     * module writer replaces the characters BSL has no place for unless the call turns that off.
     * </p>
     * <p>
     * Raised from 170444 to 170476 for the docs_lookup description, which names system_enum_values
     * among the operations it accepts and no longer counts them.
     * </p>
     * <p>
     * Raised from 173621 to 174078 for reuseRecent on the yaxunit_tests facade and for the
     * sentences that say what a call does about the infobase before it launches: the flag that
     * takes a report of a run finished within the last five minutes, and the refusal a call gets
     * when the update it asked for does not finish.
     * </p>
     * <p>
     * Raised from 174185 to 174710 for reportAffectedSettings on dcs_workshop: a removal lists the
     * settings that still reference what was removed.
     * </p>
     * <p>
     * Raised from 174835 to 175155 for breakpointEnabled on set_breakpoint_state and replaceModuleSet
     * on add_breakpoint, and for the two operation names the launch_debugger description carries.
     * </p>
     * <p>
     * And from 173621 to 173735 for the data-loss protection of update_database - protectData and
     * acceptDataLoss in its own schema and the facade's - and the inspect_database_sync
     * operation of infobase_admin.
     * </p>
     * <p>
     * And from 173735 to 173958 for the reworked data-loss protection: the descriptions of
     * protectData and acceptDataLoss now say the comparison is made before the update starts and
     * answers dataLossTables, and the help names acceptDataLoss beside protectData (measured
     * 29.09).
     * </p>
     * <p>
     * Raised from 176567 to 178350 for show_file_changes and revert_file on git: the file path,
     * the two revisions, the granularity and the dry-run flag. The git entry grew from 1330 to
     * 3113 bytes and nothing else in the list changed.
     * </p>
     * <p>
     * Raised from 178350 to 178385 for the line endings revert_file names, the editor note on
     * dryRun and the file limit of show_file_changes. Measured 29.09: 178385.
     * </p>
     * <p>
     * Raised from 178350 to 178983 on 2026-09-29 for the merge restore point: the two git
     * operations and the sentences on compare_three_way and insights that name it.
     * </p>
     * <p>
     * And for backupTo on infobase_admin, the path, timeoutSeconds, runKey and cancel sentences
     * that name the two snapshot operations, and the schema description that says a load
     * replaces everything the infobase holds. Measured 29.09: 181766.
     * </p>
     * <p>
     * And for retrieve_database_changes on the infobase_admin schema; the hidden sync_control alias
     * is not in tools/list, so the document moves by this facade only. Measured 29.09: 182933.
     * </p>
     */
    private static final int DOCUMENT_BUDGET = 182933;

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
        // git: the repository answers inside the IDE through the JGit that EDT ships - status,
        // branches and log carry the work tree, the index and the recent history, so a caller
        // never rebuilds what git already knows from the disk state.
        // Grown to 1330 for the commit operation: staging by name and a refusal for a blanket
        // add is the whole point of having git inside the IDE, and the schema is where a client
        // learns that.
        // Grown to 4040 for show_file_changes and revert_file (the file path, the two revisions,
        // the line-or-method granularity, the dry-run flag, the file limit, the line endings) and
        // for create_merge_restore_point and restore_merge_point with pointId (measured 29.09: 4040).
        BUDGETS.put("git", Integer.valueOf(4040)); //$NON-NLS-1$
        BUDGETS.put("compare_three_way", Integer.valueOf(10227)); //$NON-NLS-1$
        BUDGETS.put("insights", Integer.valueOf(9614)); //$NON-NLS-1$
        // Raised from 8523 to 9341 for reportAffectedSettings: removing a dataset, a field or a
        // parameter lists the settings that still reference it, and the schema says so.
        BUDGETS.put("dcs_workshop", Integer.valueOf(9341)); //$NON-NLS-1$
        BUDGETS.put("code_search", Integer.valueOf(7248)); //$NON-NLS-1$
        // Raised by 787 for runMode and clientType on start_client: a configuration on ordinary
        // forms starts under the thick client in the ordinary run mode, and the caller names both.
        // Raised by 707 for register_infobase: an existing infobase is registered in EDT's list
        // and bound to the project in one call, and the facade's schema is where a client learns
        // the operation and its two new arguments exist. And by 407 for the dump-info work: the
        // syncOperation value rebuild_dump_info, the rebuild's wait budget, the format override.
        // Raised to the measured 9864 for the data-loss protection of update_database and
        // inspect_database_sync: protectData now says the comparison is made before the update
        // starts and answers dataLossTables, acceptDataLoss says what it carries through, and the
        // facade's help names acceptDataLoss beside protectData (measured 29.09). The two snapshot
        // operations add backupTo and name themselves for path, timeoutSeconds, runKey and cancel,
        // and retrieve_database_changes adds the operation, replaceLocal, markSynchronized and the
        // pending wait the schema describes.
        BUDGETS.put("infobase_admin", Integer.valueOf(12255)); //$NON-NLS-1$
        BUDGETS.put("write_module_source", Integer.valueOf(6887)); //$NON-NLS-1$
        // Raised by 1803 for export_database_configuration and export_database_extension: the
        // guarded .cf/.cfe dumps declare overwrite and allowOutOfSync in the schema, and the
        // description says an old file can never pass as the result (measured 28.09: 7748).
        BUDGETS.put("config_io", Integer.valueOf(7748)); //$NON-NLS-1$
        // Raised from 5405 by 1755 for the ten list-action arguments, one sentence each, and the
        // sentence in the tool's own description that names the way they compose an action.
        // Raised from 7160 to 7834 for the sentence that a timeout held by a 1C window returns
        // blockingWindows (title, texts, buttons, imageFile) and does not ask to raise the
        // deadline. Measured 29.09: 7834.
        BUDGETS.put("vanessa", Integer.valueOf(7834)); //$NON-NLS-1$
        BUDGETS.put("extension_workshop", Integer.valueOf(5402)); //$NON-NLS-1$
        // Raised from 5400 for startupOption: the startup string is how an object opened at
        // startup receives its parameters, and the test runners already pass theirs the same way.
        // Raised by 1073 for runMode and clientType on launch, the same two arguments start_client
        // takes, so a debug launch of a configuration on ordinary forms opens the same client.
        // Raised by 840 for breakpointEnabled on set_breakpoint_state and replaceModuleSet on
        // add_breakpoint, and for the two operation names the facade description and help now carry
        // (measured 29.09: 7998).
        BUDGETS.put("launch_debugger", Integer.valueOf(7998)); //$NON-NLS-1$
        // The entry names the operations and the model; what each argument takes is the schema's
        // to say. Measured 7813 bytes.
        BUDGETS.put("mxl_workshop", Integer.valueOf(7813)); //$NON-NLS-1$
        BUDGETS.put("diagnostics", Integer.valueOf(3532)); //$NON-NLS-1$
        BUDGETS.put("project_admin", Integer.valueOf(3281)); //$NON-NLS-1$
        BUDGETS.put("edit_form", Integer.valueOf(3168)); //$NON-NLS-1$
        BUDGETS.put("external_data_source_workshop", Integer.valueOf(2954)); //$NON-NLS-1$
        BUDGETS.put("support_registry", Integer.valueOf(2831)); //$NON-NLS-1$
        BUDGETS.put("xdto_workshop", Integer.valueOf(2742)); //$NON-NLS-1$
        BUDGETS.put("external_object_workshop", Integer.valueOf(2602)); //$NON-NLS-1$
        BUDGETS.put("docs_lookup", Integer.valueOf(2547)); //$NON-NLS-1$
        BUDGETS.put("validate_query", Integer.valueOf(2460)); //$NON-NLS-1$
        // Raised from 2395 to 2639 for reuseRecent, its sentence, and the sentences that say the
        // infobase is updated before the launch and that a refused update starts nothing. The two
        // hidden aliases carry the same declarations and are not weighed: tools/list under the
        // default preset does not advertise them. Measured 29.09: 2639.
        BUDGETS.put("yaxunit_tests", Integer.valueOf(2639)); //$NON-NLS-1$
        // Raised from 2295 by 713: the audit advertises scope, moduleFqn, methodName and
        // subsystemName, one sentence each. The sentences that say which combination is a walk
        // and which is a refusal live in operation help, not in this schema. 3008 is what remains
        // after that move (the four sentences are 235 bytes; the rest is the properties themselves).
        // Raised from 3008 to 3274 for the complete orphan mode contract and apply property.
        BUDGETS.put("security_audit", Integer.valueOf(3274)); //$NON-NLS-1$
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
        // First weigh of naparnik: status, help and probe. Raised by 122 for the sentence that
        // keeps service knowledge-base reads (mcp__knowledge-hub__ Search_, Fetch_, Diff_ or
        // Get_) in the read set. The sentence is the tool description.
        BUDGETS.put("naparnik", Integer.valueOf(1922)); //$NON-NLS-1$
        BUDGETS.put("get_mcp_history", Integer.valueOf(676)); //$NON-NLS-1$
        BUDGETS.put("get_edt_version", Integer.valueOf(123)); //$NON-NLS-1$
    }
}
