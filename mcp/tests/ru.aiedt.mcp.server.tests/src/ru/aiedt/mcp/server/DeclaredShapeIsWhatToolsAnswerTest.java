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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import ru.aiedt.mcp.server.toolkit.IMcpTool;
import ru.aiedt.mcp.server.toolkit.McpToolCatalog;

/**
 * Holds every tool to the response shape it declares.
 * <p>
 * A tool that declares {@link IMcpTool.ResponseType#JSON} and answers markdown does not degrade - it
 * fails outright. The router parses the payload by the declared type, and the caller gets
 * {@code MalformedJsonException} where an answer should have been. Two tools were doing exactly
 * that when this check was written, and both on the path an agent reaches first: the catalog of
 * operations, and the bare call with no arguments at all.
 * </p>
 * <p>
 * <b>The check is a run, not a read of the source.</b> That distinction is the whole reason it
 * exists. A census over the sources looked for {@code ResponseType.JSON} beside a markdown-building
 * help method; it flagged twelve candidates of which ten were innocent, and it missed
 * {@code yaxunit_tests} entirely, because that one answers markdown by handing back what a
 * delegate returned rather than by building it. Only calling the tool sees that.
 * </p>
 * <p>
 * The two shapes called here are the two an agent tries before it knows anything: the bare call and
 * the request for help. Neither needs a workspace, so both are answerable in this runtime - what
 * comes back is a refusal, and a refusal has to parse just as much as an answer does.
 * </p>
 * <p>
 * Only the JSON direction is held. A markdown tool that answers a JSON object renders as text and
 * costs the reader nothing; the reverse costs them the answer.
 * </p>
 */
public class DeclaredShapeIsWhatToolsAnswerTest
{
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
    public void aJsonToolAnswersJsonOnTheTwoShapesAnAgentTriesFirst()
    {
        Map<String, String> bare = new LinkedHashMap<>();
        Map<String, String> askingForHelp = new LinkedHashMap<>();
        askingForHelp.put("operation", "help"); //$NON-NLS-1$ //$NON-NLS-2$

        List<String> offenders = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            if (tool.getResponseType() != IMcpTool.ResponseType.JSON)
            {
                continue;
            }
            complain(tool, bare, "called with no arguments", offenders); //$NON-NLS-1$
            complain(tool, askingForHelp, "asked for help", offenders); //$NON-NLS-1$
        }

        if (!offenders.isEmpty())
        {
            // Every offender at once: the drift lands wherever a response is edited next, and a
            // check that stops at the first one turns a single sweep into as many runs as there
            // are defects.
            throw new AssertionError("These tools declare JSON and answer something else, which " //$NON-NLS-1$
                + "reaches the caller as a parse error rather than as an answer:\n  " //$NON-NLS-1$
                + String.join("\n  ", offenders)); //$NON-NLS-1$
        }
    }

    /**
     * Calls one tool one way and records it when the answer will not parse.
     * <p>
     * A thrown exception is not an offence here: the router turns one into an error response, which
     * is a JSON object by construction. What this looks for is a tool that returns successfully and
     * returns something the declared type cannot carry.
     * </p>
     *
     * @param tool the tool to call.
     * @param arguments the call shape.
     * @param shape how to describe that shape in a failure.
     * @param offenders where to record a failure.
     */
    private static void complain(IMcpTool tool, Map<String, String> arguments, String shape,
        List<String> offenders)
    {
        String answer;
        try
        {
            answer = tool.execute(arguments);
        }
        catch (RuntimeException | LinkageError thrown)
        {
            return;
        }
        if (answer == null || answer.isEmpty())
        {
            return;
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(answer);
            if (parsed != null && parsed.isJsonObject())
            {
                return;
            }
        }
        catch (RuntimeException willNotParse)
        {
            // Falls through to the complaint below, which quotes the payload.
        }
        offenders.add(tool.getName() + " (" + shape + "): " + firstLineOf(answer)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The opening of a payload, for a failure message that says what came back instead.
     *
     * @param answer what the tool returned.
     * @return its first line, shortened
     */
    private static String firstLineOf(String answer)
    {
        String line = answer.trim();
        int breakAt = line.indexOf('\n');
        if (breakAt > 0)
        {
            line = line.substring(0, breakAt);
        }
        return line.length() > 90 ? line.substring(0, 90) + "..." : line; //$NON-NLS-1$
    }
}
