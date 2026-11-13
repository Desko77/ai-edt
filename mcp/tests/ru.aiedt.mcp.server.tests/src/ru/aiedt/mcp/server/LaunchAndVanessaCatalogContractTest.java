/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.server;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.Test;
import com.google.gson.*;
import ru.aiedt.mcp.server.toolkit.ops.LaunchDebuggerTool;

/** Contract of the launch and scenario catalog entries against their published records. */
public class LaunchAndVanessaCatalogContractTest
{
    /** The wire records retain their non-prose shape and weigh less in compact JSON.
     * @throws Exception if the endpoint or baseline cannot be read
     */
    @Test
    public void shapeAndWeight() throws Exception
    {
        try (LiveServer server = LiveServer.start())
        {
            JsonArray tools = tools(server);
            int beforeTotal = 0;
            int afterTotal = 0;
            for (String[] item : new String[][] {{"launch_debugger", "launch-debugger"},
                {"vanessa", "vanessa"}})
            {
                JsonObject before = baseline(item[1]);
                JsonObject after = record(tools, item[0]);
                assertEquals(withoutDescriptions(before), withoutDescriptions(after));
                int oldBytes = compactBytes(before);
                int newBytes = compactBytes(after);
                assertTrue(item[0] + " did not shrink", newBytes < oldBytes);
                beforeTotal += oldBytes;
                afterTotal += newBytes;
            }
            assertTrue("combined record did not shrink", afterTotal < beforeTotal);
        }
    }

    /** Critical launch properties and advertised action reachability remain unchanged.
     * @throws Exception if the endpoint or baseline cannot be read
     */
    @Test
    public void launchConditionsAndActions() throws Exception
    {
        try (LiveServer server = LiveServer.start())
        {
            JsonObject before = properties(baseline("launch-debugger"));
            JsonObject after = properties(record(tools(server), "launch_debugger"));
            assertEquals(40, before.size());
            assertEquals(before.keySet(), after.keySet());
            java.util.Set<String> changedDescriptions = java.util.Set.of(
                "action", "runMode", "clientType", "updateBeforeLaunch",
                "externalObjectName", "launchConfigurationName", "enableExternalObjectDump", "startupOption",
                "threadId", "waitForEndpoint", "breakpoints", "breakpointEnabled",
                "catchAll", "scope", "timeoutSeconds", "replaceModuleSet",
                "message", "externalObjectProject", "endpointTimeoutSeconds", "topic");
            for (String property : before.keySet())
            {
                if (changedDescriptions.contains(property))
                    assertEquals(property, withoutDescriptions(before.get(property)),
                        withoutDescriptions(after.get(property)));
                else
                    assertEquals(property, before.get(property), after.get(property));
            }
            String startup = description(before, "startupOption");
            assertTrue(startup.contains("/C only"));
            assertTrue(startup.contains("/Execute"));
            assertTrue(startup.contains("saved configuration is not changed"));
            assertTrue(description(before, "enableExternalObjectDump").contains("refuses"));
            assertTrue(description(before, "updateBeforeLaunch").contains("explicit true is refused"));
            String topic = new LaunchDebuggerTool().execute(Map.of("action", "help", "topic", "workflow"));
            assertTrue(topic.contains("external_object_workshop operation=import_external_object"));
            assertTrue(topic.contains("enableExternalObjectDump=true"));
            assertTrue(topic.contains("Standalone compatibility tools"));
            assertTrue(topic.contains("evaluate_expression"));
        }
    }

