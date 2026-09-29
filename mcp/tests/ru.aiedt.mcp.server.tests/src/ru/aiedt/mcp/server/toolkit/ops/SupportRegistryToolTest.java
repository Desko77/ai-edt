/**
 * AI-EDT - 1C AI tools for EDT
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.support.BmSupportRegistryHelper;
import ru.aiedt.mcp.server.support.SupportSnapshot;
import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Contract of the vendor-support reader.
 * <p>
 * Everything asserted here holds without a workspace: the argument handling, the refusals, and the
 * promises the description and schema make to a client. What the tool reports about a real
 * configuration needs an open project and is checked on a stand instead.
 * </p>
 */
public class SupportRegistryToolTest
{
    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    public void itIsNamedAndReadOnlyInItsDescription()
    {
        SupportRegistryTool tool = new SupportRegistryTool();
        assertEquals("support_registry", tool.getName());
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
        String description = tool.getDescription();
        assertTrue("the one operation that writes has to be named as such: a support mode decides "
            + "what a vendor update may overwrite, and a caller reading this description is how "
            + "the difference between reporting and writing gets noticed",
            description.contains("restore_modes"));
        assertTrue("and it has to say that writing needs asking for - the default reports and "
            + "changes nothing", description.contains("apply=true"));
    }

    @Test
    public void theDescriptionSeparatesDeclarationFromMeasurement()
    {
        // The single most misleading reading of this tool is that CHANGES_ALLOWED means somebody
        // changed the object. It does not, and the description is where a client learns that.
        String description = new SupportRegistryTool().getDescription();
        assertTrue("the description must say a mode is what was declared, not whether the object "
            + "was modified", description.contains("not whether the object was modified"));
        assertTrue("and it must name the tool that answers the other question",
            description.contains("compare_three_way"));
    }

    @Test
    public void missingOperationIsRefusedWithTheChoices()
    {
        String answer = new SupportRegistryTool().execute(args());
        assertTrue(answer.contains("operation is required"));
        assertTrue("a refusal that does not list the choices makes the caller guess",
            answer.contains("status") && answer.contains("list_objects")
                && answer.contains("object_mode"));
    }

    @Test
    public void anUnknownOperationIsRefusedRatherThanIgnored()
    {
        String answer = new SupportRegistryTool().execute(args("operation", "set_mode"));
        assertTrue(answer.contains("Unknown operation"));
        assertTrue(answer.contains("set_mode"));
    }

    @Test
    public void camelCaseOperationsAreAccepted()
    {
        // listObjects and list_objects must not be two different answers.
        String answer = new SupportRegistryTool().execute(args("operation", "listObjects"));
        assertFalse("camelCase must resolve to the canonical operation, not be rejected as unknown",
            answer.contains("Unknown operation"));
    }

    @Test
    public void helpListsEveryOperation()
    {
        String help = new SupportRegistryTool().execute(args("operation", "help"));
        assertTrue(help.contains("status"));
        assertTrue(help.contains("list_objects"));
        assertTrue(help.contains("object_mode"));
    }

    @Test
    public void helpExplainsWhatTheModesMean()
    {
        String help = new SupportRegistryTool().execute(args("operation", "help", "topic", "modes"));
        assertTrue(help.contains("CHANGES_NOT_ALLOWED"));
        assertTrue(help.contains("CHANGES_ALLOWED"));
        assertTrue(help.contains("CANCELLED"));
        assertTrue("the modes topic must say the configuration root has to be open first, which is "
            + "the rule people hit before any other", help.contains("Configuration"));
    }

    @Test
    public void anUnknownHelpTopicNamesTheAvailableOnes()
    {
        String help = new SupportRegistryTool().execute(args("operation", "help", "topic", "sql"));
        assertTrue(help.contains("Unknown topic"));
        assertTrue(help.contains("modes"));
        assertTrue(help.contains("workflow"));
    }

    @Test
    public void theSchemaDeclaresEveryParameterTheToolReads()
    {
        // A parameter the tool reads but does not declare is unreachable: the schema is the only
        // place a client learns it exists.
        String schema = new SupportRegistryTool().getInputSchema();
        for (String declared : new String[] {"operation", "topic", "projectName", "objectFqn",
            "userMode", "parentId", "offset", "limit"})
        {
            assertTrue(declared + " is read by execute() but missing from the schema",
                schema.contains('"' + declared + '"'));
        }
    }

    @Test
    public void theSchemaExplainsWhyAVendorHasToBeNamed()
    {
        // A configuration can sit on several supports at once, and then a mode belongs to a pair of
        // object and vendor. The schema has to say so, or the argument reads like noise.
        String schema = new SupportRegistryTool().getInputSchema();
        assertTrue(schema.contains("more than one"));
    }

    /**
     * The state the refusal funnel leaves past the page limit: the list stops, the count does not.
     * <p>
     * What the funnel itself does with the count is asserted where the funnel is reachable, beside
     * the helper; this is the same state, built to be read by the answer.
     * </p>
     *
     * @return a restore every object of which was refused
     */
    private static BmSupportRegistryHelper.Restore everyObjectRefused()
    {
        BmSupportRegistryHelper.Restore restore = new BmSupportRegistryHelper.Restore();
        restore.refusedCount = BmSupportRegistryHelper.PAGE_LIMIT + 1;
        for (int i = 0; i < BmSupportRegistryHelper.PAGE_LIMIT; i++)
        {
            restore.refused.add("object " + i + ": the mode was not written"); //$NON-NLS-1$
        }
        return restore;
    }

