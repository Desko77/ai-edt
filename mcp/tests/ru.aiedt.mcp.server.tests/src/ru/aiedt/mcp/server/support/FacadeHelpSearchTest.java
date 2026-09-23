/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * The rules of searching a facade's help, on a facade small enough to read whole.
 * <p>
 * What is pinned here is the contract the facades share: what a chunk of help is, that a match is
 * a case-insensitive substring in any alphabet, that several words must share one chunk, that the
 * answer counts everything and shows the first ten, and that an empty answer lists what can be
 * asked instead of failing.
 * <p>
 * The two chunkings are pinned as well. A document of sections is cut at its sections, and what
 * stands under a section belongs to it. An operation's parameters are cut at their arguments
 * however the facade draws them, and neither the operation's own heading nor the paragraph that
 * names every argument in passing carries the arguments' words.
 * </p>
 * </p>
 */
public class FacadeHelpSearchTest
{
    private static final List<String> TOPICS = Arrays.asList("alpha", "workflow");

    /** The topic of this facade that names an operation, and so is searched argument by argument. */
    private static final List<String> OPERATIONS = Collections.singletonList("alpha");

    /** A catalog whose `## Operations` section holds one `###` subsection per operation. */
    private static final String NESTED_CATALOG =
        "# demo - operations\n\n"
            + "## Common\n\n- **alpha** - reads the Таблица of one project.\n\n"
            + "## Operations\n\n"
            + "### addField\n\nAdds a field.\n\n"
            + "### addGroup\n\nAdds a group.\n";

    /** One operation's parameters: its arguments are bullets under a heading per group. */
    private static final String BULLETED_ARGUMENTS =
        "## alpha - parameters\n\n"
            + "### Established for this operation\n\n"
            + "- **mode**  _string_ - what to restore.\n"
            + "- **snapshotPath**  _string_ - where the snapshot is kept.\n\n"
            + "### Accepted here as well\n\n"
            + "- **projectName**  _string_ - the project it belongs to.\n";

    /** One operation's help: a paragraph naming every argument above the headings for them. */
    private static final String OPENING_PARAGRAPH =
        "## alpha - parameters\n\n"
            + "Parameters: query, caseSensitive, fileMask.\n\n"
            + "### query\n\nThe text to look for.\n\n"
            + "### caseSensitive\n\nWhether case matters.\n";

    /**
     * A small facade to search: a catalog of two operations, one operation's parameters and one
     * named topic. The Russian word is there on purpose: case folding has to work outside ASCII.
     *
     * @param topic the topic to render; <code>null</code> for the catalog.
     * @return the topic's help text
     */
    private static String helpOf(String topic)
    {
        if (topic == null)
        {
            return "# demo - operations\n\n"
                + "- **alpha** - reads the Таблица of one project.\n"
                + "- **beta** - writes nothing.\n";
        }
        if ("alpha".equals(topic))
        {
            return "## alpha - parameters\n\n"
                + "### projectName\n  _string, required_\n\nWhich project holds the Таблица.\n\n"
                + "### limit\n  _integer_\n\nCap on rows returned.\n";
        }
        if ("workflow".equals(topic))
        {
            return "# demo - operation picker\n\n"
                + "| Goal | Operation |\n|---|---|\n| Read the table | alpha |\n";
        }
        return "# Unknown topic '" + topic + "'.\n\nAvailable: workflow.\n";
    }

    /**
     * How many chunks an answer shows, counted by the marker every shown chunk carries.
     *
     * @param answer the search answer.
     * @return the number of shown chunks
     */
    private static int shown(String answer)
    {
        int count = 0;
        int from = 0;
        int at = answer.indexOf("## From ", from);
        while (at >= 0)
        {
            count++;
            from = at + 1;
            at = answer.indexOf("## From ", from);
        }
        return count;
    }

    @Test
    public void anOperationNameFindsTheCatalogEntry()
    {
        String answer = FacadeHelpSearch.search("demo", "beta", null, TOPICS,
            OPERATIONS, FacadeHelpSearchTest::helpOf);

        assertTrue(answer, answer.contains("1 chunk matches"));
        assertTrue(answer, answer.contains("## From " + FacadeHelpSearch.CATALOG_ORIGIN));
        assertTrue(answer, answer.contains("- **beta** - writes nothing."));
    }

    @Test
    public void aWordMatchesInAnyCaseAndAnyAlphabet()
    {
        String russian = FacadeHelpSearch.search("demo", "ТАБЛИЦА", null, TOPICS,
            OPERATIONS, FacadeHelpSearchTest::helpOf);
        // The catalog line and the projectName description, catalog first.
        assertTrue(russian, russian.contains("2 chunks match"));
        assertTrue(russian,
            russian.indexOf("From " + FacadeHelpSearch.CATALOG_ORIGIN)
                < russian.indexOf("From topic=alpha"));

        String english = FacadeHelpSearch.search("demo", "ROWS", null, TOPICS,
            OPERATIONS, FacadeHelpSearchTest::helpOf);
        assertTrue(english, english.contains("Cap on rows returned."));
    }

    @Test
    public void severalWordsMustShareOneChunk()
    {
        String both = FacadeHelpSearch.search("demo", "cap rows", null, TOPICS,
            OPERATIONS, FacadeHelpSearchTest::helpOf);
        assertTrue(both, both.contains("## From topic=alpha"));

        String apart = FacadeHelpSearch.search("demo", "cap beta", null, TOPICS,
            OPERATIONS, FacadeHelpSearchTest::helpOf);
        assertTrue(apart, apart.contains("Nothing matches"));
    }

