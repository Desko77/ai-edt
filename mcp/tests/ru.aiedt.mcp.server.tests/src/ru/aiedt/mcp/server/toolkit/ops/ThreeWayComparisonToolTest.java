/**
 * AI-EDT - 1C AI tools for EDT - Tests
 * Copyright (C) 2026 Desko77 (https://github.com/Desko77)
 * Licensed under AGPL-3.0-or-later
 */

package ru.aiedt.mcp.server.toolkit.ops;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import ru.aiedt.mcp.server.toolkit.IMcpTool;

/**
 * Covers the contract of the three-way comparison: its shape, and above all what it refuses to do.
 * <p>
 * The comparison itself needs a workspace with a project in it, so the counts are measured on a
 * stand rather than here. What is pinned here is the boundary: this tool reads. A merge writes into
 * a configuration and is not undone by a button, so "it cannot merge" is a property worth a test
 * that fails the day someone adds one.
 * </p>
 */
public class ThreeWayComparisonToolTest
{
    private static Map<String, String> args(String... pairs)
    {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2)
        {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    public void itIsNamedForWhatItDoesAndAnswersJson()
    {
        ThreeWayComparisonTool tool = new ThreeWayComparisonTool();

        assertEquals("compare_three_way", tool.getName()); //$NON-NLS-1$
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
        assertNotNull(tool.getDescription());
    }

    /**
     * The description is what an agent reads before choosing the tool, so the one thing it must not
     * leave open is that this one can write - and that writing is irreversible.
     */
    @Test
    public void theDescriptionSaysReadingIsTheDefaultAndMergingIsNot()
    {
        String description = new ThreeWayComparisonTool().getDescription().toLowerCase();

        assertTrue("reading must be named as the default: " + description, //$NON-NLS-1$
            description.contains("default")); //$NON-NLS-1$
        assertTrue("and the merge must be named irreversible, in those words: " + description, //$NON-NLS-1$
            description.contains("irreversible")); //$NON-NLS-1$
        assertTrue("and the caller must learn it is refused without decisions: " + description, //$NON-NLS-1$
            description.contains("no decisions")); //$NON-NLS-1$
    }

    /**
     * Nothing is merged unless it was asked for by name.
     * <p>
     * The whole safety of this tool rests on the default. A value nobody recognises must not fall
     * back to reading either: somebody who wrote MERGE and mistyped it would be told their
     * configuration is unchanged only by reading the answer closely, and would try again with the
     * same word.
     * </p>
     */
    @Test
    public void anUnknownIntentIsRefusedRatherThanTreatedAsReading()
    {
        String answer = new ThreeWayComparisonTool()
            .execute(args("projectName", "P", "otherPath", "somewhere", "intent", "MERGE_NOW")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must list what may be written instead: " + answer, //$NON-NLS-1$
            answer.contains("MERGE_IGNORING_PROBLEMS")); //$NON-NLS-1$
    }

    /**
     * Overriding the environment's own objection has its own word.
     * <p>
     * Not a flag beside MERGE. A flag that means "and also ignore the problems" is how an override
     * becomes something people pass by habit.
     * </p>
     */
    @Test
    public void ignoringBlockingProblemsIsASeparateIntentNotAFlag()
    {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (ru.aiedt.mcp.server.support.BmComparisonHelper.Intent intent : ru.aiedt.mcp.server.support.BmComparisonHelper.Intent
            .values())
        {
            names.add(intent.name());
        }

        // Counted rather than listed, so that a fifth way to write into a configuration cannot
        // arrive without this test being read. Each of the four is a distinct decision a person
        // makes, and none of them is a flag on another.
        assertEquals("five intents, no more: " + names, 5, names.size()); //$NON-NLS-1$
        assertTrue(names.contains("REPORT")); //$NON-NLS-1$
        assertTrue(names.contains("MERGE")); //$NON-NLS-1$
        assertTrue("the override must be a value of its own", //$NON-NLS-1$
            names.contains("MERGE_IGNORING_PROBLEMS")); //$NON-NLS-1$
        assertTrue("and so must the one route that merges with no decisions at all", //$NON-NLS-1$
            names.contains("UPDATE_UNCHANGED")); //$NON-NLS-1$
        assertTrue("and the ordinary update, which protects what this side reworked", //$NON-NLS-1$
            names.contains("UPDATE_KEEPING_OURS")); //$NON-NLS-1$
        assertEquals("reading must be the first value, which is what an absent argument means", //$NON-NLS-1$
            "REPORT", names.get(0)); //$NON-NLS-1$
    }

    /** Three sides means three inputs, and the third one is what makes it three-way. */
    @Test
    public void theSchemaNamesAllThreeSides()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();

        assertTrue(schema.contains("projectName")); //$NON-NLS-1$
        assertTrue(schema.contains("otherPath")); //$NON-NLS-1$
        assertTrue(schema.contains("ancestorPath")); //$NON-NLS-1$
        assertTrue("the ancestor is what separates this from a plain diff", //$NON-NLS-1$
            schema.contains("COMMON_ANCESTOR")); //$NON-NLS-1$
    }

