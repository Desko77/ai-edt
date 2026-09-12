/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Three answers, and a caller has to be able to tell them apart.
 * <p>
 * A topic naming an operation the facade routes somewhere gets that tool's parameters. A topic
 * naming an operation the facade handles itself gets a line saying so. A topic naming nothing gets
 * a refusal. Collapse the middle one into either neighbour and the caller is misled: told the
 * operation takes nothing, or told the operation they just read about does not exist.
 * </p>
 */
public class AFacadeTellsAnAbsentTopicFromAnUndescribedOneTest
{
    private static Map<String, Supplier<IMcpTool>> described()
    {
        Map<String, Supplier<IMcpTool>> map = new LinkedHashMap<>();
        map.put("routed", Described::new);
        return map;
    }

    private static Set<String> dispatched()
    {
        Set<String> names = new LinkedHashSet<>();
        names.add("routed");
        names.add("handled_here");
        return names;
    }

    @Test
    public void anOperationWithAToolBehindItAnswersThatToolsParameters()
    {
        String answer =
            FacadeParameterHelp.answer("routed", described(), dispatched(), "workflow");

        assertTrue(answer, answer.contains("routed - parameters"));
        assertTrue("the parameters come from the tool the operation routes to",
            answer.contains("projectName"));
    }

    @Test
    public void anOperationTheFacadeHandlesItselfSaysItIsNotRecorded()
    {
        String answer =
            FacadeParameterHelp.answer("handled_here", described(), dispatched(), "workflow");

        assertTrue(answer, answer.contains("not"));
        assertTrue("an operation the catalogue offers must not be called an unknown topic",
            !answer.contains("Unknown topic"));
    }

    @Test
    public void aTopicNamingNothingIsRefusedAndSaysWhatCanBeAsked()
    {
        String answer =
            FacadeParameterHelp.answer("no such thing", described(), dispatched(), "workflow");

        assertTrue(answer, answer.contains("Unknown topic"));
        assertTrue(answer, answer.contains("workflow"));
    }

    @Test
    public void theThreeAnswersDifferFromEachOther()
    {
        // The point of the class in one assertion: two of these were one answer before, and a
        // caller could not act differently on them because they read the same.
        String routed = FacadeParameterHelp.answer("routed", described(), dispatched(), "workflow");
        String here =
            FacadeParameterHelp.answer("handled_here", described(), dispatched(), "workflow");
        String nothing =
            FacadeParameterHelp.answer("nothing", described(), dispatched(), "workflow");

        assertTrue(!routed.equals(here) && !here.equals(nothing) && !routed.equals(nothing));
    }

    @Test
    public void aNullTopicIsRefusedRatherThanThrown()
    {
        String answer = FacadeParameterHelp.answer(null, described(), dispatched(), "workflow");

        assertTrue(answer, answer.contains("Unknown topic"));
    }

    @Test
    public void aFacadeThatKnowsNoOperationsStillAnswers()
    {
        // An empty set is what a facade passes before it has a catalogue of its own. The answer
        // must be the refusal, not an exception on the way to it.
        String answer = FacadeParameterHelp.answer("routed",
            Collections.<String, Supplier<IMcpTool>> emptyMap(), Collections.<String> emptySet(),
            "workflow");

        assertTrue(answer, answer.contains("Unknown topic"));
    }

    /** Stands in for a routed tool: it declares one parameter and answers nothing. */
    public static final class Described implements IMcpTool
    {
        @Override
        public String getName()
        {
            return "routed";
        }

        @Override
        public String getDescription()
        {
            return "stands in for a tool an operation routes to";
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\",\"properties\":{\"projectName\":{\"type\":\"string\","
                + "\"description\":\"which project\"}},\"required\":[\"projectName\"]}";
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "{}";
        }
    }
}
