/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import java.util.ArrayList;
import java.util.List;

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
 * A tool that offers help by argument must let a caller ask for it.
 * <p>
 * The schema is not documentation the client may skip - a validating client refuses a call that
 * does not satisfy it, before the server ever sees the request. So a tool that declares a
 * {@code help} argument and also requires arguments a help call has nothing to put in has declared
 * a route nobody can take: the answer exists, the call to reach it does not.
 * </p>
 * <p>
 * Found on {@code compare_three_way} the day the argument was added - the schema still required
 * projectName and otherPath, which name a comparison that a caller asking what the parameters mean
 * has not chosen yet.
 * </p>
 */
public class AHelpCallMustBeMakeableTest
{
    private static final String HELP = "help"; //$NON-NLS-1$

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
    public void nothingIsRequiredOfACallThatOnlyAsksWhatTheParametersAre()
    {
        List<String> unreachable = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            JsonObject schema = schemaOf(tool);
            if (schema == null || !declaresHelpArgument(schema))
            {
                continue;
            }
            for (String insisted : insistedOn(schema))
            {
                if (!HELP.equals(insisted))
                {
                    unreachable.add(tool.getName() + " requires " + insisted); //$NON-NLS-1$
                }
            }
        }

        if (!unreachable.isEmpty())
        {
            throw new AssertionError("These tools answer help by argument and require arguments a " //$NON-NLS-1$
                + "help call cannot supply, so a validating client refuses the call before the " //$NON-NLS-1$
                + "tool is reached:\n  " + String.join("\n  ", unreachable)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void theSweepLooksAtSomething()
    {
        // Without this, a change that stopped the schema parsing - or renamed the argument - would
        // leave the test above sweeping an empty set and reporting success.
        int offering = 0;
        for (IMcpTool tool : registry.getAllTools())
        {
            JsonObject schema = schemaOf(tool);
            if (schema != null && declaresHelpArgument(schema))
            {
                offering++;
            }
        }
        if (offering == 0)
        {
            throw new AssertionError("no tool declares a help argument, so the check above proves " //$NON-NLS-1$
                + "nothing"); //$NON-NLS-1$
        }
    }

    private static JsonObject schemaOf(IMcpTool tool)
    {
        try
        {
            JsonElement parsed = JsonParser.parseString(tool.getInputSchema());
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
        catch (RuntimeException willNotParse)
        {
            return null;
        }
    }

    private static boolean declaresHelpArgument(JsonObject schema)
    {
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        return properties != null && properties.has(HELP);
    }

    private static List<String> insistedOn(JsonObject schema)
    {
        List<String> names = new ArrayList<>();
        JsonArray required = schema.getAsJsonArray("required"); //$NON-NLS-1$
        if (required != null)
        {
            for (JsonElement name : required)
            {
                names.add(name.getAsString());
            }
        }
        return names;
    }
}
