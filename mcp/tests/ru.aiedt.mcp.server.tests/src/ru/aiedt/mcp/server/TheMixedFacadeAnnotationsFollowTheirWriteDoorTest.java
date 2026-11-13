/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolProfile;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/** The wire catalogue follows the existing write door of both mixed facades. */
public class TheMixedFacadeAnnotationsFollowTheirWriteDoorTest
{
    /** Both records remain callable and their advertised hints follow the active preset. */
    @Test
    public void bothFacadesFollowTheWriteDoorOnTheWire() throws IOException
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        boolean presetWasDefault = store.isDefault(PrefKeys.PREF_TOOL_PRESET);
        boolean bindBefore = store.getBoolean(PrefKeys.PREF_BIND_ALL_INTERFACES);
        boolean bindWasDefault = store.isDefault(PrefKeys.PREF_BIND_ALL_INTERFACES);
        try
        {
            store.setValue(PrefKeys.PREF_BIND_ALL_INTERFACES, false);
            store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.CANONICAL.name());
            try (LiveServer server = LiveServer.start())
            {
                assertState(server, false);
                store.setValue(PrefKeys.PREF_TOOL_PRESET, ToolProfile.READ_ONLY.name());
                assertState(server, true);
            }
        }
        finally
        {
            if (presetWasDefault)
            {
                store.setToDefault(PrefKeys.PREF_TOOL_PRESET);
            }
            else
            {
                store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
            }
            if (bindWasDefault)
            {
                store.setToDefault(PrefKeys.PREF_BIND_ALL_INTERFACES);
            }
            else
            {
                store.setValue(PrefKeys.PREF_BIND_ALL_INTERFACES, bindBefore);
            }
        }
    }

    /**
     * Checks both advertised facade records and the actual registry state of their write door.
     *
     * @param server isolated HTTP server
     * @param readOnly whether the canonical write door must be disabled
     * @throws IOException when the HTTP catalogue cannot be read
     */
    private static void assertState(LiveServer server, boolean readOnly) throws IOException
    {
        McpToolCatalog catalog = McpToolCatalog.getInstance();
        assertEquals(readOnly, catalog.isDisabledByPreset("write_module_source")); //$NON-NLS-1$
        assertEquals(!readOnly, catalog.isToolEnabled("write_module_source")); //$NON-NLS-1$
        LiveServer.Response response = server.request("POST", "/mcp", LiveServer.headers( //$NON-NLS-1$ //$NON-NLS-2$
            "Content-Type", "application/json", "Accept", "application/json", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Authorization", server.bearer()), //$NON-NLS-1$
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"); //$NON-NLS-1$
        assertEquals(response.body, 200, response.code);
        JsonObject result = JsonParser.parseString(response.body).getAsJsonObject().getAsJsonObject("result"); //$NON-NLS-1$
        assertNotNull(response.body, result);
        for (String name : new String[] { "support_registry", "security_audit" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue(name, catalog.isToolEnabled(name));
            JsonObject record = null;
            for (JsonElement entry : result.getAsJsonArray("tools")) //$NON-NLS-1$
            {
                JsonObject candidate = entry.getAsJsonObject();
                if (name.equals(candidate.get("name").getAsString())) //$NON-NLS-1$
                {
                    record = candidate;
                    break;
                }
            }
            assertNotNull(name + " must be listed", record); //$NON-NLS-1$
            JsonObject annotations = record.getAsJsonObject("annotations"); //$NON-NLS-1$
            assertNotNull(name, annotations);
            assertTrue(name, annotations.has("openWorldHint")); //$NON-NLS-1$
            assertFalse(name, annotations.get("openWorldHint").getAsBoolean()); //$NON-NLS-1$
            for (String hint : new String[] { "readOnlyHint", "idempotentHint" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                if (readOnly)
                {
                    assertTrue(name + " " + hint, annotations.has(hint) && annotations.get(hint).getAsBoolean()); //$NON-NLS-1$
                }
                else
                {
                    assertFalse(name + " " + hint, annotations.has(hint) && annotations.get(hint).getAsBoolean()); //$NON-NLS-1$
                }
            }
        }
    }
}