    /** Scenario safety and readiness conditions remain at their original properties.
     * @throws Exception if the endpoint or baseline cannot be read
     */
    @Test
    public void scenarioConditions() throws Exception
    {
        try (LiveServer server = LiveServer.start())
        {
            JsonObject before = properties(baseline("vanessa"));
            JsonObject after = properties(record(tools(server), "vanessa"));
            assertEquals(30, before.size());
            assertEquals(before.keySet(), after.keySet());
            java.util.Set<String> changedDescriptions = java.util.Set.of(
                "connectionString", "testManager", "testClient", "featurePath",
                "listKind", "infobaseUser", "vanessaParams", "formToOpen",
                "openStep", "async", "windowTitle", "scenarioText",
                "keepOpen", "whenSeveral", "cancel", "testClientPort",
                "startStep", "projectName", "buttonTitle", "buttonName", "timeoutSeconds");
            for (String property : before.keySet())
            {
                if (changedDescriptions.contains(property))
                    assertEquals(property, withoutDescriptions(before.get(property)),
                        withoutDescriptions(after.get(property)));
                else
                    assertEquals(property, before.get(property), after.get(property));
            }
            String featureBefore = description(before, "featurePath");
            String featureAfter = description(after, "featurePath");
            assertTrue(featureBefore.contains("runKey"));
            assertTrue(featureBefore.contains("relative path"));
            assertTrue(featureAfter.contains("File calls"));
            assertTrue(featureAfter.contains("scenarioText"));
            assertTrue(featureAfter.contains("formToOpen"));
            assertTrue(featureAfter.contains("composed list action"));
            assertTrue(featureAfter.contains("runKey"));
            assertTrue(featureAfter.contains("relative path"));
            assertTrue(description(before, "scenarioText").contains("both are refused"));
            assertTrue(description(after, "scenarioText").contains("Refused with featurePath"));
            assertTrue(description(before, "vanessaParams").contains("refused"));
            assertTrue(description(after, "vanessaParams").contains("refused"));
            assertTrue(description(before, "formToOpen").contains("neither featurePath nor scenarioText"));
            assertTrue(description(after, "formToOpen").contains("not combined with featurePath/scenarioText"));
            assertTrue(description(before, "openStep").contains("{form}"));
            assertTrue(description(before, "openStep").contains("common form"));
            assertTrue(description(after, "openStep").contains("{form}"));
            assertTrue(description(after, "openStep").contains("common form"));
            assertTrue(description(after, "openStep").contains("version-specific"));
            assertTrue(description(before, "scenarioText").contains("temporary directory"));
            assertTrue(description(after, "scenarioText").contains("temporary run directory"));
            assertTrue(description(before, "vanessaParams").contains("ignores one"));
            assertTrue(description(after, "vanessaParams").contains("unknown names ignored"));
            assertTrue(description(before, "formToOpen").contains("whatever covers"));
            assertTrue(description(after, "formToOpen").contains("even when covered"));
            String published = record(tools(server), "vanessa").get("description").getAsString();
            for (String fact : new String[] {"yaxunit_tests", "code", "featurePath", "scenarioText",
                "formToOpen", "exactly one", "without any of those three", "listKind", "listName",
                "column", "columnValue", "buttonTitle or buttonName", "windowWaitSeconds",
                "testManager and testClient", "default", "refuse false", "does not ask to raise timeoutSeconds",
                "vanessa-automation.epf", "thick client", "blockingWindows"})
                assertTrue(fact, published.contains(fact));
            assertTrue(description(after, "connectionString").contains("Pwd refused"));
            assertTrue(description(after, "screenshots").contains("default true"));
            assertTrue(description(after, "startStep").contains("default launches TestClient or attaches to a running one"));
        }
    }

    /** A property literally named description remains part of the schema shape. */
    @Test
    public void descriptionNamedArgumentSurvives()
    {
        JsonObject source = JsonParser.parseString(
            "{\"description\":\"tool prose\",\"properties\":{\"description\":{\"type\":\"string\",\"description\":\"argument prose\"}}}")
            .getAsJsonObject();
        JsonObject result = withoutDescriptions(source).getAsJsonObject();
        assertFalse(result.has("description"));
        JsonObject argument = result.getAsJsonObject("properties").getAsJsonObject("description");
        assertEquals("string", argument.get("type").getAsString());
        assertFalse(argument.has("description"));
    }

    /** Reads a supplied runtime record. */
    private static JsonObject baseline(String suffix) throws Exception
    {
        try (InputStream stream = LaunchAndVanessaCatalogContractTest.class.getResourceAsStream(
            "catalog-runtime-baseline-" + suffix + ".json"))
        {
            assertNotNull(stream);
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** Reads the advertised catalog from the endpoint. */
    private static JsonArray tools(LiveServer server) throws Exception
    {
        LiveServer.Response response = server.request("POST", "/mcp", LiveServer.headers(
            "Content-Type", "application/json", "Accept", "application/json", "Authorization", server.bearer()),
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        return JsonParser.parseString(response.body).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("tools");
    }

    /** Finds a named record in the advertised catalog. */
    private static JsonObject record(JsonArray tools, String name)
    {
        for (JsonElement item : tools)
        {
            if (name.equals(item.getAsJsonObject().get("name").getAsString()))
                return item.getAsJsonObject();
        }
        throw new AssertionError("Missing tool: " + name);
    }

    /** Returns the schema properties of a record. */
    private static JsonObject properties(JsonObject record)
    {
        return record.getAsJsonObject("inputSchema").getAsJsonObject("properties");
    }

    /** Returns the prose of one property. */
    private static String description(JsonObject properties, String name)
    {
        return properties.getAsJsonObject(name).get("description").getAsString();
    }

    /** Measures both records using the same compact Gson serialization. */
    private static int compactBytes(JsonObject record)
    {
        return new Gson().toJson(record).getBytes(StandardCharsets.UTF_8).length;
    }

    /** Recursively removes prose while retaining all other schema and catalog fields. */
    private static JsonElement withoutDescriptions(JsonElement value)
    {
        if (value.isJsonObject())
        {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet())
            {
                if ("description".equals(entry.getKey()))
                    continue;
                if ("properties".equals(entry.getKey()) && entry.getValue().isJsonObject())
                {
                    JsonObject named = new JsonObject();
                    for (Map.Entry<String, JsonElement> property : entry.getValue().getAsJsonObject().entrySet())
                        named.add(property.getKey(), withoutDescriptions(property.getValue()));
                    result.add(entry.getKey(), named);
                }
                else
                    result.add(entry.getKey(), withoutDescriptions(entry.getValue()));
            }
            return result;
        }
        if (value.isJsonArray())
        {
            JsonArray result = new JsonArray();
            for (JsonElement item : value.getAsJsonArray())
                result.add(withoutDescriptions(item));
            return result;
        }
        return value;
    }
}
