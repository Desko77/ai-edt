/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ops.AuditRoleRightsTool;

/**
 * The audit_role_rights standalone is a reader for its audit modes and a writer for its one
 * removal mode, and the live catalogue says which by the state of its canonical write door.
 * <p>
 * The tool stays callable under every preset because the security group stays on for an auditor;
 * only mode=orphans with apply=true writes, and it gates that write on write_module_source. A
 * declaration of that door is what lets tools/list publish the tool as a writer while the door is
 * enabled and as a reader once a preset disables it. The test reads the real tools/list over HTTP
 * under All Tools and Read-only and asserts the real registry state of the door; it calls no tool
 * operation.
 * </p>
 */
public class TheAuditRoleRightsStandaloneFollowsItsWriteDoorTest
{
    /** The standalone the test classifies. */
    private static final String TOOL = "audit_role_rights"; //$NON-NLS-1$

    /** The canonical write door the tool's removal branch gates on. */
    private static final String WRITE_DOOR = "write_module_source"; //$NON-NLS-1$

    private LiveServer server;

    private IPreferenceStore store;

    private String presetBefore;

    private boolean presetWasDefault;

    /**
     * Starts the live server and remembers the preset value and its is-default bit as found, so
     * the restore can tell a default from an explicit choice.
     *
     * @throws IOException when the server cannot open a port
     */
    @Before
    public void aServerAndThePresetAsFound() throws IOException
    {
        store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        presetWasDefault = store.isDefault(PrefKeys.PREF_TOOL_PRESET);
        server = LiveServer.start();
    }

    /**
     * Puts the preset back to the value or the default it had and stops the server, which also
     * clears the catalogue it registered.
     */
    @After
    public void thePresetGoesBackAndTheServerStops()
    {
        try
        {
            if (presetWasDefault)
            {
                store.setToDefault(PrefKeys.PREF_TOOL_PRESET);
            }
            else
            {
                store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
            }
        }
        finally
        {
            if (server != null)
            {
                server.close();
            }
        }
    }

    /**
     * All Tools enables the canonical write door, so the standalone is listed and callable as a
     * writer: readOnlyHint and idempotentHint are not claimed true, while openWorldHint stays
     * false.
     *
     * @throws IOException when the catalogue request fails
     */
    @Test
    public void allToolsLetsTheWriteDoorThroughAndTheStandaloneIsAWriter() throws IOException
    {
        selectPreset(ToolProfile.ALL_TOOLS);
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        assertTrue("the registry holds the standalone", //$NON-NLS-1$
            catalog.getTool(TOOL) instanceof AuditRoleRightsTool);
        assertTrue("the standalone is callable under All Tools", catalog.isToolEnabled(TOOL)); //$NON-NLS-1$
        assertTrue("the canonical write door is enabled", catalog.isToolEnabled(WRITE_DOOR)); //$NON-NLS-1$
        assertFalse("the preset does not disable the canonical write door", //$NON-NLS-1$
            catalog.isDisabledByPreset(WRITE_DOOR));

        JsonObject record = catalogueRecord(TOOL);
        assertFalse("a writer must not claim readOnlyHint=true", readOnlyHint(record)); //$NON-NLS-1$
        assertFalse("a writer must not claim idempotentHint=true", idempotentHint(record)); //$NON-NLS-1$
        assertFalse("openWorldHint=false remains", openWorldHint(record)); //$NON-NLS-1$
    }

    /**
     * Read-only disables the canonical write door. The standalone is not disabled as a whole: it
     * stays listed and callable for its read modes and advertises readOnlyHint, idempotentHint and
     * openWorldHint=false.
     *
     * @throws IOException when the catalogue request fails
     */
    @Test
    public void readOnlyKeepsTheStandaloneForItsReadsAndPublishesReadHints() throws IOException
    {
        selectPreset(ToolProfile.READ_ONLY);
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        assertTrue("the registry holds the standalone", //$NON-NLS-1$
            catalog.getTool(TOOL) instanceof AuditRoleRightsTool);
        assertTrue("Read-only leaves the standalone callable for its read modes", //$NON-NLS-1$
            catalog.isToolEnabled(TOOL));
        assertFalse("Read-only does not disable the standalone as a whole", //$NON-NLS-1$
            catalog.isDisabledByPreset(TOOL));
        assertFalse("the canonical write door is disabled", catalog.isToolEnabled(WRITE_DOOR)); //$NON-NLS-1$
        assertTrue("the preset disables the canonical write door", //$NON-NLS-1$
            catalog.isDisabledByPreset(WRITE_DOOR));

        JsonObject record = catalogueRecord(TOOL);
        assertTrue("the standalone advertises readOnlyHint", readOnlyHint(record)); //$NON-NLS-1$
        assertTrue("the standalone advertises idempotentHint", idempotentHint(record)); //$NON-NLS-1$
        assertFalse("the standalone states openWorldHint=false", openWorldHint(record)); //$NON-NLS-1$
    }

