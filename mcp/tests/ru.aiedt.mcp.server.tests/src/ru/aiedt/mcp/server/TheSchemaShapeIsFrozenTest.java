/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * What a tool declares it accepts may be described differently, but not declared differently.
 * <p>
 * A client builds its calls from the schema, so the part of it that decides whether a call is legal
 * - which parameters exist, what each is, which are required - is a contract. The prose beside them
 * is not: it may be rewritten, shortened, or moved to help.
 * </p>
 * <p>
 * Nothing held that contract. The weight budget walks the properties a schema still has, so a
 * parameter that left takes its weight with it and reads as an improvement; and it never looks at
 * {@code required}, so an obligatory parameter can quietly become optional. Both are ways for a
 * shorter document to mean a broken one.
 * </p>
 * <p>
 * So the shape is frozen here: every property's validation keys, {@code description} excluded, plus
 * the tool's {@code required} list. Changing a schema on purpose means updating the snapshot, and
 * the failure prints the file to copy.
 * </p>
 */
public class TheSchemaShapeIsFrozenTest
{
    private static final String SNAPSHOT = "schema-shape.tsv"; //$NON-NLS-1$

    private static final String DESCRIPTION = "description"; //$NON-NLS-1$

    /**
     * Keywords whose value is what a call may send, rather than a schema describing it. Their
     * contents are recorded as they stand: inside them {@code description} is part of the value.
     */
    private static final java.util.Set<String> LITERAL_VALUES =
        Collections.unmodifiableSet(new java.util.HashSet<>(
            java.util.Arrays.asList("const", "enum", "default", "examples"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private McpToolCatalog registry;

    @Before
    public void registerEveryTool()
    {
        registry = McpToolCatalog.getInstance();
        registry.clear();
        new McpHttpEndpoint().registerTools();
    }

    @After
    public void clearRegistry()
    {
        registry.clear();
    }

    /**
     * The same element with every prose description taken out, however deep it sits.
     * <p>
     * A property can carry a schema of its own - {@code items}, a branch of {@code oneOf} - and
     * that nested schema can describe itself. Left in, the description of a nested schema would
     * read as part of the contract, and rewriting it would fail a check whose whole point is that
     * prose may be rewritten.
     * </p>
     * <p>
     * Inside {@code properties} the keys are parameter NAMES, not schema keywords, so a parameter
     * called {@code description} - two tools declare one - is kept while the keyword of the same
     * spelling is dropped. Stripping by spelling alone would erase a real parameter from the
     * contract and let it disappear unnoticed, which is the very thing this guards.
     * </p>
     *
     * @param element any part of a schema.
     * @return a copy without prose
     */
    private static JsonElement withoutProse(JsonElement element)
    {
        if (element.isJsonArray())
        {
            JsonArray copied = new JsonArray();
            for (JsonElement item : element.getAsJsonArray())
            {
                copied.add(withoutProse(item));
            }
            return copied;
        }
        if (!element.isJsonObject())
        {
            return element;
        }
        // Sorted on the way in, so that members written in another order still render the same
        // string. Where two schemas accept the same calls, this check has nothing to say.
        JsonObject source = element.getAsJsonObject();
        JsonObject copied = new JsonObject();
        for (String key : new TreeSet<>(source.keySet()))
        {
            if (DESCRIPTION.equals(key))
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
                    named.add(name, withoutProse(declared.get(name)));
                }
                copied.add(key, named);
            }
            else if (LITERAL_VALUES.contains(key))
            {
                // What a call may send, not a schema describing it. An object here is data, so a
                // member of it that happens to be spelled description is part of the value: const
                // {"description":"A"} and const {"description":"B"} accept different things, and
                // reducing both to {} would record them as the same contract.
                copied.add(key, value);
            }
            else
            {
                copied.add(key, withoutProse(value));
            }
        }
        return copied;
    }

    /**
     * Everything about a property that decides whether a call is legal, rendered in one line.
     *
     * @param property the property object from the schema.
     * @return its validation keys and their values, sorted, with the prose left out
     */
    private static String shapeOf(JsonObject property)
    {
        // The whole property goes through at once. Walking its members here and cleaning each value
        // separately hands the property-name map of `properties` to the cleaner as if it were a
        // schema, and a parameter called description is then dropped as if it were the keyword.
        // The whole property goes through at once. Walking its members here and cleaning each value
        // separately hands the property-name map of `properties` to the cleaner as if it were a
        // schema, and a parameter called description is then dropped as if it were the keyword -
        // without moving the snapshot, so nothing notices.
        Map<String, String> keys = new TreeMap<>();
        for (Map.Entry<String, JsonElement> member : withoutProse(property).getAsJsonObject()
            .entrySet())
        {
            keys.put(member.getKey(), member.getValue().toString());
        }
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, String> key : keys.entrySet())
        {
            if (line.length() > 0)
            {
                line.append(';');
            }
            line.append(key.getKey()).append('=').append(key.getValue());
        }
        return line.toString();
    }

