/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.ops.DcsWorkshopTool;
import ru.aiedt.mcp.server.toolkit.ops.EditMetadataTool;
import ru.aiedt.mcp.server.toolkit.ops.InfobaseAdminFacadeTool;
import ru.aiedt.mcp.server.toolkit.ops.MxlWorkshopTool;

/**
 * Checks the four catalogue records against their pre-cut shape and operation fixtures,
 * and checks that critical conditions and moved guidance remain published.
 */
public class TheWorkshopProseCutKeepsShapeAndConditionsTest
{
    private static final String EDIT_METADATA_BASELINE = "catalog-runtime-baseline-edit-metadata.json"; //$NON-NLS-1$

    private static final String INFOBASE_ADMIN_BASELINE = "catalog-runtime-baseline-infobase-admin.json"; //$NON-NLS-1$

    private static final String DCS_WORKSHOP_BASELINE = "catalog-runtime-baseline-dcs-workshop.json"; //$NON-NLS-1$

    private static final String MXL_WORKSHOP_BASELINE = "catalog-runtime-baseline-mxl-workshop.json"; //$NON-NLS-1$

    private static final String OPERATIONS_FIXTURE = "catalog-cut-operations.json"; //$NON-NLS-1$

    private LiveServer server;

    /**
     * Stops the server the record tests started, when one was started.
     */
    @After
    public void stopTheServer()
    {
        if (server != null)
        {
            server.close();
        }
    }

    /**
     * The records keep every field except the prose: same properties, types, required
     * flags and annotations as before the cut.
     *
     * @throws IOException when the server or a resource cannot be read
     */
    @Test
    public void theCutRecordsKeptEveryNonProseField() throws IOException
    {
        assertShapeKept("edit_metadata", EDIT_METADATA_BASELINE); //$NON-NLS-1$
        assertShapeKept("infobase_admin", INFOBASE_ADMIN_BASELINE); //$NON-NLS-1$
        assertShapeKept("dcs_workshop", DCS_WORKSHOP_BASELINE); //$NON-NLS-1$
        assertShapeKept("mxl_workshop", MXL_WORKSHOP_BASELINE); //$NON-NLS-1$
    }

    /**
     * Every operation the source registered before the cut is still registered in the
     * dispatch registry the tools use, not merely named in a description.
     *
     * @throws Exception when a registry field cannot be read
     */
    @Test
    public void everyOperationStillReachesItsDispatchRegistry() throws Exception
    {
        JsonObject fixture = JsonParser.parseString(readResource(OPERATIONS_FIXTURE))
            .getAsJsonObject();

        assertEquals("edit_metadata changed its dispatch registry", //$NON-NLS-1$
            names(fixture.getAsJsonArray("edit_metadata")), //$NON-NLS-1$
            keysOf(new EditMetadataTool(), "registry")); //$NON-NLS-1$
        assertEquals("infobase_admin changed its routing registry", //$NON-NLS-1$
            names(fixture.getAsJsonArray("infobase_admin")), //$NON-NLS-1$
            keysOf(null, InfobaseAdminFacadeTool.class, "OPS")); //$NON-NLS-1$
        assertEquals("dcs_workshop changed its dispatch registry", //$NON-NLS-1$
            names(fixture.getAsJsonArray("dcs_workshop")), //$NON-NLS-1$
            keysOf(new DcsWorkshopTool(), "OPS")); //$NON-NLS-1$
        assertEquals("mxl_workshop changed its dispatch registry", //$NON-NLS-1$
            names(fixture.getAsJsonArray("mxl_workshop")), //$NON-NLS-1$
            keysOf(null, MxlWorkshopTool.class, "OPS")); //$NON-NLS-1$
    }

