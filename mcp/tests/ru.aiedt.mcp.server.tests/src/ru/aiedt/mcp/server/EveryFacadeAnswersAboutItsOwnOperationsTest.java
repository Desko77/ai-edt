/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * A facade's help answers about every operation the facade itself offers.
 * <p>
 * The facade's schema declares the union of its operations' parameters and sorts them out in prose.
 * Asking about one operation is the question a caller has, and `operation=help topic=&lt;operation&gt;`
 * is where it is asked. An operation the catalogue offers and the help calls unknown is worse than
 * one with no help at all: the caller is told the thing they just read about does not exist.
 * </p>
 * <p>
 * What the answer says is not pinned here - some operations are described from the tool they route
 * to, and some are handled inside the facade and say so. What is pinned is that neither reads as
 * "no such topic".
 * </p>
 */
public class EveryFacadeAnswersAboutItsOwnOperationsTest
{
    /** An operation in a facade's own catalogue, written `- **name** - ...`. */
    private static final Pattern CATALOGUED = Pattern.compile("\\*\\*([a-z0-9_]+)\\*\\*");

    /** How many tools answer a catalogue of their own operations. Counted, not estimated. */
    private static final int EXPECTED_FACADES = 10;

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

    private static String help(IMcpTool facade, String topic)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", "help");
        if (topic != null)
        {
            params.put("topic", topic);
        }
        try
        {
            return facade.execute(params);
        }
        catch (RuntimeException | LinkageError thrown)
        {
            return null;
        }
    }

    /** Facades, recognised by answering their own catalogue to `operation=help`. */
    private static List<IMcpTool> facades(McpToolCatalog registry)
    {
        List<IMcpTool> found = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String catalogue = help(tool, null);
            if (catalogue != null && catalogue.contains(tool.getName() + " - operations"))
            {
                found.add(tool);
            }
        }
        return found;
    }

    @Test
    public void theSweepFindsEveryFacadeItFoundBefore()
    {
        // Counted against what is there rather than against a floor well under it. A facade drops
        // out of this sweep when its catalogue heading changes or its help throws, and a floor of
        // eight let two leave without a word - which is the same silent shrink the sweep exists to
        // refuse one operation at a time.
        int found = facades(registry).size();
        if (found != EXPECTED_FACADES)
        {
            throw new AssertionError(found + " facades answered their own catalogue, and "
                + EXPECTED_FACADES + " did when this was written. One that stops answering is "
                + "not swept, and a sweep over fewer reads exactly like a sweep that passed.");
        }
    }

    @Test
    public void noOperationAFacadeOffersIsCalledAnUnknownTopic()
    {
        List<String> denied = new ArrayList<>();
        for (IMcpTool facade : facades(registry))
        {
            Matcher offered = CATALOGUED.matcher(help(facade, null));
            while (offered.find())
            {
                String operation = offered.group(1);
                if ("help".equals(operation))
                {
                    continue;
                }
                String answer = help(facade, operation);
                if (answer == null || answer.contains("Unknown topic"))
                {
                    denied.add(facade.getName() + " operation=help topic=" + operation);
                }
            }
        }

        if (!denied.isEmpty())
        {
            throw new AssertionError("These facades offer an operation in their own catalogue and "
                + "answer that the operation is an unknown topic:\n  "
                + String.join("\n  ", denied));
        }
    }
}
