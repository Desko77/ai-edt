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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.oform.ops.ParamSpec;

/** Independent literals reject lost schema facts, lost help facts, false attribution and type changes. */
public class TheOrdinaryDisclosureOracleRejectsLostFactsTest
{
    /** Every pinned critical fragment must remain in the schema even when full help still contains it. */
    @Test
    public void removingEachCriticalSchemaFactIsRejected() throws Exception
    {
        JsonObject frozen = TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest.contract();
        for (JsonElement item : facts().getAsJsonArray("criticalSchemaFacts"))
        {
            JsonObject fact = item.getAsJsonObject();
            String name = fact.get("parameter").getAsString();
            String fragment = fact.get("fragment").getAsString();
            JsonObject damaged = frozen.getAsJsonObject("expectedSchema").deepCopy();
            JsonObject property = damaged.getAsJsonObject("properties").getAsJsonObject(name);
            String original = property.get("description").getAsString();
            assertTrue(name + " contains the pinned fact", original.contains(fragment));
            property.addProperty("description", original.replace(fragment, ""));
            rejected("visible." + name, () -> TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest
                .verifyVisible(frozen, name, damaged));
        }
    }

    /** Removing one moved detail from help is rejected while the correct short schema remains intact. */
    @Test
    public void removingEachMovedHelpFactIsRejected() throws Exception
    {
        JsonObject frozen = TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest.contract();
        int count = 0;
        for (JsonElement item : facts().getAsJsonArray("movedFacts"))
        {
            JsonObject fact = item.getAsJsonObject();
            String operation = fact.get("operation").getAsString();
            String parameter = fact.get("parameter").getAsString();
            String fragment = fact.get("fragment").getAsString();
            JsonObject details = frozen.getAsJsonObject("before").getAsJsonObject("operations")
                .getAsJsonObject(operation).deepCopy();
            JsonObject argument = details.getAsJsonObject("arguments").getAsJsonObject(parameter);
            String original = argument.get("description").getAsString();
            assertTrue(operation + "." + parameter + " contains the moved fact", original.contains(fragment));
            argument.addProperty("description", original.replace(fragment, ""));
            rejected("help." + operation + "." + parameter,
                () -> TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest.verifyHelp(frozen, operation, parameter, details));
            count++;
        }
        assertEquals(21, count);
    }

    /** Removing one operation from any shortened group must not be hidden by its surviving neighbors. */
    @Test
    public void removingEachShortMeaningAttributionIsRejected() throws Exception
    {
        JsonObject frozen = TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest.contract();
        JsonObject meanings = frozen.getAsJsonObject("schemaMeanings");
        for (String operation : meanings.keySet())
        {
            for (String parameter : meanings.getAsJsonObject(operation).keySet())
            {
                JsonObject damaged = frozen.getAsJsonObject("expectedSchema").deepCopy();
                JsonObject property = damaged.getAsJsonObject("properties").getAsJsonObject(parameter);
                property.addProperty("description", withoutOperation(property.get("description").getAsString(), operation));
                rejected("visible." + parameter,
                    () -> TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest.verifyVisible(frozen, parameter, damaged));
            }
        }
    }

    /** Losing the same critical refusal in both channels must fail both independent oracles. */
    @Test
    public void removingTheSameFactFromSchemaAndHelpIsRejected() throws Exception
    {
        JsonObject frozen = TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest.contract();
        String fragment = "this argument is refused for them";
        JsonObject schema = frozen.getAsJsonObject("expectedSchema").deepCopy();
        JsonObject property = schema.getAsJsonObject("properties").getAsJsonObject("data");
        assertTrue(property.get("description").getAsString().contains(fragment));
        property.addProperty("description", property.get("description").getAsString().replace(fragment, ""));
        JsonObject details = frozen.getAsJsonObject("before").getAsJsonObject("operations")
            .getAsJsonObject("add_element").deepCopy();
        JsonObject argument = details.getAsJsonObject("arguments").getAsJsonObject("data");
        assertTrue(argument.get("description").getAsString().contains(fragment));
        argument.addProperty("description", argument.get("description").getAsString().replace(fragment, ""));
        rejected("visible.data", () -> TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest
            .verifyVisible(frozen, "data", schema));
        rejected("help.add_element.data", () -> TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest
            .verifyHelp(frozen, "add_element", "data", details));
    }

