/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * Holds every tool the server registers to the contract an MCP client relies on.
 * <p>
 * The declaration is the whole of what an agent knows about a tool: it picks the tool by name, reads
 * the description to decide whether it fits, and fills the arguments from the schema. A schema that
 * says a parameter is required without declaring it, or declares one with no type, produces a call
 * that fails for reasons the agent cannot see - and no single-tool test catches the drift, because
 * the drift lands wherever a schema is edited next. So the sweep walks the whole registry rather
 * than a chosen sample, and each failure names every offender at once.
 * </p>
 */
public class McpToolSchemaContractTest
{
    /** Wire names are snake_case throughout; a stray camelCase name is a typo an agent cannot call. */
    private static final Pattern WIRE_NAME = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*"); //$NON-NLS-1$

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

    @Test
    public void theRegistryIsNotEmpty()
    {
        // Guards the sweep itself: an empty registry would make every test below pass vacuously.
        assertTrue("registerTools() produced no tools, so nothing below was actually checked", //$NON-NLS-1$
            registry.getAllTools().size() > 50);
    }

    @Test
    public void everyNameIsSnakeCase()
    {
        List<String> offenders = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            if (tool.getName() == null || !WIRE_NAME.matcher(tool.getName()).matches())
            {
                offenders.add(String.valueOf(tool.getName()));
            }
        }
        assertTrue("wire names have to be snake_case: " + offenders, offenders.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void everyToolDescribesItself()
    {
        List<String> offenders = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String description = tool.getDescription();
            if (description == null || description.isBlank())
            {
                offenders.add(tool.getName());
            }
        }
        assertTrue("an agent picks a tool by its description; these have none: " + offenders, //$NON-NLS-1$
            offenders.isEmpty());
    }

    @Test
    public void everyToolAnswersWithAResponseType()
    {
        List<String> offenders = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            if (tool.getResponseType() == null)
            {
                offenders.add(tool.getName());
            }
        }
        assertTrue("the response type decides how the result is wrapped: " + offenders, //$NON-NLS-1$
            offenders.isEmpty());
    }

    @Test
    public void everySchemaIsAnObjectSchema()
    {
        List<String> offenders = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            JsonObject schema = parse(tool);
            if (schema == null)
            {
                offenders.add(tool.getName() + " (not a JSON object)"); //$NON-NLS-1$
                continue;
            }
            JsonElement type = schema.get("type"); //$NON-NLS-1$
            if (type == null || !"object".equals(type.getAsString())) //$NON-NLS-1$
            {
                offenders.add(tool.getName() + " (type is " + type + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (!schema.has("properties") || !schema.get("properties").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
            {
                offenders.add(tool.getName() + " (no properties object)"); //$NON-NLS-1$
            }
        }
        assertTrue("MCP requires an object schema with a properties map: " + offenders, //$NON-NLS-1$
            offenders.isEmpty());
    }

    @Test
    public void everyRequiredParameterIsDeclared()
    {
        // The drift that costs an agent a call: "required": ["projectName"] left behind after the
        // property was renamed. The client sends what the schema declares, the tool waits for what
        // it does not.
        List<String> offenders = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            JsonObject schema = parse(tool);
            if (schema == null || !schema.has("required") || !schema.get("required").isJsonArray()) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
            for (JsonElement required : schema.getAsJsonArray("required")) //$NON-NLS-1$
            {
                String name = required.getAsString();
                if (properties == null || !properties.has(name))
                {
                    offenders.add(tool.getName() + "." + name); //$NON-NLS-1$
                }
            }
        }
        assertTrue("declared required but never defined as a property: " + offenders, //$NON-NLS-1$
            offenders.isEmpty());
    }

    @Test
    public void everyParameterCarriesATypeAndADescription()
    {
        List<String> offenders = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            JsonObject schema = parse(tool);
            if (schema == null || !schema.has("properties") || !schema.get("properties").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
            {
                continue;
            }
            JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
            for (String name : properties.keySet())
            {
                JsonElement property = properties.get(name);
                if (!property.isJsonObject())
                {
                    offenders.add(tool.getName() + "." + name + " (not an object)"); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                JsonObject declaration = property.getAsJsonObject();
                // anyOf / oneOf carry the type in their branches instead of at this level.
                boolean typed = declaration.has("type") || declaration.has("anyOf") //$NON-NLS-1$ //$NON-NLS-2$
                    || declaration.has("oneOf") || declaration.has("$ref"); //$NON-NLS-1$ //$NON-NLS-2$
                if (!typed)
                {
                    offenders.add(tool.getName() + "." + name + " (untyped)"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                JsonElement description = declaration.get("description"); //$NON-NLS-1$
                if (description == null || description.getAsString().isBlank())
                {
                    offenders.add(tool.getName() + "." + name + " (undocumented)"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        assertTrue("every parameter needs a type and a description an agent can read: " + offenders, //$NON-NLS-1$
            offenders.isEmpty());
    }

    private static JsonObject parse(IMcpTool tool)
    {
        String schema = tool.getInputSchema();
        if (schema == null || schema.isBlank())
        {
            return null;
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(schema);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
        catch (JsonSyntaxException malformed)
        {
            return null;
        }
    }
}
