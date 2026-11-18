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
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The two descriptions this task shortens keep their contract and lose only prose.
 * <p>
 * A client builds its call from the schema, so the parameters, their types, their required
 * obligations and every condition that decides whether a write happens are not prose. Only the
 * sentences around them may shrink. The baseline entry and the critical-condition matrix were
 * recorded from {@code tools/list} in the headless test runtime before the cut, and are kept as
 * resources so this check never reads its expectations from the shortened text.
 * </p>
 */
public class TheTwoToolDescriptionsAreSmallerWithoutLosingTheirContractTest
{
    private static final String BASELINE_DIR = "prose-baseline"; //$NON-NLS-1$

    private static final String CONDITIONS = BASELINE_DIR + "/critical-conditions.json"; //$NON-NLS-1$

    /** Serialized UTF-8 bytes of each tool entry in {@code tools/list} before the cut. */
    private static final Map<String, Integer> BASELINE_BYTES = new HashMap<>();
    static
    {
        BASELINE_BYTES.put("write_module_source", Integer.valueOf(7113)); //$NON-NLS-1$
        BASELINE_BYTES.put("compare_three_way", Integer.valueOf(6728)); //$NON-NLS-1$
    }

    /** Parameter prose in the same entry before the cut, in UTF-8 bytes. */
    private static final Map<String, Integer> BASELINE_PROSE = new HashMap<>();
    static
    {
        BASELINE_PROSE.put("write_module_source", Integer.valueOf(5108)); //$NON-NLS-1$
        BASELINE_PROSE.put("compare_three_way", Integer.valueOf(3747)); //$NON-NLS-1$
    }

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
    public void eachPublishedEntryIsSmallerThanItWas() throws IOException
    {
        Map<String, JsonObject> tools = toolsByName();
        List<String> notSmaller = new ArrayList<>();
        for (String name : BASELINE_BYTES.keySet())
        {
            JsonObject tool = tools.get(name);
            assertNotNull(name + " is no longer advertised", tool); //$NON-NLS-1$
            int bytes = tool.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes >= BASELINE_BYTES.get(name).intValue())
            {
                notSmaller.add(name + " weighs " + bytes + " bytes, not less than the recorded " //$NON-NLS-1$ //$NON-NLS-2$
                    + BASELINE_BYTES.get(name));
            }
            int prose = proseOf(tool.get("inputSchema")); //$NON-NLS-1$
            if (prose >= BASELINE_PROSE.get(name).intValue())
            {
                notSmaller.add(name + " carries " + prose + " bytes of parameter prose, not less than " //$NON-NLS-1$ //$NON-NLS-2$
                    + "the recorded " + BASELINE_PROSE.get(name)); //$NON-NLS-1$
            }
        }
        assertTrue(String.join("\n", notSmaller), notSmaller.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void eachSchemaIsTheSameOnceDescriptionsAreRemoved() throws IOException
    {
        Map<String, JsonObject> tools = toolsByName();
        for (String name : BASELINE_BYTES.keySet())
        {
            JsonObject before = JsonParser.parseString(resource(BASELINE_DIR + "/" + name + ".json")) //$NON-NLS-1$ //$NON-NLS-2$
                .getAsJsonObject();
            JsonObject now = tools.get(name);
            assertNotNull(name + " is no longer advertised", now); //$NON-NLS-1$
            assertEquals(name + " declares something other than prose differently", //$NON-NLS-1$
                withoutDescriptions(before), withoutDescriptions(now));
        }
    }

    @Test
    public void everyCriticalConditionIsStillPublished() throws IOException
    {
        JsonObject matrix = JsonParser.parseString(resource(CONDITIONS)).getAsJsonObject();
        Map<String, JsonObject> tools = toolsByName();
        Map<String, JsonObject> baselines = new HashMap<>();
        for (String name : BASELINE_BYTES.keySet())
        {
            baselines.put(name, JsonParser.parseString(resource(BASELINE_DIR + "/" + name + ".json")) //$NON-NLS-1$ //$NON-NLS-2$
                .getAsJsonObject());
        }
        List<String> missing = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int checked = 0;
        for (Map.Entry<String, JsonElement> toolEntry : matrix.entrySet())
        {
            String name = toolEntry.getKey();
            JsonObject tool = tools.get(name);
            assertNotNull(name + " is no longer advertised", tool); //$NON-NLS-1$
            JsonObject baseline = baselines.get(name);
            assertNotNull("no baseline entry for " + name, baseline); //$NON-NLS-1$
            for (JsonElement element : toolEntry.getValue().getAsJsonArray())
            {
                JsonObject condition = element.getAsJsonObject();
                String id = condition.get("id").getAsString(); //$NON-NLS-1$
                assertTrue("condition id " + id + " is repeated", ids.add(id)); //$NON-NLS-1$ //$NON-NLS-2$
                String property = location(condition, "property"); //$NON-NLS-1$
                String before = location(condition, "before"); //$NON-NLS-1$
                String where = name + (property == null ? "" : "." + property); //$NON-NLS-1$ //$NON-NLS-2$
                String beforeWhere = name + "." + (before == null ? "tool" : before); //$NON-NLS-1$ //$NON-NLS-2$
                for (JsonElement required : condition.getAsJsonArray("requires")) //$NON-NLS-1$
                {
                    checked++;
                    String token = required.getAsString();
                    // The matrix was recorded from the baseline, so every expectation has to be
                    // there. A token that only matches the shortened text is an expectation
                    // written after the cut, and this check refuses it.
                    if (!holds(textOf(baseline, before), token))
                    {
                        missing.add(id + " did not hold in the baseline at " + beforeWhere //$NON-NLS-1$
                            + ": \"" + token + "\""); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    if (!holds(textOf(tool, property), token))
                    {
                        missing.add(id + " lost \"" + token + "\" at " + where); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
        }
        assertFalse("the recorded critical-condition matrix is empty", checked == 0); //$NON-NLS-1$
        assertTrue(String.join("\n", missing), missing.isEmpty()); //$NON-NLS-1$
    }

    /**
     * A location in the condition, or the inherited one.
     * <p>
     * The matrix records {@code property} as where the condition remains and {@code before} as
     * where it was recorded in the baseline. A condition that moves inside the published schema
     * names both; the rest read the same place twice.
     * </p>
     *
     * @param condition one matrix row.
     * @param key the member to read.
     * @return the parameter name, or <code>null</code> for the tool description.
     */
    private static String location(JsonObject condition, String key)
    {
        if (!condition.has(key))
        {
            return "property".equals(key) ? null : location(condition, "property"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        JsonElement value = condition.get(key);
        return value.isJsonNull() ? null : value.getAsString();
    }

    /**
     * Whether a text carries what a condition requires.
     *
     * @param text the description, or <code>null</code> when it is not published.
     * @param required the phrase the condition insists on.
     * @return <code>true</code> when the phrase is there, case-insensitively.
     */
    private static boolean holds(String text, String required)
    {
        return text != null
            && text.toLowerCase(Locale.ROOT).contains(required.toLowerCase(Locale.ROOT));
    }

    /**
     * The text a condition watches: the tool's own description, or one parameter's.
     *
     * @param tool the published tool entry.
     * @param property the parameter name, or <code>null</code> for the tool description.
     * @return the description, or <code>null</code> when the tool no longer publishes it
     */
    private static String textOf(JsonObject tool, String property)
    {
        if (property == null)
        {
            return tool.has("description") ? tool.get("description").getAsString() : null; //$NON-NLS-1$
        }
        JsonObject schema = tool.getAsJsonObject("inputSchema"); //$NON-NLS-1$
        if (schema == null || !schema.has("properties")) //$NON-NLS-1$
        {
            return null;
        }
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        if (!properties.has(property) || !properties.get(property).isJsonObject())
        {
            return null;
        }
        JsonElement description = properties.getAsJsonObject(property).get("description"); //$NON-NLS-1$
        return description == null || description.isJsonNull() ? null : description.getAsString();
    }

    /**
     * The same value with every schema description removed.
     * <p>
     * Inside {@code properties} the keys are parameter names rather than schema keywords, so a
     * parameter that happens to be called description stays while the keyword of that spelling
     * goes. These two schemas declare no such parameter today; treating the name map as a schema
     * would quietly drop one if it ever appeared, so the distinction is kept here as well.
     * </p>
     *
     * @param element any part of a tool entry.
     * @return a copy without prose.
     */
    private static JsonElement withoutDescriptions(JsonElement element)
    {
        if (element.isJsonArray())
        {
            JsonArray copied = new JsonArray();
            for (JsonElement item : element.getAsJsonArray())
            {
                copied.add(withoutDescriptions(item));
            }
            return copied;
        }
        if (!element.isJsonObject())
        {
            return element;
        }
        JsonObject source = element.getAsJsonObject();
        JsonObject copied = new JsonObject();
        for (String key : new TreeSet<>(source.keySet()))
        {
            if ("description".equals(key)) //$NON-NLS-1$
            {
                continue;
            }
            JsonElement value = source.get(key);
            if ("properties".equals(key) && value.isJsonObject()) //$NON-NLS-1$
            {
                JsonObject named = new JsonObject();
                JsonObject declared = value.getAsJsonObject();
                for (String name : new TreeSet<>(declared.keySet()))
                {
                    named.add(name, withoutDescriptions(declared.get(name)));
                }
                copied.add(key, named);
            }
            else
            {
                copied.add(key, withoutDescriptions(value));
            }
        }
        return copied;
    }

    /** Every {@code description} inside a schema's properties, however deeply nested. */
    private static int proseOf(JsonElement element)
    {
        int bytes = 0;
        if (element == null)
        {
            return bytes;
        }
        if (element.isJsonObject())
        {
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet())
            {
                if ("description".equals(member.getKey()) && member.getValue().isJsonPrimitive()) //$NON-NLS-1$
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

    private Map<String, JsonObject> toolsByName() throws IOException
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
        JsonArray tools = document.getAsJsonObject("result").getAsJsonArray("tools"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, JsonObject> named = new HashMap<>();
        for (JsonElement element : tools)
        {
            JsonObject tool = element.getAsJsonObject();
            named.put(tool.get("name").getAsString(), tool); //$NON-NLS-1$
        }
        return named;
    }

    private static String resource(String name) throws IOException
    {
        try (InputStream stream = TheTwoToolDescriptionsAreSmallerWithoutLosingTheirContractTest.class
            .getResourceAsStream(name))
        {
            if (stream == null)
            {
                fail("missing test resource " + name); //$NON-NLS-1$
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
