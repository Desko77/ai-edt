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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;
import ru.aiedt.mcp.server.toolkit.ops.ThreeWayComparisonTool;

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
    public void theCallThatTheSchemaAdmitsActuallyAnswersWithTheParameters()
    {
        // The test above only says the call is admissible. Admissible and answered are different
        // claims: move the help branch below the missing-argument guard and the schema check stays
        // green while every help call is refused for arguments the caller was never asking about.
        //
        // What counts as an answer is read in two tiers, because `help` does not mean one thing
        // across the catalogue. Where it renders the tool's parameters the heading says so, and
        // that is proof. Where the tool has a help vocabulary of its own - yaxunit_tests takes
        // help=<topic> and answers an unknown one by listing the topics it has - there is no
        // heading to look for, and what remains provable is that passing help CHANGED the answer.
        // Identical answers are the defect itself: the help branch never ran and the bare refusal
        // came back both times.
        List<String> refused = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            JsonObject schema = schemaOf(tool);
            if (schema == null || !declaresHelpArgument(schema))
            {
                continue;
            }
            Map<String, String> asking = new LinkedHashMap<>();
            asking.put(HELP, "yes"); //$NON-NLS-1$
            String answer = answerOf(tool, asking);
            if (answer == null)
            {
                refused.add(tool.getName() + " threw or answered nothing"); //$NON-NLS-1$
                continue;
            }
            // The heading ParameterHelp writes. Checked as a substring because a tool answering
            // JSON carries the same text inside a member of its document.
            if (answer.contains(tool.getName() + " - parameters")) //$NON-NLS-1$
            {
                continue;
            }
            String bare = answerOf(tool, new LinkedHashMap<>());
            if (answer.equals(bare))
            {
                refused.add(tool.getName() + " answered the same with help as without: " //$NON-NLS-1$
                    + firstLineOf(answer));
            }
        }

        if (!refused.isEmpty())
        {
            throw new AssertionError("These tools declare a help argument and passing it reaches " //$NON-NLS-1$
                + "no help of any kind:\n  " + String.join("\n  ", refused)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void anEmptyHelpValueIsNotARequest()
    {
        // Decided rather than defaulted: a client that fills every declared string with an empty
        // value would otherwise turn every call into a help answer and never compare anything. The
        // schema says an empty or whitespace-only value is not a request, and this holds it to it.
        //
        // Every spelling, because two readings were tried before this one and each drew the line
        // somewhere a caller would not: trim stops at U+0020, so an em space asked for help while
        // an ordinary space did not; isBlank goes by Character.isWhitespace, which excludes the
        // non-breaking spaces, so U+00A0 asked for help while U+0020 did not. What is asked now is
        // whether anything shows, and these are the spellings that do not.
        for (String blankValue : new String[] {"", " ", "   ", "\t", "\n", " ", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            " ", " ", " ", "​", "﻿", "  \t", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            // Above the basic plane, so it arrives as a surrogate pair. Read a char at a time,
            // neither half is FORMAT and the value counted as something that shows.
            new String(Character.toChars(0xE0001))})
        {
            Map<String, String> blank = new LinkedHashMap<>();
            blank.put(HELP, blankValue);
            String answer = new ThreeWayComparisonTool().execute(blank);

            if (answer != null && answer.contains("compare_three_way - parameters")) //$NON-NLS-1$
            {
                throw new AssertionError("a blank help value (" + escaped(blankValue) //$NON-NLS-1$
                    + ") was read as a request for help, which makes a client that fills every " //$NON-NLS-1$
                    + "declared string unable to compare anything"); //$NON-NLS-1$
            }
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

    /**
     * What one tool answers to one call, or <code>null</code> when it will not answer at all.
     *
     * @param tool the tool to call.
     * @param arguments the call.
     * @return the answer, or <code>null</code>
     */
    private static String answerOf(IMcpTool tool, Map<String, String> arguments)
    {
        try
        {
            String answer = tool.execute(arguments);
            return answer == null || answer.isEmpty() ? null : answer;
        }
        catch (RuntimeException | LinkageError thrown)
        {
            return null;
        }
    }

    /**
     * One value written so a failure names which spelling of blank it was.
     *
     * @param value the value.
     * @return the value with its characters spelled out
     */
    private static String escaped(String value)
    {
        StringBuilder written = new StringBuilder();
        for (int i = 0; i < value.length(); i++)
        {
            written.append(String.format("U+%04X ", (int)value.charAt(i))); //$NON-NLS-1$
        }
        return written.length() == 0 ? "empty" : written.toString().trim(); //$NON-NLS-1$
    }

    private static String firstLineOf(String answer)
    {
        if (answer == null)
        {
            return "nothing at all"; //$NON-NLS-1$
        }
        int newline = answer.indexOf('\n');
        String line = newline < 0 ? answer : answer.substring(0, newline);
        return line.length() > 200 ? line.substring(0, 200) + "..." : line; //$NON-NLS-1$
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
