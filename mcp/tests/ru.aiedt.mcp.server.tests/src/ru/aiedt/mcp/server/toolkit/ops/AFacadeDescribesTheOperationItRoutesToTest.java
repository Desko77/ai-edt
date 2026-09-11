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
    public void theCatalogueIsNotEmpty()
    {
        // Everything below reads the catalogue. Were it to stop matching, each of those tests would
        // pass over an empty list and prove nothing at all.
        assertTrue("the facade's own catalogue named no operations", catalogued().size() >= 9);
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
