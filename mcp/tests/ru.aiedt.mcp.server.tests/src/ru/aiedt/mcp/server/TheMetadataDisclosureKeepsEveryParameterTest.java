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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import ru.aiedt.mcp.server.settings.ToolSettingsStore;
import ru.aiedt.mcp.server.toolkit.ops.EditMetadataTool;

/**
 * Checks the HTTP catalogue against literal description and ordered schema contracts.
 * It also checks built-in help without accessing a project or changing existing budgets.
 */
public class TheMetadataDisclosureKeepsEveryParameterTest
{
    private static final String[] RESOURCES = {
        "catalog-metadata-disclosure-contract.json" //$NON-NLS-1$
    };

    private static final String[] KEYS = {
        PrefKeys.PREF_DISABLED_TOOLS, PrefKeys.PREF_UNLISTED_TOOLS, PrefKeys.PREF_TOOL_PRESET
    };

    private final String[] previous = new String[KEYS.length];
    private final boolean[] wasDefault = new boolean[KEYS.length];
    private LiveServer server;
    private JsonArray catalogue;

    /**
     * Saves raw values and default states, then selects the Canonical catalogue.
     */
    @Before
    public void selectCanonicalCatalogue()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        for (int i = 0; i < KEYS.length; i++)
        {
            previous[i] = store.getString(KEYS[i]);
            wasDefault[i] = store.isDefault(KEYS[i]);
        }
        ToolSettingsStore.getInstance().applyPreset(ToolProfile.CANONICAL);
    }

    /**
     * Stops the owned endpoint and restores values and default states, with the preset last.
     */
    @After
    public void restorePreferencesAndCloseEndpoint()
    {
        try
        {
            if (server != null)
            {
                server.close();
            }
        }
        finally
        {
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            for (int i = 0; i < KEYS.length; i++)
            {
                if (wasDefault[i])
                {
                    store.setToDefault(KEYS[i]);
                }
                else
                {
                    store.setValue(KEYS[i], previous[i]);
                }
            }
        }
    }

    /**
     * Requires an actual further reduction against each recorded accepted tool record.
     *
     * @throws IOException if the endpoint or a literal contract cannot be read
     */
    @Test
    public void thePublishedRecordIsStrictlySmaller() throws IOException
    {
        for (String resource : RESOURCES)
        {
            JsonObject contract = contract(resource);
            String name = contract.get("name").getAsString(); //$NON-NLS-1$
            int before = contract.get("beforeUtf8Bytes").getAsInt(); //$NON-NLS-1$
            int now = published(name).toString().getBytes(StandardCharsets.UTF_8).length;
            assertTrue(name + " weighs " + now + ", not less than " + before, now < before); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Checks every property, the unchanged root and the literal ordered non-description shape.
     *
     * @throws IOException if the endpoint or a literal contract cannot be read
     */
    @Test
    public void everyDescriptionAndTheOrderedShapeMatch() throws IOException
    {
        for (String resource : RESOURCES)
        {
            JsonObject contract = contract(resource);
            JsonObject record = published(contract.get("name").getAsString()); //$NON-NLS-1$
            assertFalse(contract.getAsJsonObject("descriptions").entrySet().isEmpty()); //$NON-NLS-1$
            assertTrue(String.join("; ", detect(record, contract)), detect(record, contract).isEmpty()); //$NON-NLS-1$
        }
    }

    /**
     * Deletes each changed description separately and corrupts the root and a required property.
     * Every mutation must change a valid record and be reported under its exact target.
     *
     * @throws IOException if the endpoint or a literal contract cannot be read
     */
    @Test
    public void everyChangedDescriptionAndStructuralMutationIsDetected() throws IOException
    {
        for (String resource : RESOURCES)
        {
            JsonObject contract = contract(resource);
            JsonObject original = published(contract.get("name").getAsString()); //$NON-NLS-1$
            assertTrue(detect(original, contract).toString(), detect(original, contract).isEmpty());
            assertTrue(contract.getAsJsonArray("changedKeys").size() > 0); //$NON-NLS-1$
            for (JsonElement key : contract.getAsJsonArray("changedKeys")) //$NON-NLS-1$
            {
                String name = key.getAsString();
                JsonObject copy = original.deepCopy();
                JsonObject property = copy.getAsJsonObject("inputSchema").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
                    .getAsJsonObject(name);
                assertNotNull(name, property);
                assertTrue(name, property.has("description")); //$NON-NLS-1$
                assertFalse(name, property.get("description").getAsString().isEmpty()); //$NON-NLS-1$
                property.remove("description"); //$NON-NLS-1$
                assertFalse(name, original.toString().equals(copy.toString()));
                assertTrue(name, detect(copy, contract).contains("PROSE:" + name)); //$NON-NLS-1$
            }
            JsonObject rootCopy = original.deepCopy();
            assertTrue(rootCopy.has("description")); //$NON-NLS-1$
            rootCopy.remove("description"); //$NON-NLS-1$
            assertFalse(original.toString().equals(rootCopy.toString()));
            assertTrue(detect(rootCopy, contract).contains("ROOT")); //$NON-NLS-1$
            JsonObject shapeCopy = original.deepCopy();
            JsonObject properties = shapeCopy.getAsJsonObject("inputSchema").getAsJsonObject("properties"); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(properties.has("operation")); //$NON-NLS-1$
            properties.remove("operation"); //$NON-NLS-1$
            assertFalse(original.toString().equals(shapeCopy.toString()));
            assertTrue(detect(shapeCopy, contract).contains("SHAPE")); //$NON-NLS-1$
        }
    }

    /**
     * Keeps every moved example in its own uniquely named parameter-help section.
     *
     * @throws IOException if the literal contract cannot be read
     */
    @Test
    public void everyMovedExampleIsInItsOwnHelpSection() throws IOException
    {
        JsonObject expected = contract(RESOURCES[0]).getAsJsonObject("helpFacts"); //$NON-NLS-1$
        JsonObject answer = JsonParser.parseString(new EditMetadataTool().execute(
            Map.of("operation", "help", "topic", "parameters"))).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(answer.toString(), answer.has("text")); //$NON-NLS-1$
        String text = answer.get("text").getAsString(); //$NON-NLS-1$
        for (Map.Entry<String, JsonElement> entry : expected.entrySet())
        {
            String heading = "### " + entry.getKey() + "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$
            int start = text.indexOf(heading);
            assertTrue(entry.getKey(), start >= 0);
            assertEquals(entry.getKey(), start, text.lastIndexOf(heading));
            start += heading.length();
            int end = text.indexOf("\n### ", start); //$NON-NLS-1$
            String section = text.substring(start, end < 0 ? text.length() : end);
            for (JsonElement fact : entry.getValue().getAsJsonArray())
            {
                assertTrue(entry.getKey() + ": " + fact.getAsString(), section.contains(fact.getAsString())); //$NON-NLS-1$
            }
        }
    }

    /**
     * Reads one packaged literal contract without deriving expectations from production getters.
     *
     * @param name resource adjacent to the test class
     * @return literal schema and description expectations
     * @throws IOException if the resource is absent or unreadable
     */
    private static JsonObject contract(String name) throws IOException
    {
        try (InputStream input = TheMetadataDisclosureKeepsEveryParameterTest.class.getResourceAsStream(name))
        {
            if (input == null)
            {
                throw new IOException("Contract resource missing: " + name); //$NON-NLS-1$
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /**
     * Reads the named record through tools/list once per test; no tools/call is sent.
     *
     * @param name canonical tool name
     * @return advertised record
     * @throws IOException if the endpoint fails or the tool is absent
     */
    private JsonObject published(String name) throws IOException
    {
        if (catalogue == null)
        {
            server = LiveServer.start();
            Map<String, String> headers = LiveServer.headers("Content-Type", "application/json", //$NON-NLS-1$ //$NON-NLS-2$
                "Accept", "application/json", "Authorization", server.bearer()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            LiveServer.Response response = server.request("POST", "/mcp", headers, //$NON-NLS-1$ //$NON-NLS-2$
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"); //$NON-NLS-1$
            assertEquals(response.body, 200, response.code);
            catalogue = JsonParser.parseString(response.body).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("tools"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (JsonElement element : catalogue)
        {
            JsonObject record = element.getAsJsonObject();
            if (name.equals(record.get("name").getAsString())) //$NON-NLS-1$
            {
                return record;
            }
        }
        throw new IOException("Tool absent: " + name); //$NON-NLS-1$
    }

    /**
     * Names every mismatched contract slot; missing properties are findings, not exceptions.
     *
     * @param record published or mutated tool record
     * @param contract literal expectations
     * @return all mismatched shape, root and property slots
     */
    private static List<String> detect(JsonObject record, JsonObject contract)
    {
        List<String> issues = new ArrayList<>();
        if (!contract.get("shape").toString().equals(withoutDescriptions(record, false).toString())) //$NON-NLS-1$
        {
            issues.add("SHAPE"); //$NON-NLS-1$
        }
        if (!contract.get("root").equals(record.get("description"))) //$NON-NLS-1$ //$NON-NLS-2$
        {
            issues.add("ROOT"); //$NON-NLS-1$
        }
        JsonObject properties = record.getAsJsonObject("inputSchema").getAsJsonObject("properties"); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, JsonElement> entry : contract.getAsJsonObject("descriptions").entrySet()) //$NON-NLS-1$
        {
            JsonObject property = properties.getAsJsonObject(entry.getKey());
            if (property == null || !entry.getValue().equals(property.get("description"))) //$NON-NLS-1$
            {
                issues.add("PROSE:" + entry.getKey()); //$NON-NLS-1$
            }
        }
        return issues;
    }

    /**
     * Removes documentation slots while retaining a property whose name is description.
     *
     * @param value record subtree
     * @param propertyMap whether object keys are parameter names rather than schema fields
     * @return ordered copied JSON without documentation slots
     */
    private static JsonElement withoutDescriptions(JsonElement value, boolean propertyMap)
    {
        if (value.isJsonObject())
        {
            JsonObject copy = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet())
            {
                if (propertyMap || !"description".equals(entry.getKey())) //$NON-NLS-1$
                {
                    copy.add(entry.getKey(), withoutDescriptions(entry.getValue(),
                        !propertyMap && "properties".equals(entry.getKey()))); //$NON-NLS-1$
                }
            }
            return copy;
        }
        if (value.isJsonArray())
        {
            JsonArray copy = new JsonArray();
            for (JsonElement item : value.getAsJsonArray())
            {
                copy.add(withoutDescriptions(item, false));
            }
            return copy;
        }
        return value.deepCopy();
    }
}
