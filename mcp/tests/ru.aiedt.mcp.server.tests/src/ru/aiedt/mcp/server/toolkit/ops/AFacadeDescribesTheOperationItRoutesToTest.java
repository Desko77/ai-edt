/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import ru.aiedt.mcp.server.support.ToolGate;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Holds the insights facade's routing and its help to the same list of operations.
 * <p>
 * The facade's schema names its own parameters and not the parameters of the tools it routes to, so
 * the only way to ask what one operation takes is through its help. An operation the help cannot
 * describe is one whose parameters a caller who found it here has no way to learn - the standalone
 * tool has them, and nothing tells the caller that the standalone tool exists.
 * </p>
 * <p>
 * The routing lives in a switch and the describing in a map, because the operation-parameter census
 * reads the widest switch on the word as the facade's vocabulary and two would leave it to chance.
 * Two lists of the same thing is what this test is here for.
 * </p>
 */
public class AFacadeDescribesTheOperationItRoutesToTest
{
    private static final Pattern CATALOGUED = Pattern.compile("\\*\\*([a-z0-9_]+)\\*\\*");

    private static String help(String topic)
    {
        Map<String, String> params = new HashMap<>();
        params.put("operation", "help");
        if (topic != null)
        {
            params.put("topic", topic);
        }
        return new InsightsFacadeTool().execute(params);
    }

    private static String answerOrThrow(IMcpTool tool, Map<String, String> call)
    {
        try
        {
            return tool.execute(call);
        }
        catch (RuntimeException | LinkageError thrown)
        {
            return "threw " + thrown;
        }
    }

    private static String firstLineOf(String answer)
    {
        if (answer == null)
        {
            return "nothing";
        }
        int newline = answer.indexOf('\n');
        String line = newline < 0 ? answer : answer.substring(0, newline);
        return line.length() > 160 ? line.substring(0, 160) + "..." : line;
    }

    /** Every operation the facade advertises in its own catalogue, help itself excluded. */
    private static List<String> catalogued()
    {
        List<String> names = new ArrayList<>();
        Matcher found = CATALOGUED.matcher(help(null));
        while (found.find())
        {
            if (!"help".equals(found.group(1)))
            {
                names.add(found.group(1));
            }
        }
        return names;
    }

    @Test
    public void theCatalogueHoldsExactlyTheseOperations()
    {
        // Named rather than counted, and counted rather than bounded below. Everything else here
        // compares the catalogue with something else, and any such comparison is satisfied by both
        // sides losing an operation together: drop one from the help text and from the dispatch and
        // the sweep simply runs over nine. An operation this facade offers is a public name, so it
        // goes away when someone writes it down here, not quietly.
        List<String> expected = Arrays.asList("compare_configurations", "compare_three_way",
            "dependency_graph", "describe_db_tables", "detect_query_anti_patterns",
            "generate_health_snapshot", "impact_analysis", "object_summary", "project_metrics",
            "semantic_metadata_search");
        List<String> offered = new ArrayList<>(catalogued());
        Collections.sort(offered);

        assertEquals("the operations this facade advertises have changed", expected, offered);

        // The same list from the other side. Asking only about the names the catalogue holds would
        // never see one described here and offered nowhere - help would answer for an operation no
        // caller can reach through this facade.
        List<String> describable = new ArrayList<>(InsightsFacadeTool.describedOperations());
        Collections.sort(describable);
        assertEquals("the operations this facade can describe have changed", expected, describable);
    }

    @Test
    public void everyCataloguedOperationResolvesToTheToolItNames()
    {
        for (String operation : catalogued())
        {
            IMcpTool routed = InsightsFacadeTool.delegateFor(operation);
            assertNotNull("the catalogue offers '" + operation + "' and nothing describes it",
                routed);
            assertEquals("help for '" + operation + "' would describe another tool", operation,
                routed.getName());
        }
    }

    @Test
    public void everyCataloguedOperationIsAlsoDispatched()
    {
        // The test above reads the map that describes operations. This one reads the switch that
        // runs them, and the two are different lists: remove a case and help would go on describing
        // a route the call no longer takes, which is the drift the map was accused of inviting.
        //
        // What the operation answers here does not matter - none of them has a workspace to work
        // on, so all of them refuse. The refusal is the proof: a switch with no case for the name
        // falls to the default branch, and the default branch says so in those words.
        List<String> undispatched = new ArrayList<>();
        for (String operation : catalogued())
        {
            // A gated operation is answered before the switch is reached, so a missing case would
            // be invisible. Said out loud rather than skipped: the check would otherwise weaken
            // silently the day a preset switches one of these off by default.
            assertTrue("the preset disables " + operation + ", so this test cannot see whether "
                + "the dispatcher still has a case for it", ToolGate.gateIfPresetDisabled(
                    operation) == null);

            Map<String, String> call = new HashMap<>();
            call.put("operation", operation);
            String answer;
            try
            {
                answer = new InsightsFacadeTool().execute(call);
            }
            catch (RuntimeException | LinkageError thrown)
            {
                // Recorded rather than forgiven: a throw can come from before the switch as easily
                // as from inside the delegate, and only one of those proves the case is there.
                undispatched.add(operation + " threw " + thrown);
                continue;
            }
            if (answer != null
                && (answer.contains("Unhandled operation") || answer.contains("Unknown operation")))
            {
                undispatched.add(operation);
                continue;
            }
            // Presence is not agreement. A case that exists and routes to another tool describes
            // one thing and does another, which is worse than a missing case because it answers.
            // The facade returns what the delegate returned, so calling the tool the map names
            // with the same arguments must produce the same string - and a different tool refuses
            // in its own words.
            IMcpTool named = InsightsFacadeTool.delegateFor(operation);
            String direct = named == null ? null : answerOrThrow(named, call);
            if (answer != null && !answer.equals(direct))
            {
                undispatched.add(operation + " is dispatched to a tool other than the one help "
                    + "describes: through the facade " + firstLineOf(answer) + ", through "
                    + (named == null ? "nothing" : named.getName()) + " " + firstLineOf(direct));
            }
        }

        assertTrue("the catalogue and the help map offer these operations and the dispatcher does "
            + "not answer them: " + undispatched, undispatched.isEmpty());
    }

    @Test
    public void anOperationNameAsTopicAnswersWithThatOperationsParameters()
    {
        String answer = help("compare_three_way");

        assertTrue(answer, answer.contains("compare_three_way"));
        assertTrue("the heaviest parameters of this facade are this operation's, and they are "
            + "declared on the tool it routes to", answer.contains("ancestorPath"));
        assertTrue(answer, answer.contains("otherPath"));
    }

    @Test
    public void theWorkflowTopicStillAnswersAsItself()
    {
        // A topic that names no operation and was answering before. Falling through to the new
        // branch would replace the operation picker with an unknown-topic line.
        String answer = help("workflow");

        assertTrue(answer, answer.contains("operation picker"));
    }

    @Test
    public void aTopicThatIsNeitherIsStillRefused()
    {
        String answer = help("no such thing");

        assertTrue(answer, answer.contains("Unknown topic"));
    }
}
