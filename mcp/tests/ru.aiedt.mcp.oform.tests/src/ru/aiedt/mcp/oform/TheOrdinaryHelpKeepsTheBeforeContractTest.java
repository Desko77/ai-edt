/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.ops.OperationsCatalog;

/** Frozen runtime contracts preserve the complete general help, schema shape and operation facts. */
public class TheOrdinaryHelpKeepsTheBeforeContractTest
{
    private static final List<String> COMMON = List.of("projectName", "formFqn", "modulePath", "dryRun", "language");

    /** Every original schema field and structural constraint remains; only topic and descriptions may differ. */
    @Test
    public void everyOriginalSchemaConstraintRemains() throws Exception
    {
        JsonObject before = baseline().getAsJsonObject("schema");
        JsonObject after = JsonParser.parseString(new OrdinaryFormsTool().getInputSchema()).getAsJsonObject();
        after.getAsJsonObject("properties").remove("topic");
        assertEquals(List.copyOf(before.getAsJsonObject("properties").keySet()),
            List.copyOf(after.getAsJsonObject("properties").keySet()));
        assertEquals(withoutParameterDescriptions(before), withoutParameterDescriptions(after));
    }

    /** The complete general help stays equal to the response captured before the implementation. */
    @Test
    public void theGeneralHelpIsUnchanged() throws Exception
    {
        assertEquals(baseline().get("generalHelp"), answer(""));
    }

    /** All original operation facts and common parameter descriptions survive in the addressable help. */
    @Test
    public void everyOriginalOperationFactSurvives() throws Exception
    {
        JsonObject before = baseline();
        JsonObject operations = before.getAsJsonObject("operations");
        assertEquals(24, operations.size());
        assertEquals(List.copyOf(operations.keySet()), OperationsCatalog.knownNames());
        for (String name : operations.keySet())
        {
            JsonObject response = answer(name);
            verifyOperation(before, name, response.getAsJsonObject("operationHelp"));
            if (operations.getAsJsonObject(name).get("writes").getAsBoolean())
            {
                assertEquals(name, before.getAsJsonObject("generalHelp").get("writes"), response.get("writes"));
            }
        }
    }

    /** Exact description grouping reduces the schema even after adding the optional topic argument. */
    @Test
    public void theSchemaIsSmallerThanTheCapturedBaseline() throws Exception
    {
        int before = baseline().get("schema").toString().getBytes(StandardCharsets.UTF_8).length;
        int after = JsonParser.parseString(new OrdinaryFormsTool().getInputSchema()).toString()
            .getBytes(StandardCharsets.UTF_8).length;
        assertTrue("ordinary schema bytes before=" + before + " after=" + after, after < before);
    }

    /** Removing any original argument or common argument is rejected by the frozen contract check. */
    @Test
    public void deletingAnyOriginalArgumentIsRejected() throws Exception
    {
        JsonObject before = baseline();
        JsonObject operations = before.getAsJsonObject("operations");
        for (String name : operations.keySet())
        {
            JsonObject details = answer(name).getAsJsonObject("operationHelp");
            for (String argument : operations.getAsJsonObject(name).getAsJsonObject("arguments").keySet())
            {
                JsonObject damaged = details.deepCopy();
                damaged.getAsJsonObject("arguments").remove(argument);
                assertRejected(before, name, damaged);
            }
            for (String argument : COMMON)
            {
                JsonObject damaged = details.deepCopy();
                damaged.getAsJsonObject("commonArguments").remove(argument);
                assertRejected(before, name, damaged);
            }
        }
    }

    /** Every operation retains its exact pinned visible meaning; complete meanings remain guarded in help. */
    @Test
    public void theSchemaKeepsEveryOriginalMeaning() throws Exception
    {
        verifySchemaMeanings(baseline(), JsonParser.parseString(new OrdinaryFormsTool().getInputSchema()).getAsJsonObject());
    }

    /** Removing each original description from the schema is rejected independently of the addressable help. */
    @Test
    public void deletingAnySchemaMeaningIsRejected() throws Exception
    {
        JsonObject before = baseline();
        JsonObject schema = JsonParser.parseString(new OrdinaryFormsTool().getInputSchema()).getAsJsonObject();
        JsonObject visible = TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest.contract().getAsJsonObject("schemaMeanings");
        for (String operation : before.getAsJsonObject("operations").keySet())
        {
            JsonObject arguments = before.getAsJsonObject("operations").getAsJsonObject(operation).getAsJsonObject("arguments");
            for (String name : arguments.keySet())
            {
                String meaning = visible.has(operation) && visible.getAsJsonObject(operation).has(name)
                    ? visible.getAsJsonObject(operation).get(name).getAsString()
                    : arguments.getAsJsonObject(name).get("description").getAsString();
                JsonObject damaged = schema.deepCopy();
                JsonObject property = damaged.getAsJsonObject("properties").getAsJsonObject(name);
                property.addProperty("description", removeOperationMeaning(property.get("description").getAsString(), operation, meaning));
                boolean rejected = false;
                try
                {
                    verifySchemaMeanings(before, damaged);
                }
                catch (AssertionError expected)
                {
                    assertEquals(operation + "." + name + " must retain its pinned visible meaning", expected.getMessage());
                    rejected = true;
                }
                assertTrue(operation + "." + name + " must reject schema fact loss", rejected);
            }
        }
    }