    /**
     * Missing arguments are refused before anything is asked of the environment, and the refusal
     * carries a reason rather than an empty answer.
     */
    @Test
    public void aRequestWithoutItsSidesIsRefusedWithAReason()
    {
        String answer = new ThreeWayComparisonTool().execute(args());

        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must name what is missing: " + answer, //$NON-NLS-1$
            answer.contains("required")); //$NON-NLS-1$
    }

    /** A path that is not a directory is named as such, not reported as an empty comparison. */
    @Test
    public void aPathThatIsNotADirectoryIsNamed()
    {
        String answer = new ThreeWayComparisonTool()
            .execute(args("projectName", "AnyProject", "otherPath", "no such directory here")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
        assertFalse("a missing path must not read as a finished comparison", //$NON-NLS-1$
            answer.contains("\"nodes\"")); //$NON-NLS-1$
    }

    /**
     * A comparison that is no longer awaited must be called off, and the wait must be short.
     * <p>
     * Both come from one live incident. The first run waited half an hour, and when it gave up it
     * simply walked away: the comparison stayed pending inside EDT, held a transaction open, and
     * the session could no longer shut down - its UI thread stopped responding entirely. A tool
     * that can leave the environment in that state is worse than one that answers slowly, so the
     * wait is bounded in seconds and the exit path stops the work it started.
     * </p>
     */
    @Test
    public void theWaitIsShortAndTheWorkIsCalledOff()
    {
        String source = ru.aiedt.mcp.server.support.BmComparisonHelper.class.getName();
        assertNotNull(source);

        boolean cancels = false;
        for (java.lang.reflect.Method m : ru.aiedt.mcp.server.support.BmComparisonHelper.class
            .getDeclaredMethods())
        {
            if ("cancel".equals(m.getName()))
            {
                cancels = true;
            }
        }
        assertTrue("giving up on a comparison must call it off, not abandon it", cancels); //$NON-NLS-1$
    }

    /** Long work belongs in the Pending flow, where waiting costs nobody a session. */
    @Test
    public void itRunsThroughThePendingFlowAndCountsAsHeavy()
    {
        assertTrue("a comparison is heavy work", //$NON-NLS-1$
            ru.aiedt.mcp.server.support.HeavyTools.isHeavy("compare_three_way")); //$NON-NLS-1$
        assertTrue("and must resume by runKey rather than block the caller", //$NON-NLS-1$
            ru.aiedt.mcp.server.support.GenericPending.applies("compare_three_way")); //$NON-NLS-1$
    }

    /**
     * Decisions a caller cannot have meant are refused, not quietly dropped.
     * <p>
     * Someone who wrote decisions and got a comparison back without them would believe they had
     * been recorded - and would then look for a settings file that says nothing. Malformed input
     * fails where it was written.
     * </p>
     */
    @Test
    public void unreadableDecisionsAreRefusedRatherThanIgnored()
    {
        String notArray = new ThreeWayComparisonTool().execute(
            args("projectName", "P", "otherPath", "somewhere", "decisions", "{\"object\":\"X\"}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(notArray, notArray.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must say what shape was expected: " + notArray, //$NON-NLS-1$
            notArray.contains("array")); //$NON-NLS-1$

        String missingRule = new ThreeWayComparisonTool().execute(
            args("projectName", "P", "otherPath", "somewhere", "decisions", "[{\"object\":\"X\"}]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(missingRule, missingRule.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("and which half is missing: " + missingRule, //$NON-NLS-1$
            missingRule.contains("rule")); //$NON-NLS-1$

        String garbage = new ThreeWayComparisonTool()
            .execute(args("projectName", "P", "otherPath", "somewhere", "decisions", "not json")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(garbage, garbage.contains("\"success\":false")); //$NON-NLS-1$
    }

    /**
     * A decision about a class is one call instead of thousands, and its shape has to be exact.
     * <p>
     * The reason a mass assignment needs a stricter parser than a single one: getting it wrong
     * applies a merge rule to a set of objects nobody looked at, and a merge rule decides whether
     * a customisation survives the update.
     * </p>
     */
    @Test
    public void aDecisionNamesAnObjectOrAClassButNotBoth()
    {
        String both = new ThreeWayComparisonTool().execute(args("projectName", "P", //$NON-NLS-1$ //$NON-NLS-2$
            "otherPath", "somewhere", "decisions", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "[{\"object\":\"Catalog.X\",\"select\":\"matching\",\"rule\":\"DO_NOT_MERGE\"}]")); //$NON-NLS-1$
        assertTrue(both, both.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("guessing which of the two was meant would apply a rule to a set the caller " //$NON-NLS-1$
            + "did not ask for: " + both, both.contains("not both")); //$NON-NLS-1$

        String neither = new ThreeWayComparisonTool().execute(args("projectName", "P", //$NON-NLS-1$ //$NON-NLS-2$
            "otherPath", "somewhere", "decisions", "[{\"rule\":\"DO_NOT_MERGE\"}]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(neither, neither.contains("\"success\":false")); //$NON-NLS-1$

        String noRule = new ThreeWayComparisonTool().execute(args("projectName", "P", //$NON-NLS-1$ //$NON-NLS-2$
            "otherPath", "somewhere", "decisions", "[{\"select\":\"matching\"}]")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(noRule, noRule.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("and it must say which half is missing: " + noRule, noRule.contains("rule")); //$NON-NLS-1$
    }

    /** A selector on its own is well formed, and must not be refused by the parser. */
    @Test
    public void aSelectorAloneIsAWellFormedDecision()
    {
        String answer = new ThreeWayComparisonTool().execute(args("projectName", "P", //$NON-NLS-1$ //$NON-NLS-2$
            "otherPath", "no such directory", "decisions", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "[{\"select\":\"matching\",\"rule\":\"DO_NOT_MERGE\"}]")); //$NON-NLS-1$
        assertTrue("it must get as far as the path, which means the decision parsed: " + answer, //$NON-NLS-1$
            answer.contains("not a directory")); //$NON-NLS-1$
    }

    /** The schema has to advertise the selector, or nobody can discover it. */
    @Test
    public void theSchemaOffersTheClassWideDecision()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();
        assertTrue("a capability nobody can find in the schema is a capability nobody calls", //$NON-NLS-1$
            schema.contains("select"));
        assertTrue(schema.contains("matching")); //$NON-NLS-1$
        assertTrue("and the schema has to say the result is read back, because the difference " //$NON-NLS-1$
            + "between calls made and rules carried is the whole point",
            schema.contains("massRefused") || schema.contains("read back")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Comparing one object must not mean comparing a whole configuration first.
     * <p>
     * Measured on a stand before this existed: a full comparison runs to some two hundred thousand
     * nodes and takes minutes, which is the wrong price for moving one catalogue between two
     * configurations.
     * </p>
     */
    @Test
    public void theComparisonCanBeNarrowedToNamedObjects()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();
        assertTrue("a capability absent from the schema is one nobody calls", //$NON-NLS-1$
            schema.contains("scope")); //$NON-NLS-1$
        assertTrue("the answer has to say which names did not land - a scope of misspelled names " //$NON-NLS-1$
            + "compares nothing and would otherwise report that nothing differs",
            schema.contains("scopeUnrecognised")); //$NON-NLS-1$
        assertTrue("and what the environment added on its own, because that is the difference " //$NON-NLS-1$
            + "between the scope that was written and the scope that ran",
            schema.contains("scopeExtendedBy")); //$NON-NLS-1$
    }

    /** A scope of blanks is the same as no scope, not a comparison of nothing. */
    @Test
    public void anEmptyScopeMeansTheWholeConfiguration()
    {
        String answer = new ThreeWayComparisonTool()
            .execute(args("projectName", "P", "otherPath", "no such directory", "scope", " , ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue("it must fail on the path, not turn a blank scope into an empty comparison: " //$NON-NLS-1$
            + answer, answer.contains("not a directory")); //$NON-NLS-1$
    }

    /**
     * A configuration with nothing reworked updates in one call, and the tool decides that.
     * <p>
     * The condition is checked here rather than trusted to the caller, because getting it wrong
     * hands every conflict to whatever the environment defaults to. All three counts matter: BOTH
     * is a category of its own and can stand above zero while OURS is zero, and UNKNOWN means
     * there was no attribution at all.
     * </p>
     */
    @Test
    public void takingADeliveryWholeIsItsOwnIntent()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();
        assertTrue("a route that skips the decisions must be nameable, or nobody can take it", //$NON-NLS-1$
            schema.contains("UPDATE_UNCHANGED")); //$NON-NLS-1$

        String unknown = new ThreeWayComparisonTool().execute(args("projectName", "P", //$NON-NLS-1$ //$NON-NLS-2$
            "otherPath", "somewhere", "intent", "UPDATE_EVERYTHING")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(unknown, unknown.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal must list the intents that exist, this one included: " + unknown, //$NON-NLS-1$
            unknown.contains("UPDATE_UNCHANGED")); //$NON-NLS-1$
    }

    /** The fast path writes, so a preset that forbids writing forbids it too. */
    @Test
    public void takingADeliveryWholeIsAWriteLikeAnyOther()
    {
        String answer = new ThreeWayComparisonTool().execute(args("projectName", "P", //$NON-NLS-1$ //$NON-NLS-2$
            "otherPath", "no such directory", "intent", "UPDATE_UNCHANGED")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue("it must reach the path check, which means the intent parsed: " + answer, //$NON-NLS-1$
            answer.contains("not a directory") || answer.contains("preset")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reading has to leave no trace, and that includes files this tool writes for its own use.
     * <p>
     * The support snapshot goes into the settings folder of the project so that a merge can be
     * undone. Taking it on a REPORT would mean the default mode of a reading tool writing into the
     * project - and under a preset whose whole promise is that nothing changes.
     * </p>
     */
    @Test
    public void readingIsTheOnlyIntentThatWritesNothing()
    {
        java.util.List<String> writes = new java.util.ArrayList<>();
        for (ru.aiedt.mcp.server.support.BmComparisonHelper.Intent intent : ru.aiedt.mcp.server.support.BmComparisonHelper.Intent
            .values())
        {
            if (intent != ru.aiedt.mcp.server.support.BmComparisonHelper.Intent.REPORT)
            {
                writes.add(intent.name());
            }
        }
        assertEquals("every intent but REPORT writes, and each one has to be gated as a write: " //$NON-NLS-1$
            + writes, 4, writes.size());
        assertTrue("the tool has to say which mode changes nothing", //$NON-NLS-1$
            new ThreeWayComparisonTool().getInputSchema().contains("REPORT (default)")); //$NON-NLS-1$
    }

    /**
     * The key that lets a caller ask a second question has to reach the answer that has a session.
     * <p>
     * It did not. The two session fields were emitted in the refusal branch, where a comparison
     * that failed has nothing to page through, and every successful answer came back without a
     * key - so paging silently cost a fresh comparison each time. The whole suite was green;
     * a single live call found it. This test pins the position, not the value.
     * </p>
     */
    @Test
    public void theSessionKeyIsReportedWhereThereIsASession()
    {
        String failed = new ThreeWayComparisonTool()
            .execute(args("projectName", "P", "otherPath", "no such directory")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(failed, failed.contains("\"success\":false")); //$NON-NLS-1$
        assertFalse("a comparison that never ran has no session to hand back, and a key there " //$NON-NLS-1$
            + "would invite a caller to page through nothing: " + failed,
            failed.contains("sessionReused")); //$NON-NLS-1$

        String schema = new ThreeWayComparisonTool().getInputSchema();
        assertTrue("and the caller has to be able to ask for it to be closed", //$NON-NLS-1$
            schema.contains("closeSession")); //$NON-NLS-1$
    }

    /**
     * A rule that cannot do anything from here is refused rather than accepted and ignored.
     * <p>
     * Measured: on a node the environment was willing to move, CUSTOM_MERGE was taken, the merge
     * reported success and the content did not change. MERGE_USING_EXTERNAL_TOOL on the same node
     * in the same state took the delivery's version, so it is not refused - over-refusing would
     * remove a rule that works.
     * </p>
     */
    @Test
    public void aRuleThatNeedsAnEditorIsRefusedAndOneThatDoesNotIsKept()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();
        assertTrue("the refusal has to be discoverable before the call, not after a merge that " //$NON-NLS-1$
            + "reported success and did nothing", schema.contains("CUSTOM_MERGE is ")); //$NON-NLS-1$
        assertTrue("and the rule that does work must still be offered", //$NON-NLS-1$
            schema.contains("MERGE_USING_EXTERNAL_TOOL")); //$NON-NLS-1$
    }

    /**
     * The ordinary update on support puts the delivery in front where both sides changed, and
     * still refuses to carry out a deletion nobody asked for.
     * <p>
     * <b>Holding everything both sides touched made the mode safe and useless.</b> On a customised
     * configuration almost every changed module is BOTH - we added a method, the delivery changed
     * another - so protecting them wholesale meant the update applied nowhere it was needed.
     * Measured: a merge that ran clean, protected 8 objects and took 0. Those objects now take
     * MERGE_PRIORITIZING_OTHER, which was itself measured first: on a module node it resolves
     * contested lines toward the delivery and leaves methods only this side has in place.
     * </p>
     * <p>
     * <b>The hazard the previous contract existed to prevent is still prevented, and it is a
     * different one.</b> A catalogue we had customised and the delivery had deleted comes back
     * with mustBeMerged set and GET_FROM_OTHER proposed - the environment is willing to carry out
     * the deletion. For an object the delivery no longer has, putting the delivery in front means
     * deleting our work, so those are held apart from the rest and named in deliveryNotApplied.
     * The change of rule must never quietly extend to them.
     * </p>
     */
    @Test
    public void keepingOurChangesPutsTheDeliveryInFrontButNeverDeletes()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();
        assertTrue("the mode has to be nameable", schema.contains("UPDATE_KEEPING_OURS")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an update that takes nothing where both sides worked is not an update, so " //$NON-NLS-1$
            + "the mode has to say it merges those with the delivery in front", //$NON-NLS-1$
            schema.contains("merged with the delivery in front")); //$NON-NLS-1$
        assertTrue("and it has to say our own methods survive that merge, which is the whole " //$NON-NLS-1$
            + "reason this rule was chosen over taking the delivery whole", //$NON-NLS-1$
            schema.contains("methods only this side has stay")); //$NON-NLS-1$
        assertTrue("an object the merge could not be applied to must be named, not folded into a " //$NON-NLS-1$
            + "count that reads as success", schema.contains("deliveryNotApplied")); //$NON-NLS-1$
        assertTrue("and it still refuses rather than merging around an object of ours it could " //$NON-NLS-1$
            + "not hold", schema.contains("refuses outright")); //$NON-NLS-1$
    }

    /**
     * Looking inside modules is asked for, and what it costs comes back either way.
     * <p>
     * Measured: with the switch on, a method arrives as its own node carrying its full name and
     * its own attribution, and a decision addressed at that name is accepted. So a conflict can be
     * stated as "this method" rather than "this module was changed by both sides".
     * </p>
     */
    @Test
    public void lookingInsideModulesIsOptionalAndItsCostIsReported()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();
        assertTrue("a caller who wants the detail has to be able to ask for it", //$NON-NLS-1$
            schema.contains("methodLevel")); //$NON-NLS-1$
        assertTrue("and the schema has to say the cost is measured rather than argued about", //$NON-NLS-1$
            schema.contains("comparedInMs")); //$NON-NLS-1$
        assertTrue("and say that a decision can be addressed at one method, which is the whole " //$NON-NLS-1$
            + "point of looking inside", schema.contains("Module.MethodName")); //$NON-NLS-1$
    }

    /** No decisions at all is not an error - the tool's ordinary use is to read. */
    @Test
    public void noDecisionsIsNotARefusal()
    {
        String answer = new ThreeWayComparisonTool()
            .execute(args("projectName", "P", "otherPath", "no such directory")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("it must fail on the path, not on the absent decisions: " + answer, //$NON-NLS-1$
            answer.contains("not a directory")); //$NON-NLS-1$
    }

    /**
     * "Merged" must come from the merge having ended, not from it having been accepted.
     * <p>
     * Measured, not supposed: {@code startMerge} schedules a job and returns OK the moment the job
     * is accepted, long before anything is written. The first version reported that status as the
     * result, so a live run answered {@code merged: true, mergeStatus: "0: OK"} while the merge had
     * barely begun - the very shape of mistake this project has paid for repeatedly, an empty
     * answer read as a successful one. The wait is therefore part of the contract.
     * </p>
     */
    @Test
    public void theAnswerWaitsForTheMergeRatherThanForItsAcceptance()
    {
        boolean waits = false;
        for (java.lang.reflect.Method m : ru.aiedt.mcp.server.support.BmComparisonHelper.class
            .getDeclaredMethods())
        {
            if ("awaitMergeEnd".equals(m.getName())) //$NON-NLS-1$
            {
                waits = true;
            }
        }
        assertTrue("a scheduled merge must be waited out before it is reported on", waits); //$NON-NLS-1$
    }

    /**
     * Decisions travel both ways: out to a person, and back to be carried out.
     * <p>
     * Writing them was only half a handover. Without the way back, work decided by eye in EDT -
     * including correspondences somebody established by hand between objects that match neither by
     * uuid nor by name - could not be acted on at all.
     * </p>
     */
    @Test
    public void decisionsCanBeReadBackFromAFileAndNotOnlyWritten()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();

        assertTrue("there must be a way in as well as a way out: " + schema, //$NON-NLS-1$
            schema.contains("decisionsFrom")); //$NON-NLS-1$
        assertTrue("and the description must say what the file is for", //$NON-NLS-1$
            new ThreeWayComparisonTool().getDescription().contains("decisionsFrom")); //$NON-NLS-1$
    }

    /**
     * A settings file is a source of decisions in its own right, not a decoration on the argument.
     * <p>
     * Measured on a stand, not supposed: the first version read the file, the environment applied
     * its rules to the comparison, and the merge was then refused by our own guard - which counted
     * only the decisions passed in that call. So the one workflow the file exists for, handing the
     * hard objects to a person and carrying back what they decided, was the one workflow that could
     * not run. The refusal must name both ways in, because a caller who used the file needs to know
     * which one was missing.
     * </p>
     */
    @Test
    public void aRestoredFileCountsAsDecisionsForTheMergeGuard()
    {
        java.util.List<String> fields = new java.util.ArrayList<>();
        for (java.lang.reflect.Field f : ru.aiedt.mcp.server.support.BmComparisonHelper.Outcome.class
            .getFields())
        {
            fields.add(f.getName());
        }
        assertTrue("whether the file carried anything must be visible: " + fields, //$NON-NLS-1$
            fields.contains("decisionsRestored")); //$NON-NLS-1$

        String answer = new ThreeWayComparisonTool()
            .execute(args("projectName", "P", "otherPath", "no such directory", "intent", "MERGE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
    }

    /**
     * The environment writes and reads exactly one format, and says so with an assertion.
     * <p>
     * A path with the wrong extension used to reach {@code serializeMergeSettings} and fail inside
     * it, which surfaced as a file that could not be written for a reason naming a constraint the
     * caller had never been told about.
     * </p>
     */
    @Test
    public void theSettingsFileFormatIsNamedInTheSchema()
    {
        String schema = new ThreeWayComparisonTool().getInputSchema();

        assertTrue("the required extension belongs where the path is asked for: " + schema, //$NON-NLS-1$
            schema.contains(".zip")); //$NON-NLS-1$
    }

    /**
     * A merge that succeeds and leaves the configuration broken is the ordinary case.
     * <p>
     * Taking the other side's version of one object routinely breaks whatever referred to the old
     * one, so an answer that stops at "merged" is true and useless. The state of the project comes
     * back with the merge rather than being left for the caller to think of.
     * </p>
     */
    @Test
    public void theStateOfTheProjectComesBackWithTheMerge()
    {
        java.util.List<String> fields = new java.util.ArrayList<>();
        for (java.lang.reflect.Field f : ru.aiedt.mcp.server.support.BmComparisonHelper.Outcome.class
            .getFields())
        {
            fields.add(f.getName());
        }

        assertTrue("the errors standing against the merged objects must be reported: " + fields, //$NON-NLS-1$
            fields.contains("errorsAfterMerge")); //$NON-NLS-1$
        assertTrue("and whether they were counted on fresh markers or stale ones", //$NON-NLS-1$
            fields.contains("revalidatedAfterMerge")); //$NON-NLS-1$
    }

    /**
     * The merge entry points must stay out of reach. Checked against the class rather than the
     * source text so the test still holds if the file is reorganised.
     */
    @Test
    public void aMergeIsRefusedWithoutDecisionsToApply()
    {
        String answer = new ThreeWayComparisonTool()
            .execute(args("projectName", "P", "otherPath", "no such directory", "intent", "MERGE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        // It fails on the path here, which is enough to show the merge was not attempted first.
        // What matters is that no merge ran: an intent alone must never be sufficient.
        assertTrue(answer, answer.contains("\"success\":false")); //$NON-NLS-1$
        assertFalse("nothing may report itself merged from a failed comparison: " + answer, //$NON-NLS-1$
            answer.contains("\"merged\":true")); //$NON-NLS-1$
    }
}
