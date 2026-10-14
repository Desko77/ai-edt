/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;
import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * A call to a tool the user switched off is answered as a refusal the client can see.
 * <p>
 * The router answered such a call with the instructions as plain text and no {@code isError}, while
 * a facade whose write door a preset closed answered with {@code isError=true}. Under one preset a
 * client then read one refusal as a failure and the other as a successful answer. The call is still
 * answered as a tool result rather than a JSON-RPC error, so the instructions reach the agent.
 * </p>
 */
public class ADisabledToolAnswersAsARefusalTest
{
    private static final String TOOL = "aiedt_disabled_probe"; //$NON-NLS-1$

    private final AtomicInteger runs = new AtomicInteger();

    private McpToolCatalog registry;

    private String presetBefore;

    private String disabledBefore;

    /**
     * Registers the probe and keeps the tool settings to put back.
     */
    @Before
    public void registerTheProbe()
    {
        registry = McpToolCatalog.getInstance();
        registry.clear();
        registry.register(probe());
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
    }

    /**
     * Puts the tool settings and the catalogue back.
     */
    @After
    public void restore()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
        registry.clear();
    }

    /**
     * Switched off, the tool does not run and the answer carries the refusal marked isError.
     */
    @Test
    public void aSwitchedOffToolIsRefusedWithIsError()
    {
        ToolSettingsStore.getInstance().setDisabledTools(Set.of(TOOL));

        JsonObject result = call().getAsJsonObject("result"); //$NON-NLS-1$

        assertEquals(0, runs.get());
        assertTrue("the refusal is not marked isError: " + result, //$NON-NLS-1$
            result.has("isError") && result.get("isError").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject() //$NON-NLS-1$
            .get("text").getAsString(); //$NON-NLS-1$
        assertEquals(ToolGate.disabledMessage(TOOL), text);
    }

    /**
     * Switched on, the same tool runs and its ordinary answer says nothing about failure.
     */
    @Test
    public void theSameToolSwitchedOnAnswersWithoutTheMark()
    {
        ToolSettingsStore.getInstance().setDisabledTools(Set.of());

        JsonObject result = call().getAsJsonObject("result"); //$NON-NLS-1$

        assertEquals(1, runs.get());
        assertNull(result.toString(), result.get("isError")); //$NON-NLS-1$
    }

    /**
     * Sends one tools/call for the probe through a fresh router.
     *
     * @return the response document
     */
    private JsonObject call()
    {
        String document = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," //$NON-NLS-1$
            + "\"params\":{\"name\":\"" + TOOL + "\",\"arguments\":{}}}"; //$NON-NLS-1$ //$NON-NLS-2$
        return JsonParser.parseString(new McpRequestRouter().processRequest(document)).getAsJsonObject();
    }

    /**
     * A tool that counts its runs and answers a fixed text.
     *
     * @return the probe
     */
    private IMcpTool probe()
    {
        return new IMcpTool()
        {
            @Override
            public String getName()
            {
                return TOOL;
            }

            @Override
            public String getDescription()
            {
                return "Probe"; //$NON-NLS-1$
            }

            @Override
            public String getInputSchema()
            {
                return "{\"type\":\"object\"}"; //$NON-NLS-1$
            }

            @Override
            public String execute(Map<String, String> params)
            {
                runs.incrementAndGet();
                return "done"; //$NON-NLS-1$
            }
        };
    }
}
