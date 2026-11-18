/**
 * AI-EDT Ordinary Forms - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */
package ru.aiedt.mcp.oform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.ops.OperationsCatalog;

/** Each visible parameter retains its pinned schema and independently pinned full operation help. */
@RunWith(Parameterized.class)
public class TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest
{
    static final List<String> COMMON = List.of("projectName", "formFqn", "modulePath", "dryRun", "language");

    private final String parameter;

    /**
     * Select one parameter without reading current production descriptions as expectations.
     *
     * @param parameter the frozen parameter name
     */
    public TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest(String parameter)
    {
        this.parameter = parameter;
    }

    /**
     * Enumerate every parameter of the independently frozen schema.
     *
     * @return all 57 parameter cases
     * @throws Exception if the immutable fixture is missing or malformed
     */
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> parameters() throws Exception
    {
        List<Object[]> cases = new ArrayList<>();
        for (String name : contract().getAsJsonObject("expectedSchema").getAsJsonObject("properties").keySet())
        {
            cases.add(new Object[] { name });
        }
        assertEquals(57, cases.size());
        return cases;
    }

    /** The exact rendered description, type and other property constraints must match the pinned contract. */
    @Test
    public void visibleDefinitionMatchesThePinnedContract() throws Exception
    {
        JsonObject actual = JsonParser.parseString(new OrdinaryFormsTool().getInputSchema()).getAsJsonObject();
        verifyVisible(contract(), parameter, actual);
    }

    /** Full help keeps every original operation-specific meaning and every common argument definition. */
    @Test
    public void fullHelpDefinitionsRemainUnchanged() throws Exception
    {
        JsonObject frozen = contract();
        JsonObject before = frozen.getAsJsonObject("before");
        JsonObject operations = before.getAsJsonObject("operations");
        if (parameter.equals("operation"))
        {
            assertEquals(List.copyOf(operations.keySet()), OperationsCatalog.knownNames());
            JsonObject general = JsonParser.parseString(new OrdinaryFormsTool().execute(Map.of("operation", "help")))
                .getAsJsonObject();
            assertEquals(before.get("generalHelp"), general);
            return;
        }
        for (String operation : operations.keySet())
        {
            JsonObject expectedArguments = operations.getAsJsonObject(operation).getAsJsonObject("arguments");
            if (COMMON.contains(parameter) || expectedArguments.has(parameter))
            {
                verifyHelp(frozen, operation, parameter, help(operation));
            }
        }
    }

    /**
     * Compare one visible definition against the fixture, including its complete operation attribution.
     *
     * @param frozen the immutable pre-production contract
     * @param parameter the parameter being checked
     * @param actual the actual or deliberately damaged schema
     */
    static void verifyVisible(JsonObject frozen, String parameter, JsonObject actual)
    {
        assertEquals("visible." + parameter,
            frozen.getAsJsonObject("expectedSchema").getAsJsonObject("properties").get(parameter),
            actual.getAsJsonObject("properties").get(parameter));
    }

    /**
     * Compare a full help definition to its independent pre-shortening value.
     *
     * @param frozen the immutable pre-production contract
     * @param operation the topic whose help is being checked
     * @param parameter the common or operation-specific argument
     * @param details the actual or deliberately damaged operationHelp body
     */
    static void verifyHelp(JsonObject frozen, String operation, String parameter, JsonObject details)
    {
        JsonObject before = frozen.getAsJsonObject("before");
        if (COMMON.contains(parameter))
        {
            assertEquals("help." + operation + "." + parameter,
                before.getAsJsonObject("schema").getAsJsonObject("properties").get(parameter),
                details.getAsJsonObject("commonArguments").get(parameter));
        }
        else
        {
            assertEquals("help." + operation + "." + parameter,
                before.getAsJsonObject("operations").getAsJsonObject(operation).getAsJsonObject("arguments").get(parameter),
                details.getAsJsonObject("arguments").get(parameter));
        }
    }

    /**
     * Read only projectless help; never execute the operation described by the topic.
     *
     * @param operation the exact registered topic name
     * @return its actual operationHelp object
     */
    static JsonObject help(String operation)
    {
        JsonObject answer = JsonParser.parseString(new OrdinaryFormsTool().execute(
            Map.of("operation", "help", "topic", operation))).getAsJsonObject();
        return answer.getAsJsonObject("operationHelp");
    }

    /**
     * Load the independent contract frozen before production changes.
     *
     * @return the literal before/expected schema and operation meanings
     * @throws Exception if the fixture is absent or malformed
     */
    static JsonObject contract() throws Exception
    {
        try (InputStream stream = TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest.class
            .getResourceAsStream("ordinary-disclosure-contract.json"))
        {
            assertNotNull("ordinary disclosure contract fixture", stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