    /**
     * Remove one operation's attribution while preserving other operations and unrelated description segments.
     *
     * @param description the complete schema description
     * @param operation the operation whose meaning is removed
     * @param meaning the exact frozen description
     * @return a description missing only the selected operation/meaning association
     */
    private static String removeOperationMeaning(String description, String operation, String meaning)
    {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^| )([a-z_]+(?:, [a-z_]+)*): "
            + java.util.regex.Pattern.quote(meaning) + "(?= |$)").matcher(description);
        while (matcher.find())
        {
            java.util.List<String> names = new java.util.ArrayList<>(List.of(matcher.group(1).split(", ")));
            if (names.remove(operation))
            {
                if (names.isEmpty())
                {
                    return description.substring(0, matcher.start(1)) + description.substring(matcher.end());
                }
                return description.substring(0, matcher.start(1)) + String.join(", ", names)
                    + description.substring(matcher.end(1));
            }
        }
        throw new AssertionError(operation + " has no source meaning to remove");
    }

    /**
     * Check every frozen operation/argument meaning against a complete operation-prefixed schema fragment.
     *
     * @param before the frozen operation contracts
     * @param schema the actual or deliberately damaged schema
     */
    private static void verifySchemaMeanings(JsonObject before, JsonObject schema) throws Exception
    {
        JsonObject visible = TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest.contract().getAsJsonObject("schemaMeanings");
        for (String operation : before.getAsJsonObject("operations").keySet())
        {
            JsonObject arguments = before.getAsJsonObject("operations").getAsJsonObject(operation).getAsJsonObject("arguments");
            for (String name : arguments.keySet())
            {
                String meaning = visible.has(operation) && visible.getAsJsonObject(operation).has(name)
                    ? visible.getAsJsonObject(operation).get(name).getAsString()
                    : arguments.getAsJsonObject(name).get("description").getAsString();
                String description = schema.getAsJsonObject("properties").getAsJsonObject(name).get("description").getAsString();
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^| )([a-z_]+(?:, [a-z_]+)*): "
                    + java.util.regex.Pattern.quote(meaning) + "(?= |$)").matcher(description);
                boolean found = false;
                while (matcher.find())
                {
                    if (List.of(matcher.group(1).split(", ")).contains(operation))
                    {
                        found = true;
                    }
                }
                assertTrue(operation + "." + name + " must retain its pinned visible meaning", found);
            }
        }
    }

    /**
     * Verify an operation against the old runtime, allowing only help's newly declared topic.
     *
     * @param before the frozen runtime contract
     * @param name the operation name
     * @param details the actual or deliberately damaged help body
     */
    private static void verifyOperation(JsonObject before, String name, JsonObject details)
    {
        assertNotNull(name, details);
        JsonObject actual = details.deepCopy();
        JsonElement commonElement = actual.remove("commonArguments");
        assertNotNull(name, commonElement);
        JsonObject common = commonElement.getAsJsonObject();
        assertEquals(name, COMMON, List.copyOf(common.keySet()));
        for (String argument : COMMON)
        {
            assertEquals(name + "." + argument, before.getAsJsonObject("schema").getAsJsonObject("properties")
                .get(argument), common.get(argument));
        }
        if (name.equals("help"))
        {
            actual.getAsJsonObject("arguments").remove("topic");
        }
        assertEquals(name, before.getAsJsonObject("operations").get(name), actual);
    }

    /**
     * Confirm that the frozen verifier rejects a deliberately removed argument.
     *
     * @param before the frozen runtime contract
     * @param name the operation name
     * @param damaged a help body missing an original argument
     */
    private static void assertRejected(JsonObject before, String name, JsonObject damaged)
    {
        boolean rejected = false;
        try
        {
            verifyOperation(before, name, damaged);
        }
        catch (AssertionError expected)
        {
            rejected = true;
        }
        assertTrue(name + " must reject argument loss", rejected);
    }

    /**
     * Remove only documentation strings from actual schema property definitions, not arbitrary data fields.
     *
     * @param schema the complete schema
     * @return a copy preserving every non-description field
     */
    private static JsonObject withoutParameterDescriptions(JsonObject schema)
    {
        JsonObject out = schema.deepCopy();
        JsonObject properties = out.getAsJsonObject("properties");
        for (String name : properties.keySet())
        {
            properties.getAsJsonObject(name).remove("description");
        }
        return out;
    }

    /**
     * Load the actual pre-implementation runtime recorded during the independent RED run.
     *
     * @return the frozen schema, general help and operation contracts
     * @throws Exception if the fixture is absent or unreadable
     */
    private static JsonObject baseline() throws Exception
    {
        try (InputStream stream = TheOrdinaryHelpKeepsTheBeforeContractTest.class
            .getResourceAsStream("ordinary-topic-before-contract.json"))
        {
            assertNotNull("ordinary runtime baseline fixture", stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /**
     * Request help only, without project resolution or execution of the described operation.
     *
     * @param topic an operation name or empty string for the general help
     * @return the actual JSON response
     */
    private static JsonObject answer(String topic)
    {
        return JsonParser.parseString(new OrdinaryFormsTool().execute(Map.of("operation", "help", "topic", topic)))
            .getAsJsonObject();
    }
}