    /**
     * The declared shape of one tool: every property, and what the tool insists on.
     *
     * @param tool the tool.
     * @return one line, or <code>null</code> when the tool declares no schema object
     */
    private static String shapeOf(IMcpTool tool)
    {
        JsonElement parsed = JsonParser.parseString(tool.getInputSchema());
        if (!parsed.isJsonObject())
        {
            return null;
        }
        JsonObject schema = withoutProse(parsed).getAsJsonObject();
        StringBuilder line = new StringBuilder();
        JsonArray required = schema.getAsJsonArray("required"); //$NON-NLS-1$
        TreeSet<String> insisted = new TreeSet<>();
        if (required != null)
        {
            for (JsonElement name : required)
            {
                insisted.add(name.getAsString());
            }
        }
        // Without a space between the names: the difference below is read by splitting the line on
        // spaces, and "required=[a, b]" would arrive as two tokens and name a change that is not
        // there.
        line.append("required=[").append(String.join(",", insisted)).append(']'); //$NON-NLS-1$ //$NON-NLS-2$
        // Whatever else the root says. A constraint written there - additionalProperties,
        // minProperties - decides which calls are legal just as a property does, and a shape made
        // of required and properties alone would let one arrive or leave without a word.
        for (String key : new TreeSet<>(schema.keySet()))
        {
            if (!"required".equals(key) && !"properties".equals(key)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                line.append(' ').append(key).append('=').append(schema.get(key).toString());
            }
        }
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        if (properties != null)
        {
            TreeMap<String, JsonObject> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> property : properties.entrySet())
            {
                if (property.getValue().isJsonObject())
                {
                    sorted.put(property.getKey(), property.getValue().getAsJsonObject());
                }
            }
            for (Map.Entry<String, JsonObject> property : sorted.entrySet())
            {
                line.append(' ').append(property.getKey()).append('{')
                    .append(shapeOf(property.getValue())).append('}');
            }
        }
        return line.toString();
    }

    private Map<String, String> shapesNow()
    {
        Map<String, String> shapes = new TreeMap<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String shape = shapeOf(tool);
            if (shape != null)
            {
                shapes.put(tool.getName(), shape);
            }
        }
        return shapes;
    }

    private static Map<String, String> recorded() throws IOException
    {
        Map<String, String> shapes = new LinkedHashMap<>();
        try (InputStream stream = TheSchemaShapeIsFrozenTest.class.getResourceAsStream(SNAPSHOT))
        {
            if (stream == null)
            {
                return shapes;
            }
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read = stream.read(buffer);
            while (read >= 0)
            {
                collected.write(buffer, 0, read);
                read = stream.read(buffer);
            }
            for (String line : new String(collected.toByteArray(), StandardCharsets.UTF_8)
                .split("\r?\n")) //$NON-NLS-1$
            {
                int tab = line.indexOf('\t');
                if (tab > 0 && !line.startsWith("#")) //$NON-NLS-1$
                {
                    shapes.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        return shapes;
    }

    /** Written beside the build so a deliberate change can be copied over the snapshot. */
    private static Path writeActual(Map<String, String> shapes) throws IOException
    {
        StringBuilder text = new StringBuilder(
            "# the declared shape of every tool: validation keys and required, prose excluded\n"); //$NON-NLS-1$
        for (Map.Entry<String, String> shape : shapes.entrySet())
        {
            text.append(shape.getKey()).append('\t').append(shape.getValue()).append('\n');
        }
        Path out = Paths.get("target", "schema-shape.actual.tsv"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createDirectories(out.getParent());
        Files.write(out, text.toString().getBytes(StandardCharsets.UTF_8));
        return out.toAbsolutePath();
    }

    @Test
    public void proseInsideANestedSchemaIsNotPartOfTheContract()
    {
        // A parameter that is a list of things describes the things too. Rewriting that sentence
        // has to be as free as rewriting the parameter's own.
        JsonObject plain = JsonParser.parseString(
            "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}").getAsJsonObject(); //$NON-NLS-1$
        JsonObject described = JsonParser.parseString(
            "{\"type\":\"array\",\"description\":\"what it is\"," //$NON-NLS-1$
                + "\"items\":{\"type\":\"string\",\"description\":\"one of them\"}}") //$NON-NLS-1$
            .getAsJsonObject();

        assertTrue("the same declaration described differently is the same declaration: " //$NON-NLS-1$
            + shapeOf(plain) + " vs " + shapeOf(described), //$NON-NLS-1$
            shapeOf(plain).equals(shapeOf(described)));
    }

    @Test
    public void aParameterNamedDescriptionStaysInTheContract()
    {
        // code_template and edit_metadata both declare one. Stripping prose by spelling alone
        // would erase it from the snapshot, and it could then leave a schema unnoticed - which is
        // the failure this whole test exists to prevent.
        JsonObject nested = JsonParser.parseString(
            "{\"type\":\"object\",\"description\":\"the prose\"," //$NON-NLS-1$
                + "\"properties\":{\"description\":{\"type\":\"string\"," //$NON-NLS-1$
                + "\"description\":\"its own prose\"}}}").getAsJsonObject(); //$NON-NLS-1$

        // Asked of shapeOf, which is what the snapshot is built from. Asking withoutProse directly
        // exercises the path that already worked and says nothing about the one that did not: the
        // first cut walked the property's members itself and handed the property-name map over as
        // if it were a schema, so the name went and no snapshot moved.
        String shape = shapeOf(nested);

        assertTrue("the parameter name has to survive: " + shape, //$NON-NLS-1$
            shape.contains("\"description\":{\"type\":\"string\"}")); //$NON-NLS-1$
        assertTrue("the keyword beside it has to go: " + shape, //$NON-NLS-1$
            !shape.contains("the prose") && !shape.contains("its own prose")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void droppingANestedParameterMovesTheShape()
    {
        // The point of keeping the name: if it can leave without moving the shape, the contract is
        // not held for anything declared inside an object parameter.
        JsonObject withIt = JsonParser.parseString(
            "{\"type\":\"object\",\"properties\":{\"description\":{\"type\":\"string\"}," //$NON-NLS-1$
                + "\"other\":{\"type\":\"string\"}}}").getAsJsonObject(); //$NON-NLS-1$
        JsonObject withoutIt = JsonParser.parseString(
            "{\"type\":\"object\",\"properties\":{\"other\":{\"type\":\"string\"}}}") //$NON-NLS-1$
            .getAsJsonObject();

        assertTrue("a parameter that left has to change the shape, whatever it is called", //$NON-NLS-1$
            !shapeOf(withIt).equals(shapeOf(withoutIt)));
    }

    @Test
    public void theRegistryIsNotEmpty()
    {
        // Guards the sweep: an empty registry would make the comparison below pass over nothing.
        assertTrue("registerTools() produced no tools, so no schema was compared", //$NON-NLS-1$
            registry.getAllTools().size() > 50);
    }

    @Test
    public void everyDeclaredShapeIsTheOneRecorded() throws IOException
    {
        Map<String, String> now = shapesNow();
        Map<String, String> before = recorded();
        Path actual = writeActual(now);
        if (before.isEmpty())
        {
            fail("no recorded shape to compare against. The current one is written to " + actual //$NON-NLS-1$
                + " - read it, then copy it over src/ru/aiedt/mcp/server/" + SNAPSHOT); //$NON-NLS-1$
        }

        List<String> differences = new ArrayList<>();
        for (Map.Entry<String, String> tool : now.entrySet())
        {
            String was = before.get(tool.getKey());
            if (was == null)
            {
                differences.add(tool.getKey() + " is not in the recorded shape - record it"); //$NON-NLS-1$
            }
            else if (!was.equals(tool.getValue()))
            {
                differences.add(tool.getKey() + " declares a different shape now" //$NON-NLS-1$
                    + firstDifference(was, tool.getValue()));
            }
        }
        for (String name : before.keySet())
        {
            if (!now.containsKey(name))
            {
                differences.add(name + " had a recorded shape and no longer declares one"); //$NON-NLS-1$
            }
        }
        assertTrue("the declared shape is a contract - a parameter that left, a type that changed " //$NON-NLS-1$
            + "or an obligation that was dropped breaks callers that the prose never would. " //$NON-NLS-1$
            + "Current shape written to " + actual + ".\n" + String.join("\n", differences), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            differences.isEmpty());
    }

    /**
     * Where two shapes part company, so the message names the property rather than printing both.
     *
     * @param was the recorded shape.
     * @param now the shape declared now.
     * @return a short description of the first difference
     */
    private static String firstDifference(String was, String now)
    {
        List<String> before = Collections.unmodifiableList(java.util.Arrays.asList(was.split(" "))); //$NON-NLS-1$
        List<String> after = Collections.unmodifiableList(java.util.Arrays.asList(now.split(" "))); //$NON-NLS-1$
        TreeSet<String> gone = new TreeSet<>(before);
        gone.removeAll(after);
        TreeSet<String> added = new TreeSet<>(after);
        added.removeAll(before);
        StringBuilder said = new StringBuilder();
        if (!gone.isEmpty())
        {
            said.append("\n      gone: ").append(String.join(", ", gone)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!added.isEmpty())
        {
            said.append("\n      now:  ").append(String.join(", ", added)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return said.toString();
    }
}
