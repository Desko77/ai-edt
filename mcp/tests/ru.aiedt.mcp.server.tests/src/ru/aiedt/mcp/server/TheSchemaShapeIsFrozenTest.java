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
     * Everything about a property that decides whether a call is legal, rendered in one line.
     *
     * @param property the property object from the schema.
     * @return its validation keys and their values, sorted, with the prose left out
     */
    private static String shapeOf(JsonObject property)
    {
        Map<String, String> keys = new TreeMap<>();
        for (Map.Entry<String, JsonElement> member : property.entrySet())
        {
            if (!DESCRIPTION.equals(member.getKey()))
            {
                keys.put(member.getKey(), member.getValue().toString());
            }
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
        JsonObject schema = parsed.getAsJsonObject();
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