    /**
     * A drift the restore found and could not act on.
     *
     * @return the drift
     */
    private static SupportSnapshot.Drift oneObjectLostItsMode()
    {
        SupportSnapshot.Drift drift = new SupportSnapshot.Drift();
        drift.changed.add("00000000-0000-0000-0000-000000000001: ChangesAllowed -> "
            + "ChangesNotAllowed");
        return drift;
    }

    /**
     * A refusal list cut to one page is answered as a page, with the whole number beside it.
     * <p>
     * The names are worth nothing without the total: 500 of them over a configuration where the
     * write refused wholesale read as a complete account of 500 refusals, and the object that is
     * not in the list cannot be found from the answer at all.
     * </p>
     */
    @Test
    public void aTruncatedRefusalListIsCountedAndNamedAsAPage()
    {
        BmSupportRegistryHelper.Restore restore = everyObjectRefused();

        String answer = SupportRegistryTool
            .restoreAnswer("SomeProject", "modes.tsv", true, restore).toJson(); //$NON-NLS-1$

        assertEquals(BmSupportRegistryHelper.PAGE_LIMIT, restore.refused.size());
        assertTrue("the answer has to name the first object of the page: " + answer,
            answer.contains("object 0: the mode was not written"));
        assertFalse("the answer must not carry more than a page of names: " + answer,
            answer.contains("object 500")); //$NON-NLS-1$
        assertTrue("the answer has to give the number of objects refused, not the number of names "
            + "it carries: " + answer, answer.contains("\"refusedCount\":501")); //$NON-NLS-1$
        assertTrue("and it has to say that the list is short of that number: " + answer,
            answer.contains("\"refusedTruncated\":true")); //$NON-NLS-1$
    }

    /**
     * The sentence that sends a caller to the refusal list says when the list is a page.
     * <p>
     * "See refused for each one" cannot be followed over a list that stops at the page limit, and a
     * caller who tries reads the page as the whole answer.
     * </p>
     */
    @Test
    public void aNoteOverATruncatedRefusalListNamesThePageLimit()
    {
        BmSupportRegistryHelper.Restore restore = everyObjectRefused();
        restore.drift = oneObjectLostItsMode();

        String note = SupportRegistryTool.restoreNote(true, restore);

        assertTrue("the note has to point at the list it is talking about: " + note,
            note.contains("refused"));
        assertTrue("and it has to say how many names the list holds of how many refusals: " + note,
            note.contains("first 500 of the 501")); //$NON-NLS-1$
        assertTrue("and where the whole number is reported: " + note, note.contains("refusedCount")); //$NON-NLS-1$
    }

    /**
     * The answer of a listing carries whether the walk that named its objects saw the whole model.
     * <p>
     * A page whose entries carry no name reads as a page of objects the configuration no longer
     * has, unless the answer says the index it came from was not whole.
     * </p>
     */
    @Test
    public void aListingSaysWhetherTheWalkWasWhole()
    {
        BmSupportRegistryHelper.Listing listing = new BmSupportRegistryHelper.Listing();
        listing.indexComplete = false;

        String answer = SupportRegistryTool.listingAnswer("SomeProject", listing).toJson(); //$NON-NLS-1$

        assertTrue("a page of nameless entries needs this to be readable at all: " + answer,
            answer.contains("\"indexComplete\":false")); //$NON-NLS-1$
    }

    /**
     * A restore that found drift and could not act on any of it says what stands in the way.
     * <p>
     * The all-clear sentence belongs to a restore with nothing to write; over a drift the answer has
     * to give the number of objects that lost their mode and where the refusals are reported.
     * </p>
     */
    @Test
    public void aRestoreThatFoundDriftAndWroteNothingWarns()
    {
        // apply=true over a drift where nothing could be written used to close with the all-clear
        // sentence - "no mode needed putting back" - over the drift it had just reported, because
        // every object it tried had been refused or was missing.
        BmSupportRegistryHelper.Restore restore = new BmSupportRegistryHelper.Restore();
        restore.restored = 0;
        SupportSnapshot.Drift drift = new SupportSnapshot.Drift();
        drift.changed.add("00000000-0000-0000-0000-000000000001: ChangesAllowed -> "
            + "ChangesNotAllowed");
        restore.drift = drift;

        String note = SupportRegistryTool.restoreNote(true, restore);

        assertTrue("the note has to name what stands between the caller and a finished restore: "
            + note, note.contains("refused"));
        assertTrue("the note has to say how many objects lost their mode: " + note,
            note.contains("1 object(s)"));
        assertTrue("the all-clear sentence belongs to a clean restore, and this one found drift: "
            + note, !note.contains("no mode needed putting back"));
    }

    /**
     * A clean restore that was asked to write and had nothing to write says so with the all-clear.
     * <p>
     * The one case that sentence describes: the difference was computed, no surviving object
     * changed its mode, and nothing was there to put back.
     * </p>
     */
    @Test
    public void aCleanAppliedRestoreThatWroteNothingSaysSo()
    {
        BmSupportRegistryHelper.Restore restore = new BmSupportRegistryHelper.Restore();
        restore.restored = 0;
        restore.drift = new SupportSnapshot.Drift();

        String note = SupportRegistryTool.restoreNote(true, restore);

        assertTrue("a clean restore with nothing to write is the one case the all-clear sentence"
            + " describes: " + note, note.contains("no mode needed putting back"));
    }

    /**
     * A restore without apply writes nothing and says how to ask for the write.
     */
    @Test
    public void aDryRunSaysItWroteNothing()
    {
        BmSupportRegistryHelper.Restore restore = new BmSupportRegistryHelper.Restore();
        restore.drift = new SupportSnapshot.Drift();

        String note = SupportRegistryTool.restoreNote(false, restore);

        assertTrue(note, note.contains("pass apply=true"));
    }
}