    /** Every parameter's type remains independently guarded without reading it from production expectations. */
    @Test
    public void changingEachParameterTypeIsRejected() throws Exception
    {
        JsonObject frozen = TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest.contract();
        JsonObject properties = frozen.getAsJsonObject("expectedSchema").getAsJsonObject("properties");
        for (String parameter : properties.keySet())
        {
            JsonObject damaged = frozen.getAsJsonObject("expectedSchema").deepCopy();
            JsonObject property = damaged.getAsJsonObject("properties").getAsJsonObject(parameter);
            property.addProperty("type", property.get("type").getAsString().equals("boolean") ? "string" : "boolean");
            rejected("visible." + parameter,
                () -> TheOrdinaryVisibleDescriptionsKeepTheirFullHelpTest.verifyVisible(frozen, parameter, damaged));
        }
    }

    /** A parameter without an explicit short description reads its original supplier on every call. */
    @Test
    public void defaultSchemaDescriptionKeepsTheLazySupplier() throws Exception
    {
        AtomicReference<String> text = new AtomicReference<>("first full meaning");
        ParamSpec parameter = ParamSpec.string("example", text::get);
        Method reader = requiredMethod("schemaDescription", 0);
        assertEquals("first full meaning", reader.invoke(parameter));
        text.set("second full meaning");
        assertEquals("second full meaning", reader.invoke(parameter));
        assertEquals("second full meaning", parameter.description());
    }

    /** An explicit short description never replaces or freezes the original full-help supplier. */
    @Test
    public void explicitSchemaDescriptionKeepsFullHelpIndependent() throws Exception
    {
        AtomicReference<String> text = new AtomicReference<>("first full meaning");
        ParamSpec original = ParamSpec.string("example", text::get);
        Method writer = requiredMethod("withSchemaDescription", 1);
        Method reader = requiredMethod("schemaDescription", 0);
        ParamSpec shortened = (ParamSpec)writer.invoke(original, "short visible meaning");
        assertEquals("short visible meaning", reader.invoke(shortened));
        assertEquals("first full meaning", shortened.description());
        text.set("second full meaning");
        assertEquals("second full meaning", shortened.description());
        assertEquals("second full meaning", reader.invoke(original));
        assertEquals("short visible meaning", reader.invoke(shortened));
    }

    /**
     * Resolve a proposed metadata method as an assertion rather than a compilation failure before implementation.
     *
     * @param name the exact public method name
     * @param arguments the number of declared arguments
     * @return the selected method
     */
    private static Method requiredMethod(String name, int arguments)
    {
        Method found = null;
        for (Method method : ParamSpec.class.getMethods())
        {
            if (method.getName().equals(name) && method.getParameterCount() == arguments)
            {
                found = method;
            }
        }
        assertNotNull("ParamSpec must declare " + name, found);
        return found;
    }

    /**
     * Remove exactly one operation attribution from a description group.
     *
     * @param description the complete pinned visible description
     * @param operation the attribution to remove
     * @return a deliberately damaged description
     */
    private static String withoutOperation(String description, String operation)
    {
        Matcher matcher = Pattern.compile("(?:^| )([a-z_]+(?:, [a-z_]+)*): ").matcher(description);
        while (matcher.find())
        {
            List<String> names = new ArrayList<>(List.of(matcher.group(1).split(", ")));
            if (names.remove(operation))
            {
                return description.substring(0, matcher.start(1)) + String.join(", ", names)
                    + description.substring(matcher.end(1));
            }
        }
        throw new AssertionError(operation + " has no attribution to remove");
    }

    /**
     * Require the intended oracle assertion rather than accepting an unrelated exception.
     *
     * @param prefix the exact parameter or help address identifying the failure
     * @param verification the deliberately failing check
     */
    private static void rejected(String prefix, Runnable verification)
    {
        boolean rejected = false;
        try
        {
            verification.run();
        }
        catch (AssertionError expected)
        {
            assertTrue("the intended oracle must fail: " + prefix, expected.getMessage().startsWith(prefix));
            rejected = true;
        }
        assertTrue(prefix + " must reject the mutation", rejected);
    }

    /**
     * Load literal facts independently frozen before production changes.
     *
     * @return the critical schema fragments and moved-help fragments
     * @throws Exception if the resource is absent or malformed
     */
    private static JsonObject facts() throws Exception
    {
        try (InputStream stream = TheOrdinaryDisclosureOracleRejectsLostFactsTest.class
            .getResourceAsStream("ordinary-disclosure-facts.json"))
        {
            assertNotNull("ordinary disclosure facts fixture", stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