    /**
     * Puts one preset in force. The store derives the preset's fixed disabled and unlisted sets
     * from this label, so no hand-written name list is needed here.
     *
     * @param preset the preset to put in force
     */
    private void selectPreset(ToolProfile preset)
    {
        store.setValue(PrefKeys.PREF_TOOL_PRESET, preset.name());
    }

    /**
     * Asks the live server for its catalogue and returns the record of one tool.
     *
     * @param name the wire name to find
     * @return the catalogue record
     * @throws IOException when the request fails
     */
    private JsonObject catalogueRecord(String name) throws IOException
    {
        for (JsonElement element : askForTools())
        {
            JsonObject tool = element.getAsJsonObject();
            if (name.equals(tool.get("name").getAsString())) //$NON-NLS-1$
            {
                return tool;
            }
        }
        fail("tools/list does not advertise " + name); //$NON-NLS-1$
        return null;
    }

    /**
     * Sends one tools/list request and returns the advertised records.
     *
     * @return the tools array of the JSON-RPC result
     * @throws IOException when the request fails
     */
    private JsonArray askForTools() throws IOException
    {
        Map<String, String> headers = LiveServer.headers(
            "Content-Type", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Accept", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
            "Authorization", server.bearer()); //$NON-NLS-1$
        LiveServer.Response answer = server.request("POST", "/mcp", headers, //$NON-NLS-1$ //$NON-NLS-2$
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"); //$NON-NLS-1$
        assertEquals("the catalogue request is answered", 200, answer.code); //$NON-NLS-1$
        JsonObject document = JsonParser.parseString(answer.body).getAsJsonObject();
        return document.getAsJsonObject("result").getAsJsonArray("tools"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Returns the annotations member of a catalogue record, failing when there is none.
     *
     * @param record the catalogue record
     * @return its annotations
     */
    private static JsonObject annotationsOf(JsonObject record)
    {
        assertTrue("the entry carries annotations", record.has("annotations")); //$NON-NLS-1$ //$NON-NLS-2$
        return record.getAsJsonObject("annotations"); //$NON-NLS-1$
    }

    /**
     * Whether the record claims readOnlyHint=true. An absent hint is the specification default
     * false.
     *
     * @param record the catalogue record
     * @return the effective value of readOnlyHint
     */
    private static boolean readOnlyHint(JsonObject record)
    {
        JsonObject annotations = annotationsOf(record);
        return annotations.has("readOnlyHint") //$NON-NLS-1$
            && annotations.get("readOnlyHint").getAsBoolean(); //$NON-NLS-1$
    }

    /**
     * Whether the record claims idempotentHint=true. An absent hint is the specification default
     * false.
     *
     * @param record the catalogue record
     * @return the effective value of idempotentHint
     */
    private static boolean idempotentHint(JsonObject record)
    {
        JsonObject annotations = annotationsOf(record);
        return annotations.has("idempotentHint") //$NON-NLS-1$
            && annotations.get("idempotentHint").getAsBoolean(); //$NON-NLS-1$
    }

    /**
     * The record's openWorldHint, which the server always states for this tool. An absent member
     * would mean the specification default true.
     *
     * @param record the catalogue record
     * @return the stated value of openWorldHint
     */
    private static boolean openWorldHint(JsonObject record)
    {
        JsonObject annotations = annotationsOf(record);
        assertTrue("the entry states openWorldHint", annotations.has("openWorldHint")); //$NON-NLS-1$ //$NON-NLS-2$
        return annotations.get("openWorldHint").getAsBoolean(); //$NON-NLS-1$
    }
}
