/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.ops.OperationSpec;
import ru.aiedt.mcp.oform.ops.OperationsCatalog;
import ru.aiedt.mcp.oform.ops.ParamSpec;

/** The addressable help describes an operation without executing it or resolving a project. */
public class TheOrdinaryTopicHelpTest
{
    private static final List<String> COMMON = List.of("projectName", "formFqn", "modulePath", "dryRun", "language");

    /** Record the actual runtime schema, general help and every raw operation contract for comparison. */
    @BeforeClass
    public static void recordTheRuntimeContract()
    {
        JsonObject snapshot = new JsonObject();
        snapshot.add("schema", JsonParser.parseString(new OrdinaryFormsTool().getInputSchema()));
        snapshot.add("generalHelp", answer(Map.of("operation", "help")));
        JsonObject operations = new JsonObject();
        for (String name : OperationsCatalog.knownNames())
        {
            OperationSpec spec = OperationsCatalog.find(name);
            JsonObject operation = new JsonObject();
            operation.addProperty("operation", spec.name);
            operation.addProperty("kind", spec.kind.name());
            operation.addProperty("writes", spec.writes);
            operation.addProperty("needsProject", spec.needsProject);
            operation.addProperty("summary", spec.help);
            operation.add("arguments", arguments(spec));
            operations.add(name, operation);
        }
        snapshot.add("operations", operations);
        System.out.println("ORDINARY_TOPIC_RUNTIME_BASE64=" + Base64.getEncoder()
            .encodeToString(snapshot.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** The new argument is optional, string typed and last; operation stays an open string. */
    @Test
    public void topicIsAnOptionalLastString()
    {
        JsonObject schema = JsonParser.parseString(new OrdinaryFormsTool().getInputSchema()).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties");
        assertTrue("help must declare its topic argument", properties.has("topic"));
        assertEquals(57, properties.size());
        assertEquals("topic", List.copyOf(properties.keySet()).get(56));
        assertEquals("string", properties.getAsJsonObject("topic").get("type").getAsString());
        assertFalse(properties.getAsJsonObject("operation").has("enum"));
        assertFalse(properties.getAsJsonObject("operation").has("const"));
        if (schema.has("required"))
        {
            for (com.google.gson.JsonElement value : schema.getAsJsonArray("required"))
            {
                assertFalse(value.getAsString().equals("topic"));
            }
        }
    }

    /** Every registered operation exposes its full raw contract, including dynamic descriptions. */
    @Test
    public void everyTopicDescribesItsRegisteredContract()
    {
        for (String name : OperationsCatalog.knownNames())
        {
            OperationSpec spec = OperationsCatalog.find(name);
            JsonObject details = details(name);
            assertEquals(name, details.get("operation").getAsString());
            assertEquals(name, spec.kind.name(), details.get("kind").getAsString());
            assertEquals(name, spec.writes, details.get("writes").getAsBoolean());
            assertEquals(name, spec.needsProject, details.get("needsProject").getAsBoolean());
            assertEquals(name, spec.help, details.get("summary").getAsString());
            JsonObject actual = details.getAsJsonObject("arguments");
            assertEquals(name, arguments(spec), actual);
            assertEquals(name, spec.params.stream().map(param -> param.name).toList(), List.copyOf(actual.keySet()));
        }
    }

    /** A topic documents all common arguments without changing their descriptions or types. */
    @Test
    public void everyTopicIncludesTheCommonArguments()
    {
        JsonObject properties = JsonParser.parseString(new OrdinaryFormsTool().getInputSchema()).getAsJsonObject()
            .getAsJsonObject("properties");
        for (String name : OperationsCatalog.knownNames())
        {
            JsonObject common = details(name).getAsJsonObject("commonArguments");
            assertNotNull(name, common);
            assertEquals(name, COMMON, List.copyOf(common.keySet()));
            for (String argument : COMMON)
            {
                assertEquals(name + "." + argument, properties.get(argument), common.get(argument));
            }
        }
    }

    /** Missing, empty and whitespace-only topics retain the general catalog response. */
    @Test
    public void blankTopicsKeepTheGeneralHelp()
    {
        JsonObject general = answer(Map.of("operation", "help"));
        assertTrue(general.get("success").getAsBoolean());
        assertFalse(general.has("operationHelp"));
        assertEquals(general, answer(Map.of("operation", "help", "topic", "")));
        assertEquals(general, answer(Map.of("operation", "help", "topic", "   ")));
    }

    /** Unknown topics are refused by name, not answered with an unrelated successful catalog. */
    @Test
    public void anUnknownTopicIsAnExplicitRefusal()
    {
        JsonObject response = answer(Map.of("operation", "help", "topic", "paint_form"));
        assertFalse("an unknown topic must not report success", response.get("success").getAsBoolean());
        assertTrue(response.toString(), response.toString().contains("Unknown help topic: paint_form"));
        assertTrue(response.toString(), response.toString().contains("add_column"));
    }

    /** Topic lookup uses the same whitespace and case normalization as operation lookup. */
    @Test
    public void topicLookupNormalizesCaseAndWhitespace()
    {
        assertEquals(details("audit"), details(" AUDIT "));
    }

    /** Describing a writer requires neither a project nor access to its form or handler. */
    @Test
    public void describingAWriterDoesNotResolveItsProject()
    {
        JsonObject normal = answer(Map.of("operation", "help", "topic", "create_form"));
        JsonObject invalidProject = answer(Map.of("operation", "help", "topic", "create_form",
            "projectName", "nonexistent-help-project", "formFqn", "invalid-form", "dryRun", "false"));
        assertEquals(normal, invalidProject);
        assertTrue(details("create_form").get("writes").getAsBoolean());
    }

    /** Full writing help retains the dry-run, limited-record and infobase synchronization conditions. */
    @Test
    public void writingTopicsKeepTheWriteConditions()
    {
        for (String name : OperationsCatalog.writingNames())
        {
            JsonObject response = answer(Map.of("operation", "help", "topic", name));
            assertTrue(name, response.has("operationHelp"));
            assertTrue(name, response.has("writes"));
            String conditions = response.get("writes").getAsString();
            for (String fact : List.of("dryRun", "only the records", "sync baselines", "update_database"))
            {
                assertTrue(name + ": " + fact, conditions.contains(fact));
            }
        }
    }

    /** Only help declares topic; the other operation-specific argument sets are unchanged. */
    @Test
    public void topicBelongsOnlyToHelp()
    {
        for (String name : OperationsCatalog.knownNames())
        {
            boolean declaresTopic = OperationsCatalog.find(name).params.stream().anyMatch(param -> param.name.equals("topic"));
            assertEquals(name, name.equals("help"), declaresTopic);
        }
    }

    /**
     * Read a topic response without executing its described operation.
     *
     * @param topic the operation to describe
     * @return the operation contract
     */
    private static JsonObject details(String topic)
    {
        JsonObject response = answer(Map.of("operation", "help", "topic", topic));
        assertTrue(topic, response.get("success").getAsBoolean());
        assertTrue("help topic must return operationHelp: " + topic, response.has("operationHelp"));
        return response.getAsJsonObject("operationHelp");
    }

    /**
     * Encode the declared operation-specific parameters without deriving undocumented defaults.
     *
     * @param spec the existing operation registration
     * @return parameter names, types and complete descriptions
     */
    private static JsonObject arguments(OperationSpec spec)
    {
        JsonObject parameters = new JsonObject();
        for (ParamSpec param : spec.params)
        {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", param.type.name().toLowerCase(Locale.ROOT));
            entry.addProperty("description", param.description());
            parameters.add(param.name, entry);
        }
        return parameters;
    }

    /**
     * Execute only the requested help query and parse its JSON envelope.
     *
     * @param params tool arguments
     * @return the actual tool response
     */
    private static JsonObject answer(Map<String, String> params)
    {
        return JsonParser.parseString(new OrdinaryFormsTool().execute(params)).getAsJsonObject();
    }
}
