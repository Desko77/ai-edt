/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ru.aiedt.mcp.server.support.FacadeHelpSearch;
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

    /** An argument as a facade draws one: a bullet naming it, or a heading of its own. */
    private static final Pattern ARGUMENT =
        Pattern.compile("(?m)^(?:- \\*\\*|#{3,} )([A-Za-z0-9_]+)");

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
     * Runs one call whose operation goes under <code>action</code>: the debugger facade declares
     * its operations that way rather than as <code>operation</code>, which is what its own refusal
     * names when the other key is given.
     *
     * @param facade the tool to call.
     * @param action the action argument.
     * @return the raw answer, or <code>null</code> when the call threw
     */
    private static String runAction(IMcpTool facade, String action)
    {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("action", action);
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
     * An answer as its caller reads it. A facade that wraps its help in a JSON envelope escapes
     * what JSON escapes, so a label written with <code>=</code> arrives spelled <code>=</code>,
     * and the line breaks of the help arrive as the two characters they are escaped to, which
     * leaves the whole document looking like one line.
     *
     * @param answer the raw answer; may be <code>null</code>.
     * @return the answer with those escapes undone; <code>null</code> when it was
     */
    private static String plain(String answer)
    {
        return answer == null ? null
            : answer.replace("\\u003d", "=").replace("\\n", "\n");
    }

    /**
     * Whether a facade answers one topic at a time. A facade with no topics of its own renders the
     * same document whatever topic it is asked for, and then a name in that document is a section
     * rather than a topic that could be searched argument by argument.
     *
     * @param facade the tool to ask.
     * @return <code>true</code> when an absent topic is refused as absent
     */
    private static boolean answersTopics(IMcpTool facade)
    {
        String refused = plain(help(facade, "zzz_no_such_topic_zzz", null));
        return refused != null && refused.contains("Unknown topic");
    }

    /** Words long enough to stand for one argument rather than for prose. */
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{5,}");

    /**
     * The words of one piece of help that occur in it exactly once, so that the chunk carrying one
     * is the only chunk that can carry it.
     *
     * @param text one piece of help.
     * @return the words, lowercased, never <code>null</code>
     */
    private static List<String> wordsOccurringOnce(String text)
    {
        String lowered = text.toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        Matcher word = WORD.matcher(text);
        while (word.find())
        {
            String single = word.group().toLowerCase(Locale.ROOT);
            int count = 0;
            for (int at = lowered.indexOf(single); at >= 0; at = lowered.indexOf(single, at + 1))
            {
                count++;
            }
            if (count == 1 && !found.contains(single))
            {
                found.add(single);
            }
        }
        return found;
    }

    /**
     * One argument's help as it is laid out: a block of lines, each block starting at the line that
     * names an argument - a heading deeper than a section, or a bullet in a list of names.
     *
     * @param topic one operation's help text.
     * @return the blocks, in the order they appear, never <code>null</code>
     */
    private static List<String> argumentBlocks(String topic)
    {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : topic.split("\n", -1))
        {
            if (ARGUMENT.matcher(line).lookingAt() && current.length() > 0)
            {
                blocks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        blocks.add(current.toString());
        return blocks;
    }

    /**
     * A word of one block that the search reaches in exactly one chunk, which is what makes it safe
     * to pair with a word of another block: were the two in one chunk, that chunk would carry two
     * arguments' prose.
     *
     * @param facade the tool to ask.
     * @param topic the operation to search in.
     * @param block the argument's lines.
     * @return the word, or <code>null</code> when the block has none the search reaches alone
     */
    private static String wordOf(IMcpTool facade, String topic, String block)
    {
        for (String word : wordsOccurringOnce(block))
        {
            String answer = plain(help(facade, topic, word));
            if (answer != null && answer.contains("1 chunk matches"))
            {
                return word;
            }
        }
        return null;
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
                String answer = plain(help(facade, null, operation));
                if (answer == null || !answer.contains("## From " + FacadeHelpSearch.CATALOG_ORIGIN)
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
        String answer = plain(help(tool(registry, "diagnostics"), "get_project_errors",
            "get_project_errors"));
        if (answer == null || !answer.contains("## From topic=get_project_errors")
            || answer.contains("## From " + FacadeHelpSearch.CATALOG_ORIGIN))
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
    public void theCatalogueChunkSaysHowToAskForIt()
    {
        String answer = plain(help(tool(registry, "diagnostics"), null, "get_project_errors"));
        if (answer == null || !answer.contains("## From " + FacadeHelpSearch.CATALOG_ORIGIN)
            || !FacadeHelpSearch.CATALOG_ORIGIN.contains("operation=help"))
        {
            throw new AssertionError("A chunk of the catalogue must be labelled by what a caller "
                + "asks for to receive it - operation=help without a topic - answered:\n" + answer);
        }
    }

    @Test
    public void aSectionIsFoundByWordsFromTwoOfItsSubsections()
    {
        // edit_form names its operations as `###` headings under one `## Operations` section: a
        // word from each of two of them is a word from that one section.
        String answer = help(tool(registry, "edit_form"), null, "addField addGroup");
        if (answer == null || !answer.contains("### addField") || !answer.contains("### addGroup")
            || answer.contains("Nothing matches"))
        {
            throw new AssertionError("addField and addGroup stand under one section of edit_form's "
                + "help, so a search for both must show that section, answered:\n" + answer);
        }
    }

    @Test
    public void wordsFromTwoArgumentsOfOneOperationDoNotMatch()
    {
        // code_search draws the argument names in one paragraph above the headings for them, and
        // two arguments of one operation are two chunks wherever the facade draws them.
        IMcpTool codeSearch = tool(registry, "code_search");
        for (String query : new String[] {"caseSensitive fileMask", "query fileMask"})
        {
            String plain = help(codeSearch, null, query);
            if (plain == null || !plain.contains("Nothing matches"))
            {
                throw new AssertionError("find=\"" + query + "\" on code_search names two "
                    + "arguments of one operation and no single chunk carries both, answered:\n"
                    + plain);
            }
        }
        for (String word : new String[] {"caseSensitive", "fileMask", "query"})
        {
            String alone = help(codeSearch, "text_search", word);
            if (alone == null || alone.contains("Nothing matches"))
            {
                throw new AssertionError("Each of those words is there on its own: find=" + word
                    + " with topic=text_search must find the argument, answered:\n" + alone);
            }
        }
        String scoped = help(codeSearch, "text_search", "caseSensitive fileMask");
        if (scoped == null || !scoped.contains("Nothing matches"))
        {
            throw new AssertionError("find=\"caseSensitive fileMask\" with topic=text_search must "
                + "match nothing: the two words are two arguments, answered:\n" + scoped);
        }
    }

    @Test
    public void argumentsAFacadeListsInOneGroupDoNotShareAChunk()
    {
        String[] facades = {"support_registry", "config_io"};
        String[] topics = {"restore_modes", "export_configuration_to_cf"};
        String[] queries = {"apply snapshotPath", "outputPath skipValidation"};
        for (int i = 0; i < facades.length; i++)
        {
            IMcpTool facade = tool(registry, facades[i]);
            for (String word : queries[i].split(" "))
            {
                String alone = help(facade, topics[i], word);
                if (alone == null || alone.contains("Nothing matches"))
                {
                    throw new AssertionError("find=" + word + " with topic=" + topics[i] + " on "
                        + facades[i] + " names an argument of that operation and must find it, "
                        + "answered:\n" + alone);
                }
            }
            String together = help(facade, topics[i], queries[i]);
            if (together == null || !together.contains("Nothing matches"))
            {
                throw new AssertionError("find=\"" + queries[i] + "\" with topic=" + topics[i]
                    + " on " + facades[i] + " names two arguments of one operation, and no single "
                    + "chunk carries both, answered:\n" + together);
            }
        }
    }

    @Test
    public void aTypoedActionIsAnsweredWithTheClosestActionAndWhatItDoes()
    {
        String answer = plain(runAction(tool(registry, "launch_debugger"), "debug_statu"));
        if (answer == null || !answer.contains("Unknown action") || !answer.contains("debug_status")
            || !answer.contains("current debug state"))
        {
            throw new AssertionError("The refusal for action=debug_statu must name debug_status "
                + "with what it does, answered:\n" + answer);
        }
    }

    @Test
    public void aTypoedEditFormOperationIsAnsweredWithTheClosestAndWhatItDoes()
    {
        // Named as any other call names them: edit_form reads its project and form before it can
        // say anything about the operation, and one of those refuses first otherwise.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectName", "Demo");
        params.put("formFqn", "Catalog.Products.Form.ItemForm.Form");
        params.put("operation", "addFild");
        String answer;
        try
        {
            answer = plain(tool(registry, "edit_form").execute(params));
        }
        catch (RuntimeException | LinkageError thrown)
        {
            answer = null;
        }
        if (answer == null || !answer.contains("Unknown operation") || !answer.contains("addField")
            || !answer.contains("Add a field element to the form."))
        {
            throw new AssertionError("The refusal for operation=addFild must name addField with "
                + "what it does, answered:\n" + answer);
        }
    }

    @Test
    public void theSingleDocumentOfAFacadeWithoutTopicsNamesItsSections()
    {
        String answer = help(tool(registry, "edit_form"), null, "zzzzqqqq");
        if (answer == null || !answer.contains("its sections:") || !answer.contains("addField")
            || !answer.contains("removeItem"))
        {
            throw new AssertionError("A search matching nothing in edit_form's single document must "
                + "name its sections, answered:\n" + answer);
        }
    }

    @Test
    public void twoWordsFromTwoArgumentsOfOneOperationNeverShareAChunk()
    {
        // A sweep rather than a case, over the help as the facades render it: for every operation
        // each facade answers as a topic, one word is taken from each of two adjacent arguments and
        // both are searched at once.
        //
        // The words are the ones occurring exactly once in that operation's help, so the chunk
        // carrying each is the only chunk that can. A pair the search reaches in one chunk is
        // therefore a chunk carrying two arguments - unless the two words were prose to begin with,
        // which is why a word the search reaches in more than one chunk is passed over: an argument
        // whose description names another argument is prose, not a chunk boundary.
        List<String> merged = new ArrayList<>();
        int judged = 0;
        for (IMcpTool facade : facades(registry))
        {
            if (!answersTopics(facade))
            {
                // The facade renders one document whatever topic it is asked for; its names are
                // sections of that document rather than topics with arguments of their own.
                continue;
            }
            Matcher offered = CATALOGUED.matcher(plain(help(facade, null, null)));
            while (offered.find())
            {
                String operation = offered.group(1);
                if ("help".equals(operation))
                {
                    continue;
                }
                String topic = plain(help(facade, operation, null));
                if (topic == null || topic.contains("Unknown topic"))
                {
                    continue;
                }
                List<String> blocks = argumentBlocks(topic);
                for (int i = 0; i + 1 < blocks.size(); i++)
                {
                    String first = wordOf(facade, operation, blocks.get(i));
                    String second = wordOf(facade, operation, blocks.get(i + 1));
                    if (first == null || second == null || first.equals(second))
                    {
                        continue;
                    }
                    judged++;
                    String together = plain(help(facade, operation, first + " " + second));
                    if (together == null || !together.contains("Nothing matches"))
                    {
                        merged.add(facade.getName() + " topic=" + operation + " find=\"" + first
                            + " " + second + "\"");
                    }
                }
            }
        }
        if (judged == 0)
        {
            throw new AssertionError("The sweep judged no pair at all, which means it read no "
                + "operation's arguments rather than finding them in separate chunks.");
        }
        if (!merged.isEmpty())
        {
            throw new AssertionError("Two arguments of one operation are two chunks, each with its "
                + "own description, so no chunk carries both:\n  "
                + String.join("\n  ", merged));
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
