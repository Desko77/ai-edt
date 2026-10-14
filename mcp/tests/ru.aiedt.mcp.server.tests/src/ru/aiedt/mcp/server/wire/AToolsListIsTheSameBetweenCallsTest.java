/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.Activator;
import ru.aiedt.mcp.server.settings.PrefKeys;
import ru.aiedt.mcp.server.settings.ToolSettingsStore;
import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * Every {@code tools/list} call is answered with the same document.
 * <p>
 * The catalogue is read from a concurrent hash map, whose iteration order is not part of its
 * contract and moves as the map grows. A client that caches the catalogue - and a model whose prompt
 * cache holds it - compares the bytes it received with the bytes it has, so the same catalogue has to
 * come back as the same bytes: the same order, the same description texts, the same schemas. The
 * comparison here is between whole serialized {@code result} objects, which covers all three; the
 * request id differs between the calls and must not reach the result at all.
 * </p>
 */
public class AToolsListIsTheSameBetweenCallsTest
{
    /**
     * Registered in an order that is not the answer's, in names whose order in a hash map is not the
     * alphabetical one either.
     */
    private static final List<String> REGISTERED = List.of("zulu", "alpha", "mike", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "bravo", "yankee", "charlie"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** Hidden after the first call and shown again before the last one. */
    private static final String TOGGLED = "mike"; //$NON-NLS-1$

    private McpRequestRouter handler;

    private McpToolCatalog registry;

    private String presetBefore;

    private String disabledBefore;

    private String unlistedBefore;

    /**
     * Registers the tools in the order that is not the answer, and reads the settings to put back.
     */
    @Before
    public void registerTheTools()
    {
        registry = McpToolCatalog.getInstance();
        registry.clear();
        for (String name : REGISTERED)
        {
            registry.register(stub(name));
        }
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        presetBefore = store.getString(PrefKeys.PREF_TOOL_PRESET);
        disabledBefore = store.getString(PrefKeys.PREF_DISABLED_TOOLS);
        unlistedBefore = store.getString(PrefKeys.PREF_UNLISTED_TOOLS);
        // Whatever an earlier test in this runtime left hidden or disabled must not decide what this
        // one compares, so both sets start empty and the selection starts hand-picked.
        ToolSettingsStore.getInstance().setDisabledTools(Set.of());
        ToolSettingsStore.getInstance().setUnlistedTools(Set.of());
        handler = new McpRequestRouter();
    }

    /**
     * Puts the tool settings and the catalogue back.
     */
    @After
    public void restore()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setValue(PrefKeys.PREF_DISABLED_TOOLS, disabledBefore);
        store.setValue(PrefKeys.PREF_UNLISTED_TOOLS, unlistedBefore);
        store.setValue(PrefKeys.PREF_TOOL_PRESET, presetBefore);
        registry.clear();
    }

    /**
     * A whole number id and a string id are answered with one and the same catalogue.
     */
    @Test
    public void twoRequestIdsAreAnsweredTheSameCatalogue()
    {
        String forNumber = result(1);
        String forString = result("abc"); //$NON-NLS-1$

        assertEquals("every registered tool is listed", REGISTERED.size(), names(forNumber).size()); //$NON-NLS-1$
        assertEquals("the catalogue differs between two calls", forNumber, forString); //$NON-NLS-1$
    }

    /**
     * Hiding a tool and showing it again leaves the catalogue exactly as it was.
     * <p>
     * A hidden tool takes the other branch of the listing - the sets are non-empty, so the catalogue
     * is built by filtering rather than by handing the map's values straight over - and the answer
     * must not come back in a different order because of it.
     * </p>
     */
    @Test
    public void hidingAToolAndShowingItAgainLeavesTheCatalogueAsItWas()
    {
        String before = result(1);

        ToolSettingsStore.getInstance().setToolEnabled(TOGGLED, false);
        List<String> whileHidden = names(result(2));
        assertFalse("the hidden tool is still listed", whileHidden.contains(TOGGLED)); //$NON-NLS-1$
        assertEquals("hiding one tool must hide exactly one", REGISTERED.size() - 1, //$NON-NLS-1$
            whileHidden.size());

        ToolSettingsStore.getInstance().setToolEnabled(TOGGLED, true);
        String after = result(3);

        assertEquals("the catalogue changed although the same tools are visible", before, after); //$NON-NLS-1$
    }

    /**
     * Sends one tools/list request and returns its result as text.
     *
     * @param id the request id, a number or a string
     * @return the serialized {@code result} member
     */
    private String result(Object id)
    {
        return JsonParser.parseString(handler.processRequest(request(id)))
            .getAsJsonObject().get("result").toString(); //$NON-NLS-1$
    }

    /**
     * Reads the tool names out of a serialized result, in the order they are listed.
     *
     * @param resultJson the serialized {@code result} member
     * @return the names, in order
     */
    private static List<String> names(String resultJson)
    {
        JsonArray tools = JsonParser.parseString(resultJson)
            .getAsJsonObject().getAsJsonArray("tools"); //$NON-NLS-1$
        List<String> names = new ArrayList<>(tools.size());
        for (JsonElement entry : tools)
        {
            names.add(entry.getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$
        }
        return names;
    }

    /**
     * Builds a tools/list request document.
     *
     * @param id the request id, a number or a string
     * @return the request document
     */
    private static String request(Object id)
    {
        StringBuilder builder = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":"); //$NON-NLS-1$
        if (id instanceof String)
        {
            builder.append('"').append(id).append('"');
        }
        else
        {
            builder.append(id);
        }
        return builder.append(",\"method\":\"tools/list\"}").toString(); //$NON-NLS-1$
    }

    /**
     * A tool under the given name, with a description and a schema of its own.
     *
     * @param name the tool name
     * @return the tool
     */
    private static IMcpTool stub(String name)
    {
        return new IMcpTool()
        {
            /** Returns the name the catalogue lists this tool under. */
            @Override
            public String getName()
            {
                return name;
            }

            /** Returns the description carried in the catalogue. */
            @Override
            public String getDescription()
            {
                return name + " tool"; //$NON-NLS-1$
            }

            /** Returns a schema that names the tool, so a swapped entry shows up in the bytes. */
            @Override
            public String getInputSchema()
            {
                return "{\"type\":\"object\",\"properties\":{\"" + name + "\":{\"type\":\"string\"}}}"; //$NON-NLS-1$ //$NON-NLS-2$
            }

            /** Is never called by a tools/list, and answers nothing if it ever is. */
            @Override
            public String execute(Map<String, String> params)
            {
                return "{}"; //$NON-NLS-1$
            }
        };
    }
}
