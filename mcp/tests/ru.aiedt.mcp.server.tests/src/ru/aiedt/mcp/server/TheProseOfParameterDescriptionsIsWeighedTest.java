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
     * <p>
     * Raised for edit_metadata from 12715 by 538 for two arguments whose values were already read
     * but never advertised: commandParameterType, which the string setter accepted and the .mdo
     * never received (measured on the stand 12.09), and dataObjectName, which the root dataset
     * accepted and dropped the same way. An argument a strict client cannot see is a promise the
     * tool cannot keep.
     * </p>
     * <p>
     * Raised again by 477 for the three arguments of a conditional appearance the alias read
     * without advertising: conditionType, conditionValue and appearance. The alias and the workshop
     * read the same call, and a client building from the facade's schema could name none of them.
     * </p>
     * <p>
     * And by 543 for naming the appearance's field in the workshop's own schema - the one argument
     * the workshop read from the very beginning without telling the strict client it existed.
     * </p>
     * <p>
     * Raised for infobase_admin by 527 for statusOnly: a caller asking whether an update is still
     * going had no way to ask - a call without a runKey starts one (measured on the stand 15.09).
     * The read is advertised on both surfaces the update is launched through, or the run is
     * visible and the way to look at it is not.
     * </p>
     * <p>
     * And by 202 for naming the update_database read in the standalone schema too - the two
     * surfaces describe the same tool, and a client that can see one must see the other.
     * </p>
     * <p>
     * And by 606 for waitForEndpoint across the four launch surfaces: a process that exists is
     * not an opened processor that answers, and the wait is the only way to tell them apart - the
     * measure the release itself runs with an MCP Toolkit.
     * </p>
     * <p>
     * And by 86 for refreshWorkspace on the facade: a file written outside this server - a file
     * tool, git checkout, a pull - is invisible to the model the update state is read from, and
     * without the argument a caller who knows every change went through this server has no way to
     * skip the walk.
     * </p>
     * <p>
     * And by 544 on infobase_admin and 532 on launch_debugger for runMode and clientType across
     * the four launch schemas: a configuration on ordinary forms starts under the thick client in
     * the ordinary run mode, and the one mode the platform reads is the last one on the command
     * line, so the caller names the mode and the client and the launch puts them where they are
     * read. A client that cannot see the two arguments starts the managed client every time.
     * </p>
     * <p>
     * And by 330 on infobase_admin for connectionString and makeDefault: a caller registering an
     * existing server infobase has no way to name the server and the infobase without the string,
     * and no way to say whether the new application becomes the project's default without the
     * flag - both are the whole call. Their rules live in the operation help.
     * </p>
     */
    private static final Map<String, Integer> PROSE = new HashMap<>();
    static
    {
        PROSE.put("edit_metadata", Integer.valueOf(13730));
        // git: operation, projectName and the log's limit are the whole surface a client builds
        // the call from - the repository answers the rest. Grown to 875 for the commit's five
        // arguments (paths by name - there is no add-all - the message, and the author pair a
        // repository without its own configuration needs) and the checkout's two (the branch,
        // and whether to create it).
        PROSE.put("git", Integer.valueOf(875));
        PROSE.put("compare_three_way", Integer.valueOf(3558));
        // Raised from 5393 by 52: detect_query_anti_patterns names methodName, one sentence.
        // The combination rules (what walks, what is refused) moved to operation help.
        PROSE.put("insights", Integer.valueOf(5445));
        PROSE.put("dcs_workshop", Integer.valueOf(4249));
        PROSE.put("write_module_source", Integer.valueOf(4985));
        PROSE.put("code_search", Integer.valueOf(3357));
        // Raised by 330 for connectionString and makeDefault: registering an existing server
        // infobase is named by the string, and the flag says whether it becomes the default.
        PROSE.put("infobase_admin", Integer.valueOf(5230));
        PROSE.put("config_io", Integer.valueOf(3064));
        PROSE.put("vanessa", Integer.valueOf(3607));
        PROSE.put("extension_workshop", Integer.valueOf(3269));
        // Raised from 2710 for three arguments the debugger gained in 0.2.49, each of which a
        // caller cannot reach without being told it exists: the debug server port (on a
        // machine with several environments the default one is taken and the launch is
        // refused by a dialog), building the external object (without it the client starts
        // empty, measured 17.09), and the ceiling on the wait for a breakpoint.
        // Raised by 654 for startupOption (the /C string is how an opened object receives its
        // parameters - the test runners already pass theirs the same way) and for waitForEndpoint:
        // a process that exists is not an opened processor that answers, and the wait is the only
        // way to tell them apart.
        PROSE.put("launch_debugger", Integer.valueOf(4186));
        PROSE.put("mxl_workshop", Integer.valueOf(2164));
        PROSE.put("diagnostics", Integer.valueOf(2058));
        PROSE.put("edit_form", Integer.valueOf(2024));
        PROSE.put("project_admin", Integer.valueOf(1767));
        PROSE.put("docs_lookup", Integer.valueOf(1500));
        PROSE.put("support_registry", Integer.valueOf(1435));
        PROSE.put("external_object_workshop", Integer.valueOf(1429));
        PROSE.put("validate_query", Integer.valueOf(1319));
        PROSE.put("external_data_source_workshop", Integer.valueOf(1265));
        // Raised from 1170 by 235: find_rls_violations and sensitive_data_scan advertise scope,
        // moduleFqn, methodName and subsystemName, one sentence each. The sentences that say
        // which combination is a walk and which is a refusal moved to operation help.
        PROSE.put("security_audit", Integer.valueOf(1405));
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
        // naparnik: operation and probe. probe says that the call starts the Naparnik UI bundle.
        PROSE.put("naparnik", Integer.valueOf(216));
        PROSE.put("get_mcp_history", Integer.valueOf(145));
        PROSE.put("get_command_interface", Integer.valueOf(98));
        PROSE.put("get_edt_version", Integer.valueOf(0));
        PROSE.put("self_status", Integer.valueOf(0));
    }

    /**
     * The whole document's parameter prose, from the same run.
     * <p>
     * Raised three times, each for a contract a caller has to read without asking. By 94 bytes, for
     * the batch argument to state that an operation or argument name that does not exist stops the
     * whole batch before anything runs - it changes what a failed call leaves behind. By 210, for
     * three facades whose descriptions were cut to name once where the rest of them now is: a
     * description that stops at one sentence is honest only while the address of the rest is
     * reachable. And by 68, for the dryRun of update_database, which is how a caller asks what an
     * update would face without starting one.
     * </p>
     * <p>
     * Raised a fourth time, by 124, for objectFqn. delete_metadata_object refuses without it and
     * names it in the refusal, while the schema described the argument for adopt_* alone - so the
     * one instruction the refusal gives could not be followed from what the schema said.
     * </p>
     * <p>
     * And a fifth, by 407, for two capabilities a caller cannot reach without being told they
     * exist: the external object the debugged client opens at startup, which is how code under a
     * breakpoint gets run at all, and the button of the modal dialog EDT is waiting on, which is
     * how a call blocked behind a question goes on without a person at the keyboard.
     * </p>
     * <p>
     * And a sixth, by 160, for the debug server port. A machine running several environments has one
     * default port between them, and the second environment to debug is refused by a dialog of its
     * own - a state no argument could get out of until this one. Measured 16.09 on this machine:
     * port 1550 held by a neighbouring workspace, and the launch unreachable for the rest of the
     * session.
     * </p>
     * <p>
     * And a seventh, by 240, for entryId. The buffer keeps a few hundred characters of each call
     * because it lives in the IDE's own heap; the window that shows a call is opened to read what
     * the tool answered, and until this argument there was nowhere to read it from. Measured from a
     * screenshot 16.09: "Response (303 of 1557 characters)".
     * </p>
     * <p>
     * And an eighth, by 165, for formName and for saying what itemName does. Measured on the stand
     * 17.09: borrow_form_item answered "already borrowed" about the CATALOG while the caller had
     * named an item of its form, because a form item has no address of its own. Naming the form is
     * how the call stops borrowing the owner by accident.
     * </p>
     * <p>
     * And a ninth, by 120, for enableExternalObjectDump. Measured on the stand 17.09: the client
     * started and the external object was not in it, because the environment builds such an object
     * before opening it and the project had that switched off. Without the argument the only way out
     * of that state is the project's properties page.
     * </p>
     * <p>
     * And an eleventh, by 506, for the three DCS arguments whose values were already read and
     * dropped on one of their routes: dataObjectName on the root dataset, dataPath on a total, and
     * the field of a conditional appearance. Each is named in the schema of both surfaces that
     * build such a call - the value a strict client can see is the only value it can give.
     * </p>
     * <p>
     * And a tenth, by 322, for startupOption across the four launch schemas. The startup string is
     * how an external processor or report opened at startup receives its parameters - the test
     * runners already pass theirs the same way, and a client that must not be restarted just to
     * change one string has no other way in. Four schemas advertise it because an argument present
     * in the code and missing from one of them is a contract the strict client cannot call.
     * </p>
     * <p>
     * And a twelfth, by 176, for positions on content_assist: a survey over hundreds of positions is
     * one call rather than hundreds, and the batch form is the argument that carries it - a client
     * that cannot see it builds the survey call by call.
     * </p>
     * <p>
     * And a thirteenth, by 875, for git: the repository answers inside the IDE what a shell would
     * answer outside it - the work tree, the index, the recent history, a commit of named paths
     * and a switch of branch - and the operation, the project, the paths, the message, the
     * author pair, the branch and its creation flag are what a caller has to name to get there.
     * </p>
     * <p>
     * And a fourteenth, by 1076, for runMode and clientType on the four launch schemas: the run
     * mode and the client a configuration on ordinary forms starts under, named by the caller
     * because the platform reads the last mode flag on the command line and the launch puts the
     * caller's there.
     * </p>
     * <p>
     * And a fifteenth, by 287, for the one sentence each selector a query, metrics or security
     * scan is narrowed by. The sentences that refuse a selector the scope does not accept moved
     * to operation help. Measured at 87113.
     * </p>
     * <p>
     * And a sixteenth, by 216, for naparnik: the operation, and probe, which starts the Naparnik
     * UI bundle. Together with the fifteenth: 87329.
     * </p>
     * <p>
     * And a seventeenth, by 1800, for find on the twelve facades whose help it searches: an agent
     * that cannot see the argument reads the whole catalog and every topic to answer "where does
     * this facade say X". Each copy is one shared sentence
     * ({@code FacadeHelpSearch.FIND_DESCRIPTION}, 150 bytes), the rest of the rule having been cut
     * to keep this raise the remainder it is. Together with the sixteenth: 89129.
     * </p>
     * <p>
     * And an eighteenth, by 164, for one sentence each on includeChildren (extension_workshop,
     * 69 bytes) and edgeKinds (insights, 95 bytes). What is walked, what is skipped, which
     * graph edges are dropped and how a kind filter answers live in operation help. Together
     * with the seventeenth: 89293.
     * </p>
     * <p>
     * And a nineteenth, by 177, for connectionString and makeDefault on infobase_admin: an
     * existing server infobase is named by the connection string and the flag says whether it
     * becomes the project's default application. Together with the eighteenth: 89470.
     * </p>
     */
    private static final int DOCUMENT_PROSE = 89470;
}