    @Test
    public void twoSubsectionsOfOneSectionShareItsChunk()
    {
        String answer = FacadeHelpSearch.search("demo", "addField addGroup", null,
            Collections.<String> emptyList(), Collections.<String> emptyList(),
            topic -> NESTED_CATALOG);

        assertTrue(answer, answer.contains("1 chunk matches"));
        assertTrue(answer, answer.contains("### addField"));
        assertTrue(answer, answer.contains("### addGroup"));
    }

    @Test
    public void twoArgumentsInOneGroupShareNoChunk()
    {
        String together = FacadeHelpSearch.search("demo", "mode snapshotPath", "alpha", TOPICS,
            OPERATIONS, topic -> BULLETED_ARGUMENTS);
        assertTrue(together, together.contains("Nothing matches"));

        String alone = FacadeHelpSearch.search("demo", "snapshotPath", "alpha", TOPICS,
            OPERATIONS, topic -> BULLETED_ARGUMENTS);
        assertTrue(alone, alone.contains("- **snapshotPath**"));
        assertFalse(alone, alone.contains("- **mode**"));
    }

    @Test
    public void aParagraphNamingEveryArgumentIsNotAChunkOfItsOwn()
    {
        String together = FacadeHelpSearch.search("demo", "caseSensitive fileMask", "alpha", TOPICS,
            OPERATIONS, topic -> OPENING_PARAGRAPH);
        assertTrue(together, together.contains("Nothing matches"));

        String alone = FacadeHelpSearch.search("demo", "caseSensitive", "alpha", TOPICS,
            OPERATIONS, topic -> OPENING_PARAGRAPH);
        assertTrue(alone, alone.contains("### caseSensitive"));
        assertFalse(alone, alone.contains("fileMask"));
    }

    @Test
    public void moreThanTenMatchesAreCountedAndCut()
    {
        String answer = FacadeHelpSearch.search("demo", "row", null,
            Collections.singletonList("big"), Collections.singletonList("big"), topic ->
            {
                if (topic == null)
                {
                    return "# demo - operations\n\n- **big** - a wide topic.\n";
                }
                StringBuilder doc = new StringBuilder("## big - parameters\n\n");
                for (int i = 1; i <= 12; i++)
                {
                    doc.append("### p").append(i).append("\n\nrow number ").append(i)
                        .append("\n\n");
                }
                return doc.toString();
            });

        assertTrue(answer, answer.contains("12 chunks match; the first 10 of them"));
        assertEquals(answer, 10, shown(answer));
    }

    @Test
    public void findWithATopicSearchesOnlyThatTopic()
    {
        String answer = FacadeHelpSearch.search("demo", "таблица", "alpha", TOPICS,
            OPERATIONS, FacadeHelpSearchTest::helpOf);

        assertTrue(answer, answer.contains("1 chunk matches"));
        assertTrue(answer, answer.contains("## From topic=alpha"));
        assertFalse(answer, answer.contains("From " + FacadeHelpSearch.CATALOG_ORIGIN));
    }

    @Test
    public void findWithAnUnknownTopicAnswersTheRefusalItself()
    {
        String answer = FacadeHelpSearch.search("demo", "таблица", "no-such-topic", TOPICS,
            OPERATIONS, FacadeHelpSearchTest::helpOf);

        assertTrue(answer, answer.contains("Unknown topic"));
    }

    @Test
    public void noMatchListsWhatCanBeAskedInsteadOfFailing()
    {
        String answer = FacadeHelpSearch.search("demo", "zzz", null, TOPICS,
            OPERATIONS, FacadeHelpSearchTest::helpOf);

        assertTrue(answer, answer.contains("Nothing matches"));
        assertTrue(answer, answer.contains("alpha / workflow"));
        assertFalse(answer, answer.contains("Unknown topic"));
    }

    @Test
    public void aFacadeWithoutTopicsNamesItsSectionsInstead()
    {
        String answer = FacadeHelpSearch.search("demo", "zzzqqq", null,
            Collections.<String> emptyList(), Collections.<String> emptyList(),
            topic -> NESTED_CATALOG);

        assertTrue(answer, answer.contains("its sections:"));
        assertTrue(answer, answer.contains("addField"));
        assertTrue(answer, answer.contains("addGroup"));
        assertFalse(answer, answer.contains("Unknown topic"));
    }

    @Test
    public void aRefusalNamesTheClosestWithWhatTheyDo()
    {
        Map<String, String> described = FacadeHelpSearch.describe(helpOf(null));
        String block = FacadeHelpSearch.closestMatches("alpa",
            Arrays.asList("alpha", "beta", "help"), described);

        assertTrue(block, block.contains("alpha - reads the Таблица of one project."));
        // "help" is never suggested: the refusal already ends by naming it.
        assertFalse(block, block.contains("- help"));
    }

    @Test
    public void aBulletWithSeveralNamesDescribesEachOfThem()
    {
        Map<String, String> described =
            FacadeHelpSearch.describe("# x\n\n- **step_over / step_into / step_out** - step.\n");

        assertEquals("step.", described.get("step_over"));
        assertEquals("step.", described.get("step_into"));
        assertEquals("step.", described.get("step_out"));
    }

    @Test
    public void editDistanceCountsSingleCharacterEdits()
    {
        assertEquals(0, FacadeHelpSearch.editDistance("abc", "abc"));
        assertEquals(1, FacadeHelpSearch.editDistance("abc", "abd"));
        assertEquals(1, FacadeHelpSearch.editDistance("get_projct_errors", "get_project_errors"));
        assertEquals(3, FacadeHelpSearch.editDistance("", "abc"));
    }
}
