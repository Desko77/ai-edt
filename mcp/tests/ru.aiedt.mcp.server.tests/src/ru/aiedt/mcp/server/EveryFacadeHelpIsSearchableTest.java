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
 * `operation=help find=...` on every facade searches that facade's own help.
 * <p>
 * The search is wired once per facade: the facade hands its topics and its help function to the
 * shared helper, and a facade that wired it wrong - searched another facade's topics, forgot the
 * catalogue, treated "no match" as a failure - is only caught by asking through the facade
 * itself. This sweep asks: every operation a facade offers must be findable by its own name, a
 * search given with a topic must stay inside that topic, and an empty answer must still read as
 * an answer rather than an error.
 * </p>
 */
public class EveryFacadeHelpIsSearchableTest
{
    /** An operation in a facade's own catalogue, written `- **name** - ...`. */
    private static final Pattern CATALOGUED =
        Pattern.compile("\\*\\*([a-z0-9_]+)\\*\\*");

    /** How many tools answer a catalogue of their own operations. Counted, not estimated. */
    private static final int EXPECTED_FACADES = 11;

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
     * Runs one call against a facade without throwing: a facade that fails to answer is reported
     * by the caller as a <code>null</code> answer, so one broken facade names itself rather than
     * hiding the rest of the sweep behind its stack trace.
     *
     * @param facade the tool to call.
     * @param operation the operation argument.
     * @param topic the topic argument, or <code>null</code> to omit it.
     * @param find the find argument, or <code>null</code> to omit it.
     * @return the raw answer, or <code>null</code> when the call threw
     */
    private static String run(IMcpTool facade, String operation, String topic, String find)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("operation", operation);
        if (topic != null)
        {
            params.put("topic", topic);
        }
        if (find != null)
        {
            params.put("find", find);
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

    /**
     * Asks a facade's help, optionally confined to a topic and optionally searched.
     *
     * @param facade the tool to ask.
     * @param topic the topic argument, or <code>null</code> to omit it.
     * @param find the find argument, or <code>null</code> to omit it.
     * @return the raw answer, or <code>null</code> when the call threw
     */
    private static String help(IMcpTool facade, String topic, String find)
    {
        return run(facade, "help", topic, find);
    }

    /**
     * Facades, recognised by answering a catalogue headed with their own name - the same
     * recognition the sweep over unknown topics uses.
     *
     * @param registry the tool registry to sweep.
     * @return the facades found, never <code>null</code>
     */
    private static List<IMcpTool> facades(McpToolCatalog registry)
    {
        List<IMcpTool> found = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String catalogue = help(tool, null, null);
            if (catalogue != null && catalogue.contains("# " + tool.getName())
                && CATALOGUED.matcher(catalogue).find())
            {
                found.add(tool);
            }
        }
        return found;
    }

    /**
     * One tool by name, or a failure that names what was asked for.
     *
     * @param registry the tool registry to ask.
     * @param name the wire name of the tool.
     * @return the tool, never <code>null</code>
     */
    private static IMcpTool tool(McpToolCatalog registry, String name)
    {
        for (IMcpTool candidate : registry.getAllTools())
        {
            if (name.equals(candidate.getName()))
            {
                return candidate;
            }
        }
        throw new AssertionError("No tool named " + name + " is registered.");
    }

    @Test
    public void theSweepFindsEveryFacadeItFoundBefore()
    {
        // Counted against what is there, as the sibling sweep is: a facade whose catalogue stops
        // answering leaves the sweep silently, and a sweep over fewer reads like one that passed.
        int found = facades(registry).size();
        if (found != EXPECTED_FACADES)
        {
            throw new AssertionError(found + " facades answered their own catalogue, and "
                + EXPECTED_FACADES + " did when this was written.");
        }
    }

    @Test
    public void everyOperationAFacadeOffersIsFoundByItsOwnName()
    {
        List<String> missed = new ArrayList<>();
        for (IMcpTool facade : facades(registry))
        {
            Matcher offered = CATALOGUED.matcher(help(facade, null, null));
            while (offered.find())
            {
                String operation = offered.group(1);
                if ("help".equals(operation))
                {
                    continue;
                }
                String answer = help(facade, null, operation);
                if (answer == null || !answer.contains("## From the operation catalog")
                    || !answer.contains("**" + operation + "**"))
                {
                    missed.add(facade.getName() + " find=" + operation);
                }
            }
        }

        if (!missed.isEmpty())
        {
            throw new AssertionError("Searching a facade's help for the name of an operation it "
                + "offers must find the catalogue entry for that operation:\n  "
                + String.join("\n  ", missed));
        }
    }

    @Test
    public void aCyrillicWordMatchesRegardlessOfCase()
    {
        // The workflow table of code_search names СообщитьПользователю in mixed case; asked for
        // in all caps it must still be found, because folding uses the root locale and not the
        // machine's.
        String answer = help(tool(registry, "code_search"), null, "СООБЩИТЬПОЛЬЗОВАТЕЛЮ");
        if (answer == null || !answer.contains("СообщитьПользователю"))
        {
            throw new AssertionError("find=СООБЩИТЬПОЛЬЗОВАТЕЛЮ on code_search must find the chunk "
                + "naming СообщитьПользователю, answered:\n" + answer);
        }
    }

    @Test
    public void findWithATopicSearchesOnlyThatTopic()
    {
        String answer = help(tool(registry, "diagnostics"), "get_project_errors",
            "get_project_errors");
        if (answer == null || !answer.contains("## From topic=get_project_errors")
            || answer.contains("## From the operation catalog"))
        {
            throw new AssertionError("find given together with topic must search only that "
                + "topic, answered:\n" + answer);
        }
    }

    @Test
    public void noMatchIsAnAnswerNotAnError()
    {
        String plain = help(tool(registry, "diagnostics"), null, "zzzzqqqq");
        if (plain == null || !plain.contains("Nothing matches") || plain.contains("Unknown"))
        {
            throw new AssertionError("A search that matches nothing must answer with what can "
                + "be asked instead, not refuse, answered:\n" + plain);
        }
        // support_registry wraps its help in a JSON envelope; the envelope must still say success.
        String wrapped = help(tool(registry, "support_registry"), null, "zzzzqqqq");
        if (wrapped == null || !wrapped.contains("\"success\":true"))
        {
            throw new AssertionError("A wrapped no-match answer must stay a success, "
                + "answered:\n" + wrapped);
        }
    }

    @Test
    public void aTypoedOperationIsAnsweredWithTheClosestMatchAndWhatItDoes()
    {
        String answer = run(tool(registry, "diagnostics"), "get_projct_errors", null, null);
        if (answer == null || !answer.contains("Unknown operation")
            || !answer.contains("get_project_errors") || !answer.contains("configuration problems"))
        {
            throw new AssertionError("The refusal for get_projct_errors must name "
                + "get_project_errors with what it does, answered:\n" + answer);
        }
    }

    @Test
    public void aTypoedTopicIsAnsweredWithTheClosestMatch()
    {
        String answer = help(tool(registry, "diagnostics"), "get_projct_errors", null);
        if (answer == null || !answer.contains("Unknown topic")
            || !answer.contains("get_project_errors"))
        {
            throw new AssertionError("The refusal for topic=get_projct_errors must name "
                + "get_project_errors, answered:\n" + answer);
        }
    }
}
