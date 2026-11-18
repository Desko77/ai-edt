/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;

/** Tests the published support registry record over the actual HTTP catalogue. */
public class TheSupportRegistryRecordKeepsItsContractTest
{
    private LiveServer server;
    private IPreferenceStore store;
    private final String[] keys = {PrefKeys.PREF_DISABLED_TOOLS, PrefKeys.PREF_UNLISTED_TOOLS, PrefKeys.PREF_TOOL_PRESET};
    private final String[] previous = new String[keys.length];
    private final boolean[] defaults = new boolean[keys.length];

    /** Isolates catalogue visibility and annotations from the workspace preset. */
    @Before
    public void setUp()
    {
        store = Activator.getDefault().getPreferenceStore();
        for (int i = 0; i < keys.length; i++)
        {
            previous[i] = store.getString(keys[i]);
            defaults[i] = store.isDefault(keys[i]);
            store.setValue(keys[i], ""); //$NON-NLS-1$
        }
        store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.CANONICAL.name());
    }

    /** Stops the endpoint and restores all preference values and default state. */
    @After
    public void tearDown()
    {
        if (server != null)
        {
            server.close();
        }
        if (store != null)
        {
            for (int i = 0; i < keys.length; i++)
            {
                if (defaults[i])
                {
                    store.setToDefault(keys[i]);
                }
                else
                {
                    store.setValue(keys[i], previous[i]);
                }
            }
        }
    }

    /** The same test on the unchanged base fails only because the record is 3092 bytes. */
    @Test
    public void publishedRecordIsSmallerThanOriginal() throws IOException
    {
        int bytes = record().toString().getBytes(StandardCharsets.UTF_8).length;
        assertTrue("support_registry bytes: " + bytes, bytes < 3092); //$NON-NLS-1$
    }

    /** The complete ordered non-description record and each published condition survive. */
    @Test
    public void shapeAndConditionsSurvive() throws IOException
    {
        JsonObject record = record();
        assertTrue(failures(record).toString(), failures(record).isEmpty());
    }

    /** Every condition family and the schema detector rejects a targeted corrupt copy. */
    @Test
    public void corruptionsIdentifyTheirFamilies() throws IOException
    {
        JsonObject real = record();
        assertTrue(failures(real).toString(), failures(real).isEmpty());
        for (String[] mutation : MUTATIONS)
        {
            JsonObject copy = real.deepCopy();
            JsonObject field = "description".equals(mutation[0]) ? copy : descriptions(copy).getAsJsonObject(mutation[0]); //$NON-NLS-1$
            String text = field.get("description").getAsString(); //$NON-NLS-1$
            assertTrue("missing mutation target " + mutation[1], text.contains(mutation[1])); //$NON-NLS-1$
            field.addProperty("description", text.replace(mutation[1], mutation[2])); //$NON-NLS-1$
            assertTrue(mutation[0], failures(copy).contains(mutation[0]));
        }
        JsonObject copy = real.deepCopy();
        descriptions(copy).getAsJsonObject("operation").addProperty("type", "boolean"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(failures(copy).contains("shape")); //$NON-NLS-1$
    }

    /** Fetches the one published record from a real tools/list response. */
    private JsonObject record() throws IOException
    {
        if (server == null)
        {
            server = LiveServer.start();
        }
        Map<String, String> headers = LiveServer.headers("Content-Type", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Accept", "application/json", "Authorization", server.bearer()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        LiveServer.Response response = server.request("POST", "/mcp", headers, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"); //$NON-NLS-1$
        assertEquals(200, response.code);
        for (JsonElement entry : JsonParser.parseString(response.body).getAsJsonObject()
            .getAsJsonObject("result").getAsJsonArray("tools")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            JsonObject tool = entry.getAsJsonObject();
            if ("support_registry".equals(tool.get("name").getAsString())) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return tool;
            }
        }
        throw new IOException("support_registry is absent from tools/list"); //$NON-NLS-1$
    }

    /** Returns the schema property objects. */
    private static JsonObject descriptions(JsonObject record)
    {
        return record.getAsJsonObject("inputSchema").getAsJsonObject("properties"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Reports the independent shape and condition families that a record violates. */
    private static List<String> failures(JsonObject record)
    {
        List<String> failures = new ArrayList<>();
        JsonObject shape = record.deepCopy();
        shape.remove("description"); //$NON-NLS-1$
        for (Map.Entry<String, JsonElement> property : descriptions(shape).entrySet())
        {
            property.getValue().getAsJsonObject().remove("description"); //$NON-NLS-1$
        }
        if (!SHAPE.equals(shape.toString()))
        {
            failures.add("shape"); //$NON-NLS-1$
        }
        String top = record.get("description").getAsString(); //$NON-NLS-1$
        if (!TOP_BEFORE.equals(top) && !TOP_AFTER.equals(top))
        {
            failures.add("description"); //$NON-NLS-1$
        }
        JsonObject properties = descriptions(record);
        for (String[] condition : CONDITIONS)
        {
            if (!condition[1].equals(properties.getAsJsonObject(condition[0]).get("description").getAsString())) //$NON-NLS-1$
            {
                failures.add(condition[0]);
            }
        }
        return failures;
    }

    private static final String SHAPE = "{\"name\":\"support_registry\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"operation\":{\"type\":\"string\"},\"topic\":{\"type\":\"string\"},\"find\":{\"type\":\"string\"},\"projectName\":{\"type\":\"string\"},\"objectFqn\":{\"type\":\"string\"},\"userMode\":{\"type\":\"string\"},\"parentId\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\"},\"limit\":{\"type\":\"integer\"},\"snapshotPath\":{\"type\":\"string\"},\"apply\":{\"type\":\"boolean\"}},\"required\":[\"operation\"]},\"annotations\":{\"openWorldHint\":false}}"; //$NON-NLS-1$
    private static final String TOP_BEFORE = "Reads the vendor support state of a configuration: which vendor configurations it descends from, their releases, the object count per support mode, the rules the environment applies on the next update, and the mode of any single object together with the objects the environment requires to change with it. Operations: status, list_objects, object_mode, snapshot_modes, restore_modes, help. A mode records what was declared for an object, not whether the object was modified - use compare_three_way for that. snapshot_modes writes down which object held which mode, which is what makes a merge reversible: an update takes the support model from the delivery and no merge rule prevents it. restore_modes puts them back, and is the only operation here that changes anything - it reports what it would do unless apply=true."; //$NON-NLS-1$
    private static final String TOP_AFTER = "Reads the vendor configurations this configuration descends from and their releases, object counts per mode, next-update rules, and an object's mode and objects required to change with it. Operations: status, list_objects, object_mode, snapshot_modes, restore_modes, help. A mode records what was declared for an object, not whether the object was modified - use compare_three_way for that. snapshot_modes writes down which object held which mode, making a merge reversible: the merge takes the support model from the delivery and no merge rule prevents it. restore_modes puts them back; only it changes the configuration, and it reports what it would do unless apply=true."; //$NON-NLS-1$
    private static final String[][] CONDITIONS = {
        {"operation", "status / list_objects / object_mode / snapshot_modes / restore_modes / help. Pass operation=help without other parameters for the catalog."}, //$NON-NLS-1$ //$NON-NLS-2$
        {"topic", "Help topic when operation=help. Without topic - lists all operations."}, //$NON-NLS-1$ //$NON-NLS-2$
        {"find", "With help: search this help for a case-insensitive substring - all the words must appear inside one chunk, and with topic only that topic is searched."}, //$NON-NLS-1$ //$NON-NLS-2$
        {"projectName", "EDT project to read. Required for every operation except help."}, //$NON-NLS-1$ //$NON-NLS-2$
        {"objectFqn", "object_mode: the object to report on, named as this server names it - Catalog.Name, Catalog.Name.Attribute.Other, or Configuration for the root, which takes CHANGES_ALLOWED before any other object can."}, //$NON-NLS-1$ //$NON-NLS-2$
        {"userMode", "list_objects: report only objects in this mode - CHANGES_NOT_ALLOWED, CHANGES_ALLOWED or CANCELLED, spelled either that way or the way the answers spell it (ChangesNotAllowed). A word naming no mode is refused rather than answered with an empty page. Omit for every mode."}, //$NON-NLS-1$ //$NON-NLS-2$
        {"parentId", "list_objects: which vendor configuration the vendor modes come from, by id or by name. Required when the configuration descends from more than one, because a mode belongs to a vendor rather than to the object outright."}, //$NON-NLS-1$ //$NON-NLS-2$
        {"offset", "list_objects and object_mode: how many to skip. Default 0."}, //$NON-NLS-1$ //$NON-NLS-2$
        {"limit", "list_objects and object_mode: how many to return. Default and maximum 500."}, //$NON-NLS-1$ //$NON-NLS-2$
        {"snapshotPath", "snapshot_modes: absolute path to write the snapshot to. restore_modes: the snapshot to restore from - required. compare_three_way writes one into the project's .settings before every merge and names it in its answer."}, //$NON-NLS-1$ //$NON-NLS-2$
        {"apply", "restore_modes: write the recorded modes back. Default false, which reports what would be written and changes nothing."}, //$NON-NLS-1$ //$NON-NLS-2$
    };
    private static final String[][] MUTATIONS = {
        {"description", "unless apply=true", "only if apply=true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"description", "not whether the object was modified", "whether the object was modified"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"description", "snapshot_modes writes down", "snapshot_modes reads"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"operation", "status / list_objects", "status / deleted"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"topic", "Without topic - lists all operations", "Without topic - lists no operations"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"find", "all the words must appear inside one chunk", "any word may appear in different chunks"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"projectName", "Required for every operation except help", "Required only for help"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"objectFqn", "object_mode:", "list_objects:"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"objectFqn", "Catalog.Name.Attribute.Other", "Catalog.Name"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"objectFqn", "before any other object can", "after any other object can"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"userMode", "CHANGES_ALLOWED or CANCELLED", "CHANGES_ALLOWED only"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"userMode", "naming no mode is refused", "naming no mode is accepted"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"userMode", "Omit for every mode", "Omit for no modes"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"parentId", "by id or by name", "by id only"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"parentId", "Required when the configuration descends from more than one", "Optional when the configuration descends from more than one"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"offset", "Default 0", "Default 1"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"limit", "list_objects and object_mode", "restore_modes"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"limit", "Default and maximum 500", "Default and maximum 50"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"snapshotPath", "absolute path to write", "absolute path to read"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"snapshotPath", "snapshot to restore from - required", "snapshot to restore from - optional"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"apply", "Default false", "Default true"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {"apply", "changes nothing", "writes immediately"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    };
}