    /**
     * Every condition of write, refusal, data loss and permission the pre-cut record
     * carried is still in the record itself.
     *
     * @throws IOException when the server cannot be reached
     */
    @Test
    public void everyCriticalConditionStaysInTheRecord() throws IOException
    {
        List<String> missing = new ArrayList<>();
        missing.addAll(conditionsMissingFrom("edit_metadata", EDIT_METADATA_CRITICAL, //$NON-NLS-1$
            recordText("edit_metadata"))); //$NON-NLS-1$
        missing.addAll(conditionsMissingFrom("infobase_admin", INFOBASE_ADMIN_CRITICAL, //$NON-NLS-1$
            recordText("infobase_admin"))); //$NON-NLS-1$
        missing.addAll(conditionsMissingFrom("dcs_workshop", DCS_WORKSHOP_CRITICAL, //$NON-NLS-1$
            recordText("dcs_workshop"))); //$NON-NLS-1$
        missing.addAll(conditionsMissingFrom("mxl_workshop", MXL_WORKSHOP_CRITICAL, //$NON-NLS-1$
            recordText("mxl_workshop"))); //$NON-NLS-1$

        assertTrue("critical conditions the source published and the record no longer does:\n" //$NON-NLS-1$
            + String.join("\n", missing), missing.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Each detail the cut moved out of the schema really appears in the help topic the
     * schema points the caller to.
     */
    @Test
    public void everyMovedDetailAppearsInHelp()
    {
        List<String> missing = new ArrayList<>();
        missing.addAll(conditionsMissingFrom("edit_metadata", EDIT_METADATA_MOVED, //$NON-NLS-1$
            helpOf(new EditMetadataTool(), null, "workflow", "parameters"))); //$NON-NLS-1$ //$NON-NLS-2$
        missing.addAll(conditionsMissingFrom("infobase_admin", INFOBASE_ADMIN_MOVED, //$NON-NLS-1$
            helpOf(new InfobaseAdminFacadeTool(), null, "workflow", "get_applications", //$NON-NLS-1$ //$NON-NLS-2$
                "start_client", "update_database", "export_database_snapshot", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "restore_database_snapshot", "sync_control"))); //$NON-NLS-1$ //$NON-NLS-2$
        missing.addAll(conditionsMissingFrom("dcs_workshop", DCS_WORKSHOP_MOVED, //$NON-NLS-1$
            helpOf(new DcsWorkshopTool(), null, "workflow", "parameters"))); //$NON-NLS-1$ //$NON-NLS-2$
        missing.addAll(conditionsMissingFrom("mxl_workshop", MXL_WORKSHOP_MOVED, //$NON-NLS-1$
            helpOf(new MxlWorkshopTool(), "workflow")));
        missing.addAll(conditionsMissingFrom("mxl_workshop root", //$NON-NLS-1$
            new String[] {"never text or parameters"}, helpOf(new MxlWorkshopTool(), (String) null))); //$NON-NLS-1$ //$NON-NLS-1$

        assertTrue("details moved out of the schema and not answered by help:\n" //$NON-NLS-1$
            + String.join("\n", missing), missing.isEmpty()); //$NON-NLS-1$
    }

    /**
     * Checks operation spellings, retained examples and the distinction between copying and shifting.
     *
     * @throws IOException when the published catalogue cannot be read
     */
    @Test
    public void movedGuidanceKeepsOperationNamesExamplesAndDirections() throws IOException
    {
        String metadata = helpOf(new EditMetadataTool(), "parameters"); //$NON-NLS-1$
        assertTrue(metadata.contains("remove_data_set, remove_data_set_field, remove_schema_parameter")); //$NON-NLS-1$
        assertTrue(metadata.contains("the dcs_workshop names are remove_dataset, remove_dataset_field, remove_parameter")); //$NON-NLS-1$
        String dcs = helpOf(new DcsWorkshopTool(), "parameters"); //$NON-NLS-1$
        assertTrue(dcs.contains("SELECT-list field for add_query_field / remove_query_field")); //$NON-NLS-1$
        assertTrue(dcs.contains("Font='Arial,12,bold'")); //$NON-NLS-1$
        assertTrue(dcs.contains("colors='#RRGGBB'")); //$NON-NLS-1$
        assertTrue(new MxlWorkshopTool().getDescription()
            .contains("copy_rows / copy_columns replace cells without shifting coordinates")); //$NON-NLS-1$
        assertTrue(propertyDescription(recordOf("mxl_workshop"), "fillType") //$NON-NLS-1$ //$NON-NLS-2$
            .contains("omission keeps the current fill type")); //$NON-NLS-1$
    }

    /** Checks restored conditions against explicit facts in the immutable before records.
     * @throws IOException when a record or fixture cannot be read
     */
    @Test
    public void restoredConditionsRetainTheirBeforeFacts() throws IOException
    {
        assertBeforeAfter(EDIT_METADATA_BASELINE, "edit_metadata", "formType",
            "Legacy purpose values are normalized to MANAGED", "Legacy purpose values are normalized to MANAGED");
        assertBeforeAfter(INFOBASE_ADMIN_BASELINE, "infobase_admin", "endpointTimeoutSeconds",
            "Refused without waitForEndpoint", "refused without waitForEndpoint");
        assertBeforeAfter(INFOBASE_ADMIN_BASELINE, "infobase_admin", "ignoreDumpInfoFormat",
            "names both formats when it stops you, and expects a FULL load when it does not",
            "names both formats when it refuses; when it proceeds it expects a FULL load");
        assertBeforeAfter(INFOBASE_ADMIN_BASELINE, "infobase_admin", "updateBeforeLaunch",
            "A running client with no startupOption, waitForEndpoint, clientType or runMode answers alreadyRunning (started=false) before that gate",
            "a running client with no startupOption, waitForEndpoint, clientType or runMode answers alreadyRunning (started=false) before that gate");
        assertBeforeAfter(INFOBASE_ADMIN_BASELINE, "infobase_admin", "updateBeforeLaunch",
            "default false", "default false");
        assertBeforeAfter(INFOBASE_ADMIN_BASELINE, "infobase_admin", "updateBeforeLaunch",
            "refused when a preset disabled update_database", "refused when update_database is preset-disabled");
        assertBeforeAfter(DCS_WORKSHOP_BASELINE, "dcs_workshop", "overwriteModel",
            "replace a schema the model holds when it differs from the .dcs",
            "replace a schema the model holds when it differs from the .dcs");
        assertBeforeAfter(MXL_WORKSHOP_BASELINE, "mxl_workshop", "formatFrom",
            "Text and parameters are not copied", "text and parameters are not copied");
        assertBeforeAfter(MXL_WORKSHOP_BASELINE, "mxl_workshop", "row",
            "Cell row (1-based); insert_rows puts the new rows before it, delete_rows removes from it",
            "Cell row (1-based) for set_cell / format_cells; draw uses row in layout. insert_rows puts new rows before it; delete_rows removes from it");
        assertBeforeAfter(MXL_WORKSHOP_BASELINE, "mxl_workshop", "col",
            "Cell column (1-based); insert_columns puts the new columns before it, delete_columns removes from it",
            "Cell column (1-based) for set_cell / format_cells; draw uses col in layout. insert_columns puts new columns before it; delete_columns removes from it");
        assertBeforeAfter(INFOBASE_ADMIN_BASELINE, "infobase_admin", "statusOnly",
            "beside runKey or cancel", "beside runKey or cancel");
        assertBeforeAfter(INFOBASE_ADMIN_BASELINE, "infobase_admin", "timeoutSeconds",
            "alias waitSeconds", "alias waitSeconds");
        assertBeforeAfter(INFOBASE_ADMIN_BASELINE, "infobase_admin", "refreshWorkspace",
            "and an extension's parent", "and an extension's parent");
        assertBeforeAfter(EDIT_METADATA_BASELINE, "edit_metadata", "reportAffectedSettings",
            "the edit_metadata names remove_data_set, remove_data_set_field, remove_schema_parameter",
            "remove_data_set / remove_data_set_field / remove_schema_parameter");
        assertBeforeAfter(MXL_WORKSHOP_BASELINE, "mxl_workshop", "areaKind",
            "columns reads the column pair, rows the row pair",
            "columns reads the column pair, rows the row pair");
        assertBeforeAfter(MXL_WORKSHOP_BASELINE, "mxl_workshop", "fillType",
            "A parameter name without fillType is read as parameter",
            "a bare parameter name means parameter");
    }

    /**
     * Checks that snapshot paths distinguish a refused export destination from a required restore input.
     *
     * @throws IOException when a published record or its baseline cannot be read
     */
    @Test
    public void snapshotPathsKeepExportAndRestoreDirections() throws IOException
    {
        assertBeforeAfter(INFOBASE_ADMIN_BASELINE, "infobase_admin", "path", //$NON-NLS-1$ //$NON-NLS-2$
            "the .dt file to write or to load", //$NON-NLS-1$
            "export_database_snapshot: an existing one is refused"); //$NON-NLS-1$
        assertBeforeAfter(INFOBASE_ADMIN_BASELINE, "infobase_admin", "path", //$NON-NLS-1$ //$NON-NLS-2$
            "the .dt file to write or to load", //$NON-NLS-1$
            "restore_database_snapshot requires an existing input"); //$NON-NLS-1$
        assertBeforeAfter(INFOBASE_ADMIN_BASELINE, "infobase_admin", "path", //$NON-NLS-1$ //$NON-NLS-2$
            "required for both", "required .dt path"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Checks a baseline fact and its corresponding current schema contract.
     * @param fixture immutable before record
     * @param tool published tool name
     * @param property schema property
     * @param beforeFact expected before fact
     * @param afterContract required current contract
     * @throws IOException when a record or fixture cannot be read
     */
    private void assertBeforeAfter(String fixture, String tool, String property,
        String beforeFact, String afterContract) throws IOException
    {
        JsonObject before = JsonParser.parseString(readResource(fixture)).getAsJsonObject();
        String beforeText = propertyDescription(before, property);
        String afterText = propertyDescription(recordOf(tool), property);
        assertTrue("baseline fact missing: " + beforeFact, beforeText.contains(beforeFact));
        assertTrue("contract missing from " + tool + "." + property + ": " + afterContract,
            afterText.contains(afterContract));
    }

    /** Returns the description of a schema property.
     * @param record tool record
     * @param property property name
     * @return property description
     */
    private static String propertyDescription(JsonObject record, String property)
    {
        return record.getAsJsonObject("inputSchema").getAsJsonObject("properties")
            .getAsJsonObject(property).get("description").getAsString();
    }

    /**
     * The cut actually cut: each of the four records weighs fewer UTF-8 bytes than its
     * baseline.
     *
     * @throws IOException when the server or a resource cannot be read
     */
    @Test
    public void eachRecordWeighsLessThanBeforeTheCut() throws IOException
    {
        assertShrunk("edit_metadata", EDIT_METADATA_BASELINE); //$NON-NLS-1$
        assertShrunk("infobase_admin", INFOBASE_ADMIN_BASELINE); //$NON-NLS-1$
        assertShrunk("dcs_workshop", DCS_WORKSHOP_BASELINE); //$NON-NLS-1$
        assertShrunk("mxl_workshop", MXL_WORKSHOP_BASELINE); //$NON-NLS-1$
    }

    /**
     * Asserts that one published record still equals its baseline once descriptions are
     * removed.
     *
     * @param tool the wire name
     * @param baselineResource the baseline resource
     * @throws IOException when the server or the resource cannot be read
     */
    private void assertShapeKept(String tool, String baselineResource) throws IOException
    {
        JsonObject published = recordOf(tool);
        JsonObject before = JsonParser.parseString(readResource(baselineResource))
            .getAsJsonObject();
        assertFalse("the baseline of " + tool + " carries no prose - a stripped baseline " //$NON-NLS-1$ //$NON-NLS-2$
            + "would make this comparison vacuous", proseOf(before).isEmpty()); //$NON-NLS-1$
        assertEquals("the record of " + tool + " changed a field other than a description", //$NON-NLS-1$ //$NON-NLS-2$
            withoutProse(before), withoutProse(published));
    }

    /**
     * Asserts that one published record weighs less than its baseline.
     *
     * @param tool the wire name
     * @param baselineResource the baseline resource
     * @throws IOException when the server or the resource cannot be read
     */
    private void assertShrunk(String tool, String baselineResource) throws IOException
    {
        int before = JsonParser.parseString(readResource(baselineResource)).toString()
            .getBytes(StandardCharsets.UTF_8).length;
        int now = recordOf(tool).toString().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(tool + " weighs " + now + " bytes, not fewer than the " + before //$NON-NLS-1$ //$NON-NLS-2$
            + " it weighed before the cut", now < before); //$NON-NLS-1$
    }

    /**
     * Answers which needles are absent from one text.
     *
     * @param tool the tool the needles belong to, for the failure line
     * @param needles the phrases that have to appear
     * @param text the text to search
     * @return one line per missing needle
     */
    private static List<String> conditionsMissingFrom(String tool, String[] needles, String text)
    {
        List<String> missing = new ArrayList<>();
        for (String needle : needles)
        {
            if (!text.contains(needle))
            {
                missing.add(tool + ": " + needle); //$NON-NLS-1$
            }
        }
        return missing;
    }

    /**
     * Finds one tool's record in the advertised catalogue.
     *
     * @param tool the wire name
     * @return the record as published
     * @throws IOException when the server cannot be reached
     */
    private JsonObject recordOf(String tool) throws IOException
    {
        for (JsonElement element : askForTools())
        {
            JsonObject candidate = element.getAsJsonObject();
            if (tool.equals(candidate.get("name").getAsString())) //$NON-NLS-1$
            {
                return candidate;
            }
        }
        throw new IOException("tools/list does not advertise " + tool); //$NON-NLS-1$
    }

    /**
     * Returns the tool's own description plus every description inside its schema.
     *
     * @param tool the wire name
     * @return that text
     * @throws IOException when the server cannot be reached
     */
    private String recordText(String tool) throws IOException
    {
        JsonObject record = recordOf(tool);
        StringBuilder text = new StringBuilder(record.get("description").getAsString()); //$NON-NLS-1$
        for (String line : proseOf(record))
        {
            text.append('\n').append(line);
        }
        return text.toString();
    }

    /**
     * Collects every description member, however deeply nested.
     *
     * @param element a JSON element
     * @return the description texts it carries
     */
    private static List<String> proseOf(JsonElement element)
    {
        List<String> prose = new ArrayList<>();
        if (element.isJsonObject())
        {
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet())
            {
                if ("description".equals(member.getKey()) && member.getValue().isJsonPrimitive()) //$NON-NLS-1$
                {
                    prose.add(member.getValue().getAsString());
                }
                else
                {
                    prose.addAll(proseOf(member.getValue()));
                }
            }
        }
        else if (element.isJsonArray())
        {
            for (JsonElement each : element.getAsJsonArray())
            {
                prose.addAll(proseOf(each));
            }
        }
        return prose;
    }

    /**
     * Removes every description member, the way the shape comparison reads a record.
     *
     * @param element a JSON element
     * @return the same element without prose
     */
    private static JsonElement withoutProse(JsonElement element)
    {
        if (element.isJsonObject())
        {
            JsonObject kept = new JsonObject();
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet())
            {
                if ("description".equals(member.getKey()) && member.getValue().isJsonPrimitive()) //$NON-NLS-1$
                {
                    continue;
                }
                kept.add(member.getKey(), withoutProse(member.getValue()));
            }
            return kept;
        }
        if (element.isJsonArray())
        {
            JsonArray kept = new JsonArray();
            for (JsonElement each : element.getAsJsonArray())
            {
                kept.add(withoutProse(each));
            }
            return kept;
        }
        return element;
    }

    /**
     * Reads only the explicitly requested help topics; null requests the catalog.
     * <p>
     * The topic names differ per tool. A facade that keeps its parameter rules per
     * operation is asked by the operation names whose rules moved; a tool with one
     * parameter topic is asked for that topic.
     * </p>
     *
     * @param tool the tool
     * @param topics the topics to ask, or null for the catalog
     * @return the concatenated help text
     */
    private static String helpOf(IMcpTool tool, String... topics)
    {
        StringBuilder all = new StringBuilder();
        for (String topic : topics)
        {
            Map<String, String> params = new HashMap<>();
            params.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$
            if (topic != null)
            {
                params.put("topic", topic); //$NON-NLS-1$
            }
            all.append('\n').append(textOf(tool.execute(params)));
        }
        return all.toString();
    }

    /**
     * Extracts the text of an answer: the help/text member of a JSON envelope, or the
     * answer itself when the tool answers markdown.
     *
     * @param answer the tool's answer
     * @return its readable text
     */
    private static String textOf(String answer)
    {
        try
        {
            JsonElement parsed = JsonParser.parseString(answer);
            if (parsed.isJsonObject())
            {
                JsonObject object = parsed.getAsJsonObject();
                for (String member : new String[] {"help", "text"}) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    if (object.has(member) && object.get(member).isJsonPrimitive())
                    {
                        return object.get(member).getAsString();
                    }
                }
            }
        }
        catch (RuntimeException notJson)
        {
            // Markdown answers are the answer.
        }
        return answer;
    }

    /**
     * Reads the keys of a dispatch registry, private in the production class because no
     * caller is meant to enumerate it.
     *
     * @param instance the tool instance, or {@code null} for a static field
     * @param owner the class that declares the field
     * @param name the field name
     * @return the registry keys
     * @throws Exception when the field cannot be read
     */
    private static Set<String> keysOf(Object instance, Class<?> owner, String name)
        throws Exception
    {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return keysOf((Map<?, ?>)field.get(instance));
    }

    /**
     * The same for an instance field of a tool.
     *
     * @param instance the tool instance
     * @param name the field name
     * @return the registry keys
     * @throws Exception when the field cannot be read
     */
    private static Set<String> keysOf(Object instance, String name) throws Exception
    {
        return keysOf(instance, instance.getClass(), name);
    }

    /**
     * Turns the key set of a registry map into strings.
     *
     * @param map the registry
     * @return its keys as strings
     */
    private static Set<String> keysOf(Map<?, ?> map)
    {
        Set<String> keys = new HashSet<>();
        for (Object key : map.keySet())
        {
            keys.add(String.valueOf(key));
        }
        return keys;
    }

    /**
     * Turns a fixture array into a string set.
     *
     * @param array the fixture array
     * @return its entries as strings
     */
    private static Set<String> names(JsonArray array)
    {
        Set<String> names = new HashSet<>();
        for (JsonElement entry : array)
        {
            names.add(entry.getAsString());
        }
        return names;
    }

    /**
     * Asks the running server for the advertised tools.
     *
     * @return the tools/list array
     * @throws IOException when the server cannot be reached
     */
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

    /**
     * Reads one test resource.
     *
     * @param name the resource name
     * @return its text
     * @throws IOException when the resource cannot be read
     */
    private static String readResource(String name) throws IOException
    {
        try (InputStream stream = TheWorkshopProseCutKeepsShapeAndConditionsTest.class
            .getResourceAsStream(name); Scanner scanner = new Scanner(stream,
                StandardCharsets.UTF_8))
        {
            return scanner.useDelimiter("\\A").next(); //$NON-NLS-1$
        }
    }

    /** Conditions the four records keep: write, refusal, data loss, permission. */
    private static final String[] EDIT_METADATA_CRITICAL =
        {
            "dryRun=true previews a call without applying it",
            "idempotent skip (propertyMismatch)",
            "cascade form cleanup (cascadeForms)",
            "add/remove_object_reference: list-valued; set/clear_object_reference: scalar",
            "empty clears under mode=replace",
            "a partial condition is refused",
            "an unresolvable one fails the call",
            "required there, refused otherwise",
            "set_role_right: also grant required rights",
            "object or child to remove",
            "replace a differing schema (a backup is written first)",
            "create_object_command: parameter type",
            "other role content is preserved",
        };

    /** The same for infobase_admin: mutation, refusal, data loss, credentials. */
    private static final String[] INFOBASE_ADMIN_CRITICAL =
        {
            "a load replaces everything the infobase holds",
            "inspect_database_sync reads only",
            "protectData (default on) stops before deleting data",
            "acceptDataLoss=true carries it through",
            "a data-loss difference stops the call with confirmationRequired and dataLossTables",
            "an existing one is refused",
            "DESTRUCTIVE",
            "/C only",
            "/Execute",
            "saved configuration is not changed",
            "alreadyRunning",
            "Explicit true is refused",
            "other params are ignored with runKey",
            "best-effort",
            "A load already begun is not undone",
            "rebuild_dump_info - must be true",
            "Stored encrypted; never logged or returned",
        };

    /** The same for dcs_workshop: warnings, removals, refusals, defaults. */
    private static final String[] DCS_WORKSHOP_CRITICAL =
        {
            "settingsWarnings - a warning, not a refusal",
            "lists the settings that still reference it (affectedSettings) and clears nothing",
            "required there, refused otherwise",
            "false omits it",
            "replace a schema the model holds when it differs from the .dcs (default false)",
            "a filter comparing against nothing",
            "Extension projects save the .dcs file automatically",
        };

    /** The same for mxl_workshop: writes, read-only operations, copy behaviour. */
    private static final String[] MXL_WORKSHOP_CRITICAL =
        {
            "format_cells covers placement, font, borders and print settings",
            "read_template and check_print_width read the moxel model only and change nothing",
            "text and parameters are not copied",
            "Preview without applying (default false)",
            "a bare parameter name means parameter",
        };

    /** Details moved from the schema into edit_metadata's parameter help. */
    private static final String[] EDIT_METADATA_MOVED =
        {
            "GET, POST, PUT, DELETE, MERGE, PATCH",
            "conditionValue without field",
            "DocumentObject.X,InformationRegisterRecordSet.Y",
            "remove_schema_parameter",
            "or their Russian equivalents",
            "add it first via add_register_field kind=Dimension",
        };

    /** Details moved from the schema into infobase_admin's parameter help. */
    private static final String[] INFOBASE_ADMIN_MOVED =
        {
            "workspaceRefresh.changedResources",
            "BuildTaskHelper.waitForBuildAndDerivedData",
            "the backup goes beside the .dt being loaded",
            "the infobase's version of those objects",
            "The standalone tools behind these operations remain callable",
            "An external-object binding and /Execute are set by the environment",
        };

    /** Details moved from the schema into dcs_workshop's parameter help. */
    private static final String[] DCS_WORKSHOP_MOVED =
        {
            "multi-statement / UNION is OK",
            "not the aggregate wrapped around it",
            "Filter[Сумма]",
            "several items of one name are refused with their indices",
            "TextColor, BackColor, BorderColor, Font, Format or their Russian equivalents",
        };

    /** Details moved from the schema into mxl_workshop's operation catalog. */
    private static final String[] MXL_WORKSHOP_MOVED =
        {
            "Other templateType values: TextDocument / BinaryData / ActiveDocument / GraphicalSchema / DataCompositionSchema / DataCompositionAppearanceTemplate / GeographicalSchema / HTMLDocument / AddIn",
            "Border styles: None, Solid, Dotted, Double, ThinDashed, ThickDashed, LargeDashed",
        };
}
